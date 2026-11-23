package com.wearbomber.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.random.Random

private enum class GameMode(val title: String, val subtitle: String) {
    CARNAGE("Carnage", "85% drops · full power-up set"),
    CASUAL("Casual", "45% drops · classic power-ups"),
    HARDCORE("Hardcore", "1 hit · fast fuses · no shields"),
    JUGGERNAUT("Juggernaut", "2,250 HP · crushes soft bricks"),
    RACE("Race to Center", "Reach the gold tile at (7,7)")
}

private enum class Screen { LOBBY, GAME, CUSTOMIZER, BUILDER }
private enum class Tile { FLOOR, STEEL, BRICK, TELEPORT, GOAL }
private enum class SpeedTier(val cooldown: Long) { SLOW(800), NORMAL(500), FAST(320), TURBO_DASH(190) }
private enum class Outfit(val color: Int) {
    RED(Color.rgb(235, 75, 75)), BLUE(Color.rgb(65, 145, 245)), GREEN(Color.rgb(65, 195, 115)),
    PURPLE(Color.rgb(180, 90, 225)), ORANGE(Color.rgb(245, 155, 55)), CYAN(Color.rgb(45, 205, 205))
}
private enum class Accessory { NONE, CROWN, HORNS, HALO, VISOR, TOP_HAT }
private enum class Perk { NONE, SHIELD, KICK, PIERCE, REMOTE }

private data class CharacterSpec(
    var maxHp: Int = 150,
    var speed: SpeedTier = SpeedTier.NORMAL,
    var outfit: Outfit = Outfit.BLUE,
    var accessory: Accessory = Accessory.NONE,
    var perk: Perk = Perk.NONE
)

private data class Player(
    var x: Int,
    var y: Int,
    var hp: Int,
    val spec: CharacterSpec,
    var lastMoveAt: Long = 0L,
    var blastRange: Int = 2,
    val collectedPowerUps: MutableSet<String> = mutableSetOf()
)

class PhoneGameView(context: Context, private val sendToWatch: (String) -> Unit) : View(context) {
    var onHostRequested: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var screen = Screen.LOBBY
    private var selectedMode = GameMode.CARNAGE
    private var selectedMap = 0
    private var editingPlayer = 0
    private val specs = arrayOf(CharacterSpec(), CharacterSpec(outfit = Outfit.RED))
    private val builder = Array(11) { y -> Array(11) { x ->
        if (x == 0 || y == 0 || x == 10 || y == 10 || (x % 2 == 0 && y % 2 == 0)) Tile.STEEL else Tile.FLOOR
    } }
    private var board: Array<Array<Tile>> = emptyBoard(9)
    private var players = mutableListOf(Player(1, 1, 150, specs[0]), Player(7, 7, 150, specs[1]))
    private var activePlayer = 0
    private var handlingRemoteCommand = false
    private var raceWon = false
    private var bombs = mutableListOf<Pair<Int, Int>>()
    private val droppedItems = mutableMapOf<Pair<Int, Int>, String>()
    private var connectionStatus = "Bluetooth not connected"
    private val maps = listOf(
        "Classic Yard" to 9, "Twin Teleporters" to 9, "Crossfire" to 9, "Blue Maze" to 11,
        "Purple Circuit" to 11, "Labyrinth" to 11, "Long Road" to 15, "Twisted Center" to 15
    )
    private val powerUps = listOf(
        "F+" to "Fire Up", "B+" to "Bomb Up", "NK" to "Mega Nuke", "LS" to "Cross Laser",
        "KK" to "Bomb Kick", "GH" to "Ghost Walk", "SH" to "Shield", "RM" to "Remote Detonator",
        "SC" to "Scatter Bombs", "DS" to "Dash Boots", "CS" to "Chaos Swap", "VM" to "Vampire Drain",
        "PR" to "Pierce Blast", "CL" to "Cluster Bomb", "RN" to "Ring of Fire", "DG" to "Diagonal X",
        "SL" to "Super Laser", "MN" to "Hidden Mine", "MT" to "Meteor Shower", "NP" to "Napalm Trail",
        "LN" to "Line Bomb", "BH" to "Black Hole", "SW" to "Shockwave", "TP" to "Random Blink",
        "BD" to "Brick Fortress", "MG" to "Item Magnet", "TW" to "Twin Shot", "WS" to "Wall Smasher",
        "CF" to "Confuse Enemy", "FR" to "Freeze Enemy", "DI" to "Bomb Diarrhea", "SN" to "Snail Curse",
        "DK" to "Darkness Curse", "MR" to "Mirror Curse", "JK" to "Jackpot Rain", "BL" to "Blast Boots",
        "RF" to "Rapid Fire", "EX" to "Extra Life", "UP" to "Upgrade", "IN" to "Invisibility",
        "PW" to "Power Wave", "RB" to "Rebound", "AG" to "Agility", "SP" to "Speed Trap",
        "FP" to "Flameproof", "BM" to "Bombardier", "GL" to "Glove", "RV" to "Revive",
        "ST" to "Stun", "WT" to "Warp Tile"
    )
    fun setConnectionStatus(status: String) {
        connectionStatus = status
        invalidate()
    }

    fun handleRemoteCommand(command: String) {
        activePlayer = 1
        handlingRemoteCommand = true
        try {
            when (command) {
                "UP" -> movePlayer(0, -1)
                "DOWN" -> movePlayer(0, 1)
                "LEFT" -> movePlayer(-1, 0)
                "RIGHT" -> movePlayer(1, 0)
                "BOMB" -> placeBomb()
                "DETONATE" -> detonate()
            }
        } finally {
            handlingRemoteCommand = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(11, 15, 29))
        when (screen) {
            Screen.LOBBY -> drawLobby(canvas)
            Screen.GAME -> drawGame(canvas)
            Screen.CUSTOMIZER -> drawCustomizer(canvas)
            Screen.BUILDER -> drawBuilder(canvas)
        }
    }

    private fun drawLobby(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        text(canvas, "WEAR BOMBER", w * .08f, h * .08f, h * .045f, Color.WHITE, true)
        text(canvas, "Choose a mode", w * .08f, h * .13f, h * .022f, Color.LTGRAY)
        val cardH = min(h * .09f, 66f)
        GameMode.entries.forEachIndexed { index, mode ->
            val top = h * .16f + index * (cardH + 8f)
            card(canvas, RectF(w * .07f, top, w * .93f, top + cardH),
                if (mode == selectedMode) Color.rgb(68, 87, 155) else Color.rgb(29, 37, 61))
            text(canvas, mode.title, w * .11f, top + cardH * .43f, h * .023f, Color.WHITE, true)
            val subtitle = if (mode == GameMode.JUGGERNAUT) {
                "P${juggernautPlayer + 1} Juggernaut · tap to switch"
            } else mode.subtitle
            text(canvas, subtitle, w * .11f, top + cardH * .78f, h * .015f, Color.LTGRAY)
        }
        val actionsY = h * .69f
        button(canvas, "CHARACTERS", w * .07f, actionsY, w * .48f, actionsY + h * .07f, Color.rgb(70, 75, 106))
        button(canvas, "MAP BUILDER", w * .52f, actionsY, w * .93f, actionsY + h * .07f, Color.rgb(70, 75, 106))
        button(canvas, "MAP  ${selectedMap + 1}/8  ▸", w * .07f, actionsY + h * .085f, w * .48f, actionsY + h * .155f, Color.rgb(44, 104, 115))
        button(canvas, "HOST", w * .52f, actionsY + h * .085f, w * .93f, actionsY + h * .155f, Color.rgb(44, 104, 115))
        text(canvas, connectionStatus, w * .08f, h * .92f, h * .017f, Color.LTGRAY)
        text(canvas, "PLAY", w * .5f, h * .97f, h * .025f, Color.rgb(122, 240, 178), true, Paint.Align.CENTER)
    }

    private fun drawGame(canvas: Canvas) {
        val arenaBottom = height * .53f
        text(canvas, "${selectedMode.title}  ·  ${players[activePlayer].hp} HP", width * .05f, height * .045f, height * .025f, Color.WHITE, true)
        val mapLabel = if (selectedMode == GameMode.RACE) "Twisted Labyrinth · 15×15"
            else "${maps[selectedMap].first} · ${board.size}×${board.size}"
        text(canvas, mapLabel, width * .05f, height * .075f, height * .016f, Color.LTGRAY)
        drawBoard(canvas, RectF(width * .06f, height * .09f, width * .94f, arenaBottom))
        drawControls(canvas)
        if (raceWon) {
            text(canvas, connectionStatus, width * .5f, height * .91f, height * .02f,
                Color.rgb(255, 213, 82), true, Paint.Align.CENTER)
        }
        text(canvas, "LOBBY", width * .5f, height * .985f, height * .017f, Color.LTGRAY, false, Paint.Align.CENTER)
    }

    private fun drawBoard(canvas: Canvas, bounds: RectF) {
        val rows = board.size
        val cols = board.first().size
        val cell = min(bounds.width() / cols, bounds.height() / rows)
        val left = bounds.centerX() - cell * cols / 2f
        val top = bounds.centerY() - cell * rows / 2f
        for (y in 0 until rows) for (x in 0 until cols) {
            val rect = RectF(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell)
            val color = when (board[y][x]) {
                Tile.FLOOR -> if ((x + y) % 2 == 0) Color.rgb(38, 53, 58) else Color.rgb(43, 61, 64)
                Tile.STEEL -> Color.rgb(100, 111, 130)
                Tile.BRICK -> Color.rgb(155, 84, 55)
                Tile.TELEPORT -> Color.rgb(156, 65, 221)
                Tile.GOAL -> Color.rgb(240, 190, 53)
            }
            paint.color = color
            canvas.drawRect(rect, paint)
            if (board[y][x] == Tile.TELEPORT) {
                paint.color = Color.rgb(224, 172, 255)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .25f, paint)
            }
        }
        droppedItems.forEach { (position, code) ->
            val (x, y) = position
            if (x in 0 until cols && y in 0 until rows) {
                val rect = RectF(left + x * cell + cell * .17f, top + y * cell + cell * .17f,
                    left + (x + 1) * cell - cell * .17f, top + (y + 1) * cell - cell * .17f)
                paint.color = when (code) {
                    "F+", "NK", "LS", "PR", "SL" -> Color.rgb(245, 132, 57)
                    "SH", "RM", "GH", "IN", "FP" -> Color.rgb(72, 157, 231)
                    "DK", "MR", "CF", "FR", "SN" -> Color.rgb(173, 93, 220)
                    else -> Color.rgb(91, 205, 126)
                }
                canvas.drawRoundRect(rect, cell * .12f, cell * .12f, paint)
                text(canvas, code, rect.centerX(), rect.centerY() + cell * .08f, cell * .32f,
                    Color.BLACK, true, Paint.Align.CENTER)
            }
        }
        bombs.forEach { (x, y) ->
            paint.color = Color.BLACK
            canvas.drawCircle(left + (x + .5f) * cell, top + (y + .5f) * cell, cell * .32f, paint)
        }
        players.forEachIndexed { index, player ->
            val cx = left + (player.x + .5f) * cell
            val cy = top + (player.y + .5f) * cell
            paint.color = player.spec.outfit.color
            val radius = cell * if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) .43f else .32f
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy - radius * .2f, radius * .23f, paint)
            drawAccessory(canvas, cx, cy, radius, player.spec.accessory)
            if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) {
                text(canvas, "${player.hp}", cx, cy + radius * 1.8f, cell * .23f, Color.WHITE, true, Paint.Align.CENTER)
            }
        }
    }

    private val juggernautPlayer: Int
        get() = if (juggernautIsPhone) 0 else 1
    private var juggernautIsPhone = true

    private fun drawControls(canvas: Canvas) {
        val top = height * .59f
        val buttonSize = min(width * .18f, height * .075f)
        val centerX = width * .27f
        val centerY = top + height * .13f
        button(canvas, "↑", centerX - buttonSize / 2, centerY - buttonSize * 1.2f,
            centerX + buttonSize / 2, centerY - buttonSize * .2f, Color.rgb(62, 83, 117))
        button(canvas, "←", centerX - buttonSize * 1.2f, centerY - buttonSize / 2,
            centerX - buttonSize * .2f, centerY + buttonSize / 2, Color.rgb(62, 83, 117))
        button(canvas, "↓", centerX - buttonSize / 2, centerY + buttonSize * .2f,
            centerX + buttonSize / 2, centerY + buttonSize * 1.2f, Color.rgb(62, 83, 117))
        button(canvas, "→", centerX + buttonSize * .2f, centerY - buttonSize / 2,
            centerX + buttonSize * 1.2f, centerY + buttonSize / 2, Color.rgb(62, 83, 117))
        button(canvas, "BOMB", width * .58f, top + height * .06f, width * .93f, top + height * .17f, Color.rgb(190, 79, 63))
        button(canvas, "DETONATE", width * .58f, top + height * .2f, width * .93f, top + height * .31f, Color.rgb(126, 74, 174))
        text(canvas, "P1  ${players[0].hp} HP", width * .08f, height * .94f, height * .016f, Color.LTGRAY)
        text(canvas, "P2  ${players[1].hp} HP", width * .52f, height * .94f, height * .016f, Color.LTGRAY)
    }

    private fun drawCustomizer(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val spec = specs[editingPlayer]
        text(canvas, "CHARACTER MAKER", w * .08f, h * .08f, h * .035f, Color.WHITE, true)
        button(canvas, "EDIT P${editingPlayer + 1}  ▸", w * .08f, h * .12f, w * .92f, h * .19f, Color.rgb(61, 74, 115))
        text(canvas, "MAX HP: ${spec.maxHp}", w * .1f, h * .26f, h * .025f, Color.WHITE, true)
        button(canvas, "−", w * .1f, h * .29f, w * .34f, h * .35f, Color.rgb(65, 81, 110))
        button(canvas, "+", w * .66f, h * .29f, w * .9f, h * .35f, Color.rgb(65, 81, 110))
        text(canvas, "SPEED: ${spec.speed.name.replace('_', ' ')}", w * .1f, h * .41f, h * .022f, Color.WHITE)
        button(canvas, "CHANGE SPEED", w * .1f, h * .44f, w * .9f, h * .5f, Color.rgb(65, 81, 110))
        text(canvas, "OUTFIT: ${spec.outfit.name}  ·  HAT: ${spec.accessory.name}", w * .1f, h * .57f, h * .017f, Color.WHITE)
        button(canvas, "NEXT COLOR / ACCESSORY", w * .1f, h * .6f, w * .9f, h * .66f, Color.rgb(65, 81, 110))
        text(canvas, "STARTING PERK: ${spec.perk.name.replace('_', ' ')}", w * .1f, h * .73f, h * .019f, Color.WHITE)
        button(canvas, "CHANGE PERK", w * .1f, h * .76f, w * .9f, h * .82f, Color.rgb(65, 81, 110))
        button(canvas, "DONE", w * .1f, h * .88f, w * .9f, h * .95f, Color.rgb(44, 128, 99))
    }

    private fun drawBuilder(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        text(canvas, "11×11 MAP BUILDER", w * .08f, h * .08f, h * .033f, Color.WHITE, true)
        text(canvas, "Tap a tile to cycle: Floor · Steel · Brick · Teleporter", w * .08f, h * .12f, h * .016f, Color.LTGRAY)
        val bounds = RectF(w * .07f, h * .17f, w * .93f, h * .76f)
        drawBuilderBoard(canvas, bounds)
        text(canvas, "CYCLE TILE:  FLOOR  →  STEEL  →  BRICK  →  TELEPORT", w * .08f, h * .82f, h * .014f, Color.LTGRAY)
        button(canvas, "PLAY THIS MAP", w * .08f, h * .87f, w * .92f, h * .94f, Color.rgb(44, 128, 99))
    }

    private fun drawBuilderBoard(canvas: Canvas, bounds: RectF) {
        val cell = min(bounds.width() / 11f, bounds.height() / 11f)
        val left = bounds.centerX() - cell * 5.5f
        val top = bounds.centerY() - cell * 5.5f
        for (y in 0..10) for (x in 0..10) {
            val tile = builder[y][x]
            paint.color = when (tile) {
                Tile.FLOOR -> Color.rgb(48, 69, 68)
                Tile.STEEL -> Color.rgb(104, 116, 135)
                Tile.BRICK -> Color.rgb(155, 84, 55)
                Tile.TELEPORT -> Color.rgb(166, 68, 220)
                Tile.GOAL -> Color.rgb(240, 190, 53)
            }
            canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell - 1, top + (y + 1) * cell - 1, paint)
        }
    }

    private fun drawAccessory(canvas: Canvas, x: Float, y: Float, radius: Float, accessory: Accessory) {
        paint.color = when (accessory) {
            Accessory.CROWN -> Color.rgb(255, 206, 49)
            Accessory.HORNS -> Color.rgb(225, 75, 79)
            Accessory.HALO -> Color.rgb(255, 236, 107)
            Accessory.VISOR -> Color.rgb(83, 231, 236)
            Accessory.TOP_HAT -> Color.BLACK
            Accessory.NONE -> return
        }
        when (accessory) {
            Accessory.HALO -> canvas.drawOval(x - radius * .55f, y - radius * 1.45f, x + radius * .55f, y - radius * 1.15f, paint)
            Accessory.HORNS -> {
                canvas.drawCircle(x - radius * .65f, y - radius * .7f, radius * .25f, paint)
                canvas.drawCircle(x + radius * .65f, y - radius * .7f, radius * .25f, paint)
            }
            Accessory.TOP_HAT -> canvas.drawRect(x - radius * .48f, y - radius * 1.4f, x + radius * .48f, y - radius * .45f, paint)
            Accessory.CROWN -> canvas.drawRect(x - radius * .62f, y - radius * 1.05f, x + radius * .62f, y - radius * .72f, paint)
            Accessory.VISOR -> canvas.drawRect(x - radius * .66f, y - radius * .38f, x + radius * .66f, y - radius * .15f, paint)
            Accessory.NONE -> Unit
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        when (screen) {
            Screen.LOBBY -> handleLobbyTap(x, y)
            Screen.GAME -> handleGameTap(x, y)
            Screen.CUSTOMIZER -> handleCustomizerTap(x, y)
            Screen.BUILDER -> handleBuilderTap(x, y)
        }
        invalidate()
        return true
    }

    private fun handleLobbyTap(x: Float, y: Float) {
        val h = height.toFloat()
        val cardH = min(h * .09f, 66f)
        GameMode.entries.forEachIndexed { index, mode ->
            val top = h * .16f + index * (cardH + 8f)
            if (y in top..(top + cardH)) {
                selectedMode = mode
                if (mode == GameMode.JUGGERNAUT) juggernautIsPhone = !juggernautIsPhone
                return
            }
        }
        val actionsY = h * .69f
        if (y in actionsY..(actionsY + h * .07f)) {
            if (x < width * .5f) screen = Screen.CUSTOMIZER else screen = Screen.BUILDER
        } else if (y in (actionsY + h * .085f)..(actionsY + h * .155f)) {
            if (x < width * .5f) selectedMap = (selectedMap + 1) % maps.size
            else onHostRequested?.invoke()
        } else if (y > h * .94f) {
            startGame()
        }
    }

    private fun handleGameTap(x: Float, y: Float) {
        if (y > height * .96f) {
            screen = Screen.LOBBY
            return
        }
        if (y > height * .56f) {
            val centerX = width * .27f
            val centerY = height * .59f + height * .13f
            if (x > width * .55f) {
                if (y < height * .59f + height * .18f) placeBomb() else detonate()
            } else {
                val dx = x - centerX
                val dy = y - centerY
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) movePlayer(if (dx < 0) -1 else 1, 0)
                else movePlayer(0, if (dy < 0) -1 else 1)
            }
        } else if (x > width * .5f) {
            activePlayer = 1
        } else {
            activePlayer = 0
        }
    }

    private fun handleCustomizerTap(x: Float, y: Float) {
        val spec = specs[editingPlayer]
        when {
            y in height * .12f..height * .19f -> editingPlayer = 1 - editingPlayer
            y in height * .29f..height * .35f && x < width * .5f -> spec.maxHp = (spec.maxHp - 50).coerceAtLeast(50)
            y in height * .29f..height * .35f -> spec.maxHp = (spec.maxHp + 50).coerceAtMost(1000)
            y in height * .44f..height * .5f -> spec.speed = SpeedTier.entries[(spec.speed.ordinal + 1) % SpeedTier.entries.size]
            y in height * .6f..height * .66f -> {
                spec.outfit = Outfit.entries[(spec.outfit.ordinal + 1) % Outfit.entries.size]
                spec.accessory = Accessory.entries[(spec.accessory.ordinal + 1) % Accessory.entries.size]
            }
            y in height * .76f..height * .82f -> spec.perk = Perk.entries[(spec.perk.ordinal + 1) % Perk.entries.size]
            y > height * .87f -> screen = Screen.LOBBY
        }
    }

    private fun handleBuilderTap(x: Float, y: Float) {
        if (y > height * .85f) {
            board = Array(11) { row -> builder[row].clone() }
            selectedMap = 7
            screen = Screen.GAME
            raceWon = false
            startPlayers()
            return
        }
        if (y !in height * .17f..height * .76f) return
        val size = min(width * .86f, height * .59f)
        val cell = size / 11f
        val originX = (width - size) / 2f
        val originY = height * .17f + (height * .59f - size) / 2f
        val col = ((x - originX) / cell).toInt()
        val row = ((y - originY) / cell).toInt()
        if (row in 0..10 && col in 0..10) {
            builder[row][col] = when (builder[row][col]) {
                Tile.FLOOR -> Tile.STEEL
                Tile.STEEL -> Tile.BRICK
                Tile.BRICK -> Tile.TELEPORT
                Tile.TELEPORT, Tile.GOAL -> Tile.FLOOR
            }
        }
    }

    private fun startGame() {
        val size = if (selectedMode == GameMode.RACE) 15 else maps[selectedMap].second
        board = emptyBoard(size)
        if (selectedMode == GameMode.RACE) {
            for (x in 1..7) board[1][x] = Tile.FLOOR
            for (y in 1..7) board[y][7] = Tile.FLOOR
            for (x in 7..13) board[13][x] = Tile.FLOOR
            for (y in 7..13) board[y][7] = Tile.FLOOR
            board[7][7] = Tile.GOAL
        } else if (selectedMap > 0) {
            board[2][1] = Tile.TELEPORT
            board[size - 3][size - 2] = Tile.TELEPORT
        }
        screen = Screen.GAME
        bombs.clear()
        droppedItems.clear()
        raceWon = false
        startPlayers()
    }

    private fun startPlayers() {
        val last = board.size - 2
        players = mutableListOf(
            Player(1, 1, if (selectedMode == GameMode.JUGGERNAUT && juggernautPlayer == 0) 2250 else specs[0].maxHp, specs[0]),
            Player(last, last, if (selectedMode == GameMode.JUGGERNAUT && juggernautPlayer == 1) 2250 else specs[1].maxHp, specs[1])
        )
        if (selectedMode == GameMode.HARDCORE) players.forEach { it.hp = 50 }
    }

    private fun movePlayer(dx: Int, dy: Int) {
        if (selectedMode == GameMode.RACE && raceWon) return
        val player = players[activePlayer]
        val now = System.currentTimeMillis()
        val cooldown = if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer) {
            850L
        } else {
            player.spec.speed.cooldown
        }
        if (now - player.lastMoveAt < cooldown) return
        val nx = (player.x + dx).coerceIn(0, board.lastIndex)
        val ny = (player.y + dy).coerceIn(0, board.lastIndex)
        if (board[ny][nx] == Tile.STEEL) return
        if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer && board[ny][nx] == Tile.BRICK) {
            board[ny][nx] = Tile.FLOOR
        } else if (board[ny][nx] == Tile.BRICK) return
        player.x = nx
        player.y = ny
        player.lastMoveAt = now
        droppedItems.remove(nx to ny)?.let { code ->
            player.collectedPowerUps.add(code)
            if (code == "F+") player.blastRange = (player.blastRange + 1).coerceAtMost(8)
        }
        if (board[ny][nx] == Tile.TELEPORT) {
            player.x = board.size - 2 - nx + 1
            player.y = board.size - 2 - ny + 1
            player.x = player.x.coerceIn(1, board.lastIndex - 1)
            player.y = player.y.coerceIn(1, board.lastIndex - 1)
        }
        if (selectedMode == GameMode.RACE && board[ny][nx] == Tile.GOAL) {
            raceWon = true
            connectionStatus = "P${activePlayer + 1} wins the race!"
        }
        if (activePlayer == 1 && !handlingRemoteCommand) {
            sendToWatch(if (dx < 0) "LEFT" else if (dx > 0) "RIGHT" else if (dy < 0) "UP" else "DOWN")
        }
    }

    private fun placeBomb() {
        val player = players[activePlayer]
        if (bombs.none { it.first == player.x && it.second == player.y }) bombs.add(player.x to player.y)
        if (!handlingRemoteCommand) sendToWatch("BOMB")
        if (selectedMode == GameMode.HARDCORE) {
            val position = player.x to player.y
            postDelayed({ if (bombs.contains(position)) detonate() }, 700L)
        }
    }

    private fun detonate() {
        bombs.toList().forEach { (bx, by) ->
            val range = if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer) 4
                else players[activePlayer].blastRange
            players.forEachIndexed { index, player ->
                if ((player.x == bx && kotlin.math.abs(player.y - by) <= range) ||
                    (player.y == by && kotlin.math.abs(player.x - bx) <= range)
                ) {
                    if (selectedMode == GameMode.RACE) {
                        val last = board.size - 2
                        player.x = if (index == 0) 1 else last
                        player.y = if (index == 0) 1 else last
                    } else {
                        player.hp = (player.hp - 50).coerceAtLeast(0)
                    }
                }
            }
            for (step in 1..range) {
                listOf(bx + step to by, bx - step to by, bx to by + step, bx to by - step).forEach { (x, y) ->
                    if (x in 0..board.lastIndex && y in 0..board.lastIndex && board[y][x] == Tile.BRICK) {
                        board[y][x] = Tile.FLOOR
                        val dropChance = when (selectedMode) {
                            GameMode.CARNAGE -> 85
                            GameMode.CASUAL, GameMode.JUGGERNAUT -> 45
                            GameMode.HARDCORE, GameMode.RACE -> 0
                        }
                        if (Random.nextInt(100) < dropChance) {
                            val available = when (selectedMode) {
                                GameMode.CASUAL -> listOf("F+", "B+", "KK")
                                GameMode.JUGGERNAUT -> listOf(
                                    "F+", "B+", "KK", "NK", "LS", "PR", "CL", "SL", "WS", "BH", "SW", "MT", "RN"
                                )
                                else -> powerUps.map { it.first }
                            }
                            droppedItems[x to y] = available.random()
                        }
                    }
                }
            }
        }
        bombs.clear()
        if (!handlingRemoteCommand) sendToWatch("DETONATE")
    }

    private fun emptyBoard(size: Int): Array<Array<Tile>> = Array(size) { y -> Array(size) { x ->
        when {
            x == 0 || y == 0 || x == size - 1 || y == size - 1 || (x % 2 == 0 && y % 2 == 0) -> Tile.STEEL
            Random.nextInt(100) < (if (selectedMode == GameMode.CARNAGE) 55 else 40) -> Tile.BRICK
            else -> Tile.FLOOR
        }
    } }.also { map ->
        map[1][1] = Tile.FLOOR
        map[1][2] = Tile.FLOOR
        map[2][1] = Tile.FLOOR
        map[size - 2][size - 2] = Tile.FLOOR
        map[size - 2][size - 3] = Tile.FLOOR
        map[size - 3][size - 2] = Tile.FLOOR
    }

    private fun card(canvas: Canvas, rect: RectF, color: Int) {
        paint.color = color
        canvas.drawRoundRect(rect, 14f, 14f, paint)
    }

    private fun button(canvas: Canvas, label: String, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(RectF(l, t, r, b), 12f, 12f, paint)
        text(canvas, label, (l + r) / 2f, (t + b) / 2f + height * .007f,
            height * .019f, Color.WHITE, true, Paint.Align.CENTER)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textAlign = align
        canvas.drawText(value, x, y, paint)
    }
}
