package com.wearbomber.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
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

private data class BlastEffect(val x: Int, val y: Int, val range: Int, val startedAt: Long)

class PhoneGameView(context: Context, private val sendToWatch: (String) -> Unit) : View(context) {
    var onHostRequested: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val tileBounds = RectF()
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
    private val blastEffects = mutableListOf<BlastEffect>()
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
        drawBackground(canvas)
        when (screen) {
            Screen.LOBBY -> drawLobby(canvas)
            Screen.GAME -> drawGame(canvas)
            Screen.CUSTOMIZER -> drawCustomizer(canvas)
            Screen.BUILDER -> drawBuilder(canvas)
        }
        if (screen == Screen.GAME && blastEffects.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawLobby(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        drawBrand(canvas, w * .075f, h * .065f, unit * .075f)
        text(canvas, "BOMBER ARENA", w * .075f + unit * .1f, h * .067f, unit * .024f,
            Color.rgb(111, 226, 238), true)
        text(canvas, "Pick your fight", w * .075f, h * .12f, unit * .06f, Color.WHITE, true)
        text(canvas, "CHOOSE A GAME MODE", w * .075f, h * .155f, unit * .022f,
            Color.rgb(158, 177, 201), true)
        val cardH = lobbyCardHeight()
        val gap = lobbyCardGap()
        GameMode.entries.forEachIndexed { index, mode ->
            val top = lobbyCardTop() + index * (cardH + gap)
            val selected = mode == selectedMode
            drawPanel(canvas, RectF(w * .055f, top, w * .945f, top + cardH),
                if (selected) Color.rgb(29, 48, 69) else Color.rgb(16, 25, 39),
                if (selected) Color.rgb(68, 225, 209) else Color.rgb(40, 58, 76))
            if (selected) {
                text(canvas, "SELECTED", w * .87f, top + cardH * .35f, unit * .016f,
                    Color.rgb(103, 238, 210), true, Paint.Align.RIGHT)
            } else {
                text(canvas, "0${index + 1}", w * .88f, top + cardH * .58f, unit * .028f,
                    Color.rgb(92, 113, 137), true, Paint.Align.RIGHT)
            }
            text(canvas, mode.title.uppercase(), w * .09f, top + cardH * .43f, unit * .033f,
                Color.WHITE, true)
            val subtitle = if (mode == GameMode.JUGGERNAUT) {
                "P${juggernautPlayer + 1} is the Juggernaut · tap to switch"
            } else mode.subtitle
            text(canvas, subtitle, w * .09f, top + cardH * .76f, unit * .021f,
                Color.rgb(164, 182, 199))
        }
        val actionsY = lobbyActionsTop()
        button(canvas, "CHARACTERS", w * .055f, actionsY, w * .49f, actionsY + h * .065f,
            Color.rgb(43, 62, 85))
        button(canvas, "MAP BUILDER", w * .51f, actionsY, w * .945f, actionsY + h * .065f,
            Color.rgb(43, 62, 85))
        button(canvas, "${maps[selectedMap].first.uppercase()}  ·  ${selectedMap + 1}/8",
            w * .055f, actionsY + h * .08f, w * .49f, actionsY + h * .145f,
            Color.rgb(23, 72, 81))
        button(canvas, "HOST PHONE", w * .51f, actionsY + h * .08f, w * .945f,
            actionsY + h * .145f, Color.rgb(23, 72, 81))
        text(canvas, connectionStatus, w * .075f, h * .916f, unit * .02f,
            Color.rgb(158, 177, 201))
        button(canvas, "START MATCH", w * .055f, h * .936f, w * .945f, h * .987f,
            Color.rgb(24, 153, 117))
    }

    private fun drawGame(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        text(canvas, selectedMode.title.uppercase(), w * .06f, h * .052f, unit * .048f,
            Color.WHITE, true)
        val mapLabel = if (selectedMode == GameMode.RACE) "Twisted Labyrinth · 15×15"
            else "${maps[selectedMap].first} · ${board.size}×${board.size}"
        text(canvas, mapLabel, w * .06f, h * .083f, unit * .022f, Color.rgb(161, 181, 202))
        val active = players[activePlayer]
        drawPanel(canvas, RectF(w * .69f, h * .025f, w * .94f, h * .09f),
            Color.rgb(18, 30, 45), Color.rgb(54, 91, 112))
        text(canvas, "P${activePlayer + 1}  ${active.hp} HP", w * .815f, h * .066f,
            unit * .024f, Color.rgb(134, 241, 197), true, Paint.Align.CENTER)
        drawBoard(canvas, RectF(w * .045f, h * .115f, w * .955f, h * .555f))
        drawControls(canvas)
        if (raceWon) {
            text(canvas, connectionStatus, w * .5f, h * .91f, unit * .025f,
                Color.rgb(255, 213, 82), true, Paint.Align.CENTER)
        }
        button(canvas, "EXIT TO LOBBY", w * .34f, h * .962f, w * .66f, h * .995f,
            Color.rgb(34, 47, 64))
    }

    private fun drawBoard(canvas: Canvas, bounds: RectF) {
        val rows = board.size
        val cols = board.first().size
        val cell = min(bounds.width() / cols, bounds.height() / rows)
        val left = bounds.centerX() - cell * cols / 2f
        val top = bounds.centerY() - cell * rows / 2f
        bounds.set(left - cell * .09f, top - cell * .09f,
            left + cell * (cols + .09f), top + cell * (rows + .09f))
        paint.color = Color.rgb(6, 12, 21)
        canvas.drawRoundRect(bounds, cell * .2f, cell * .2f, paint)
        paint.color = Color.rgb(46, 76, 91)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, cell * .025f)
        canvas.drawRoundRect(bounds, cell * .2f, cell * .2f, paint)
        paint.style = Paint.Style.FILL

        for (y in 0 until rows) for (x in 0 until cols) {
            val x0 = left + x * cell
            val y0 = top + y * cell
            tileBounds.set(x0 + cell * .025f, y0 + cell * .025f,
                x0 + cell * .975f, y0 + cell * .975f)
            drawTile(canvas, board[y][x], tileBounds, cell, x, y)
        }
        droppedItems.forEach { (position, code) ->
            val (x, y) = position
            if (x in 0 until cols && y in 0 until rows) {
                tileBounds.set(left + x * cell + cell * .17f, top + y * cell + cell * .17f,
                    left + (x + 1) * cell - cell * .17f, top + (y + 1) * cell - cell * .17f)
                paint.color = when (code) {
                    "F+", "NK", "LS", "PR", "SL" -> Color.rgb(245, 132, 57)
                    "SH", "RM", "GH", "IN", "FP" -> Color.rgb(72, 157, 231)
                    "DK", "MR", "CF", "FR", "SN" -> Color.rgb(173, 93, 220)
                    else -> Color.rgb(91, 205, 126)
                }
                canvas.drawRoundRect(tileBounds, cell * .12f, cell * .12f, paint)
                paint.color = Color.argb(100, 255, 255, 255)
                canvas.drawRoundRect(tileBounds.left + cell * .025f, tileBounds.top + cell * .025f,
                    tileBounds.right - cell * .025f, tileBounds.top + cell * .1f,
                    cell * .04f, cell * .04f, paint)
                text(canvas, code, tileBounds.centerX(), tileBounds.centerY() + cell * .08f, cell * .32f,
                    Color.BLACK, true, Paint.Align.CENTER)
            }
        }
        bombs.forEach { (x, y) ->
            drawBomb(canvas, left + (x + .5f) * cell, top + (y + .5f) * cell, cell)
        }
        drawBlastEffects(canvas, left, top, cell)
        players.forEachIndexed { index, player ->
            val cx = left + (player.x + .5f) * cell
            val cy = top + (player.y + .5f) * cell
            val size = cell * if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) .86f else .7f
            drawCharacter(canvas, cx, cy, size, player.spec.outfit.color, player.spec.accessory, "${index + 1}")
            if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) {
                text(canvas, "${player.hp}", cx, cy + cell * .47f, cell * .2f, Color.WHITE, true, Paint.Align.CENTER)
            }
        }
    }

    private val juggernautPlayer: Int
        get() = if (juggernautIsPhone) 0 else 1
    private var juggernautIsPhone = true

    private fun drawControls(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        val top = height * .59f
        val buttonSize = min(width * .18f, height * .075f)
        val centerX = width * .27f
        val centerY = top + height * .13f
        drawPanel(canvas, RectF(w * .045f, h * .595f, w * .51f, h * .9f),
            Color.rgb(13, 21, 33), Color.rgb(31, 48, 65))
        drawPanel(canvas, RectF(w * .55f, h * .615f, w * .955f, h * .9f),
            Color.rgb(13, 21, 33), Color.rgb(31, 48, 65))
        text(canvas, "MOVE", w * .27f, h * .625f, unit * .019f,
            Color.rgb(128, 155, 179), true, Paint.Align.CENTER)
        button(canvas, "↑", centerX - buttonSize / 2, centerY - buttonSize * 1.2f,
            centerX + buttonSize / 2, centerY - buttonSize * .2f, Color.rgb(42, 75, 96))
        button(canvas, "←", centerX - buttonSize * 1.2f, centerY - buttonSize / 2,
            centerX - buttonSize * .2f, centerY + buttonSize / 2, Color.rgb(42, 75, 96))
        button(canvas, "↓", centerX - buttonSize / 2, centerY + buttonSize * .2f,
            centerX + buttonSize / 2, centerY + buttonSize * 1.2f, Color.rgb(42, 75, 96))
        button(canvas, "→", centerX + buttonSize * .2f, centerY - buttonSize / 2,
            centerX + buttonSize * 1.2f, centerY + buttonSize / 2, Color.rgb(42, 75, 96))
        button(canvas, "✹  PLACE BOMB", w * .58f, top + h * .06f, w * .93f,
            top + h * .17f, Color.rgb(174, 66, 54))
        button(canvas, "✦  DETONATE", w * .58f, top + h * .2f, w * .93f,
            top + h * .31f, Color.rgb(105, 70, 157))
        text(canvas, "P1  ${players[0].hp} HP", w * .27f, h * .94f, unit * .023f,
            if (activePlayer == 0) Color.rgb(125, 232, 203) else Color.rgb(165, 181, 197),
            true, Paint.Align.CENTER)
        text(canvas, "P2  ${players[1].hp} HP", w * .73f, h * .94f, unit * .023f,
            if (activePlayer == 1) Color.rgb(255, 139, 151) else Color.rgb(165, 181, 197),
            true, Paint.Align.CENTER)
    }

    private fun drawCustomizer(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        val spec = specs[editingPlayer]
        drawScreenTitle(canvas, "PLAYER LOADOUT", "Build a bomber that feels like you")
        button(canvas, "EDITING PLAYER  ${editingPlayer + 1}   ·   TAP TO SWITCH",
            w * .07f, h * .12f, w * .93f, h * .19f, Color.rgb(37, 57, 78))
        drawPanel(canvas, RectF(w * .07f, h * .205f, w * .93f, h * .28f),
            Color.rgb(16, 26, 40), Color.rgb(38, 63, 81))
        text(canvas, spec.outfit.name, w * .16f, h * .24f, unit * .034f,
            Color.WHITE, true)
        text(canvas, "SIGNATURE LOOK", w * .16f, h * .264f, unit * .018f,
            Color.rgb(141, 167, 187), true)
        drawCharacter(canvas, w * .78f, h * .245f, unit * .082f, spec.outfit.color,
            spec.accessory, "${editingPlayer + 1}")

        text(canvas, "MAX HEALTH", w * .1f, h * .275f, unit * .019f,
            Color.rgb(158, 177, 201), true)
        button(canvas, "−", w * .1f, h * .29f, w * .34f, h * .35f, Color.rgb(49, 67, 87))
        drawPanel(canvas, RectF(w * .37f, h * .29f, w * .63f, h * .35f),
            Color.rgb(17, 29, 43), Color.rgb(37, 62, 78))
        text(canvas, "${spec.maxHp} HP", w * .5f, h * .328f, unit * .028f,
            Color.rgb(115, 235, 197), true, Paint.Align.CENTER)
        button(canvas, "+", w * .66f, h * .29f, w * .9f, h * .35f, Color.rgb(49, 67, 87))

        text(canvas, "MOVEMENT SPEED", w * .1f, h * .415f, unit * .019f,
            Color.rgb(158, 177, 201), true)
        button(canvas, "CHANGE SPEED  ·  ${spec.speed.name.replace('_', ' ')}",
            w * .1f, h * .44f, w * .9f, h * .5f, Color.rgb(35, 57, 76))
        text(canvas, "OUTFIT & ACCESSORY", w * .1f, h * .575f, unit * .019f,
            Color.rgb(158, 177, 201), true)
        button(canvas, "NEXT LOOK  ·  ${spec.outfit.name} / ${spec.accessory.name}",
            w * .1f, h * .6f, w * .9f, h * .66f, Color.rgb(35, 57, 76))
        text(canvas, "STARTING PERK", w * .1f, h * .735f, unit * .019f,
            Color.rgb(158, 177, 201), true)
        button(canvas, "CHANGE PERK  ·  ${spec.perk.name.replace('_', ' ')}",
            w * .1f, h * .76f, w * .9f, h * .82f, Color.rgb(35, 57, 76))
        button(canvas, "SAVE LOADOUT", w * .1f, h * .88f, w * .9f, h * .95f,
            Color.rgb(24, 153, 117))
    }

    private fun drawBuilder(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawScreenTitle(canvas, "MAP WORKSHOP", "Build an arena, tile by tile")
        button(canvas, "BACK", w * .76f, h * .045f, w * .93f, h * .105f,
            Color.rgb(34, 48, 65))
        val bounds = RectF(w * .07f, h * .17f, w * .93f, h * .76f)
        drawPanel(canvas, RectF(w * .045f, h * .15f, w * .955f, h * .78f),
            Color.rgb(12, 19, 30), Color.rgb(37, 59, 76))
        drawBuilderBoard(canvas, bounds)
        text(canvas, "TAP A TILE TO CYCLE  ·  FLOOR  /  STEEL  /  BRICK  /  PORTAL",
            w * .08f, h * .82f, min(w, h) * .017f, Color.rgb(162, 181, 199))
        button(canvas, "PLAY THIS ARENA", w * .08f, h * .87f, w * .92f, h * .94f,
            Color.rgb(24, 153, 117))
    }

    private fun drawBuilderBoard(canvas: Canvas, bounds: RectF) {
        val cell = min(bounds.width() / 11f, bounds.height() / 11f)
        val left = bounds.centerX() - cell * 5.5f
        val top = bounds.centerY() - cell * 5.5f
        for (y in 0..10) for (x in 0..10) {
            tileBounds.set(left + x * cell + cell * .025f, top + y * cell + cell * .025f,
                left + (x + 1) * cell - cell * .025f, top + (y + 1) * cell - cell * .025f)
            drawTile(canvas, builder[y][x], tileBounds, cell, x, y)
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
        val cardH = lobbyCardHeight()
        val gap = lobbyCardGap()
        GameMode.entries.forEachIndexed { index, mode ->
            val top = lobbyCardTop() + index * (cardH + gap)
            if (y in top..(top + cardH)) {
                selectedMode = mode
                if (mode == GameMode.JUGGERNAUT) juggernautIsPhone = !juggernautIsPhone
                return
            }
        }
        val actionsY = lobbyActionsTop()
        if (y in actionsY..(actionsY + h * .065f)) {
            if (x < width * .5f) screen = Screen.CUSTOMIZER else screen = Screen.BUILDER
        } else if (y in (actionsY + h * .08f)..(actionsY + h * .145f)) {
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
        if (y < height * .13f && x > width * .7f) {
            screen = Screen.LOBBY
            return
        }
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
        blastEffects.clear()
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
        val now = SystemClock.uptimeMillis()
        bombs.toList().forEach { (bx, by) ->
            val range = if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer) 4
                else players[activePlayer].blastRange
            blastEffects.add(BlastEffect(bx, by, range, now))
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

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.rgb(6, 11, 20))
        paint.color = Color.argb(20, 33, 212, 199)
        canvas.drawCircle(width * .94f, height * .13f, min(width, height) * .33f, paint)
        paint.color = Color.argb(17, 91, 128, 255)
        canvas.drawCircle(width * .04f, height * .83f, min(width, height) * .4f, paint)
        paint.color = Color.argb(16, 141, 181, 208)
        paint.strokeWidth = maxOf(1f, min(width, height) * .001f)
        canvas.drawLine(width * .05f, height * .155f, width * .95f, height * .155f, paint)
        canvas.drawLine(width * .05f, height * .895f, width * .95f, height * .895f, paint)
    }

    private fun drawBrand(canvas: Canvas, x: Float, y: Float, size: Float) {
        bounds.set(x, y, x + size, y + size)
        paint.color = Color.rgb(17, 41, 54)
        canvas.drawRoundRect(bounds, size * .22f, size * .22f, paint)
        paint.color = Color.rgb(74, 227, 205)
        canvas.drawRoundRect(x + size * .12f, y + size * .12f, x + size * .88f, y + size * .88f,
            size * .14f, size * .14f, paint)
        paint.color = Color.rgb(11, 25, 35)
        canvas.drawRect(x + size * .28f, y + size * .28f, x + size * .49f, y + size * .49f, paint)
        canvas.drawRect(x + size * .57f, y + size * .28f, x + size * .78f, y + size * .49f, paint)
        canvas.drawRect(x + size * .28f, y + size * .57f, x + size * .49f, y + size * .78f, paint)
        paint.color = Color.rgb(255, 195, 91)
        canvas.drawCircle(x + size * .675f, y + size * .675f, size * .105f, paint)
    }

    private fun drawScreenTitle(canvas: Canvas, title: String, subtitle: String) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        text(canvas, "BOMBER ARENA  /  ${title.uppercase()}", w * .075f, h * .052f,
            unit * .019f, Color.rgb(103, 225, 209), true)
        text(canvas, title, w * .075f, h * .09f, unit * .052f, Color.WHITE, true)
        text(canvas, subtitle, w * .075f, h * .112f, unit * .021f,
            Color.rgb(158, 177, 201))
    }

    private fun drawPanel(canvas: Canvas, rect: RectF, fill: Int, accent: Int) {
        val radius = min(width, height) * .018f
        val shadow = min(width, height) * .006f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(95, 0, 0, 0)
        canvas.drawRoundRect(rect.left, rect.top + shadow, rect.right, rect.bottom + shadow,
            radius, radius, paint)
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, min(width, height) * .0015f)
        paint.color = Color.argb(155, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect.left, rect.top + rect.height() * .28f,
            rect.left + min(width, height) * .008f, rect.bottom - rect.height() * .28f,
            radius * .4f, radius * .4f, paint.apply { color = accent })
    }

    private fun drawTile(canvas: Canvas, tile: Tile, rect: RectF, cell: Float, x: Int, y: Int) {
        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom
        when (tile) {
            Tile.FLOOR -> {
                paint.color = if ((x + y) % 2 == 0) Color.rgb(21, 39, 47) else Color.rgb(25, 45, 52)
                canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
                paint.color = Color.argb(36, 124, 191, 194)
                canvas.drawCircle(left + cell * .17f, top + cell * .17f, cell * .035f, paint)
            }
            Tile.STEEL -> {
                paint.color = Color.rgb(63, 83, 100)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                paint.color = Color.rgb(106, 133, 149)
                canvas.drawRoundRect(left + cell * .08f, top + cell * .07f, right - cell * .08f,
                    bottom - cell * .16f, cell * .07f, cell * .07f, paint)
                paint.color = Color.rgb(63, 83, 100)
                canvas.drawRoundRect(left + cell * .17f, top + cell * .17f, right - cell * .17f,
                    bottom - cell * .22f, cell * .045f, cell * .045f, paint)
                paint.color = Color.rgb(171, 201, 211)
                canvas.drawCircle(left + cell * .22f, top + cell * .2f, cell * .035f, paint)
                canvas.drawCircle(right - cell * .22f, bottom - cell * .25f, cell * .035f, paint)
            }
            Tile.BRICK -> {
                paint.color = Color.rgb(118, 55, 45)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(191, 83, 58)
                canvas.drawRoundRect(left + cell * .06f, top + cell * .06f, right - cell * .06f,
                    bottom - cell * .08f, cell * .055f, cell * .055f, paint)
                paint.color = Color.rgb(131, 57, 48)
                canvas.drawRect(left + cell * .06f, top + cell * .48f, right - cell * .06f,
                    top + cell * .55f, paint)
                canvas.drawRect(left + cell * .46f, top + cell * .06f, left + cell * .53f,
                    top + cell * .48f, paint)
                canvas.drawRect(left + cell * .29f, top + cell * .55f, left + cell * .36f,
                    bottom - cell * .08f, paint)
            }
            Tile.TELEPORT -> {
                paint.color = Color.rgb(50, 27, 85)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                paint.color = Color.rgb(160, 77, 228)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .36f, paint)
                paint.color = Color.rgb(75, 43, 134)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .24f, paint)
                paint.color = Color.rgb(221, 175, 255)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .1f, paint)
            }
            Tile.GOAL -> {
                paint.color = Color.rgb(82, 61, 26)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                paint.color = Color.rgb(255, 195, 61)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .36f, paint)
                paint.color = Color.rgb(255, 232, 150)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .23f, paint)
                paint.color = Color.rgb(227, 143, 37)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .1f, paint)
            }
        }
    }

    private fun drawBomb(canvas: Canvas, cx: Float, cy: Float, cell: Float) {
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawOval(cx - cell * .32f, cy + cell * .17f, cx + cell * .32f, cy + cell * .32f, paint)
        paint.color = Color.rgb(18, 24, 35)
        canvas.drawCircle(cx, cy, cell * .3f, paint)
        paint.color = Color.rgb(65, 77, 94)
        canvas.drawCircle(cx - cell * .09f, cy - cell * .1f, cell * .075f, paint)
        paint.color = Color.rgb(201, 116, 51)
        canvas.drawLine(cx + cell * .12f, cy - cell * .24f, cx + cell * .24f, cy - cell * .38f, paint.apply {
            strokeWidth = cell * .055f
            strokeCap = Paint.Cap.ROUND
        })
        paint.color = Color.rgb(255, 195, 72)
        canvas.drawCircle(cx + cell * .25f, cy - cell * .4f, cell * .055f, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawBlastEffects(canvas: Canvas, left: Float, top: Float, cell: Float) {
        val now = SystemClock.uptimeMillis()
        var index = blastEffects.lastIndex
        while (index >= 0) {
            val blast = blastEffects[index]
            val progress = (now - blast.startedAt).toFloat() / 520f
            if (progress >= 1f) {
                blastEffects.removeAt(index)
            } else {
                val cx = left + (blast.x + .5f) * cell
                val cy = top + (blast.y + .5f) * cell
                val alpha = ((1f - progress) * 230f).toInt().coerceIn(0, 230)
                val reach = cell * blast.range * (1f - (1f - progress) * (1f - progress))
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = cell * .72f * (1f - progress * .4f)
                paint.color = Color.argb(alpha / 3, 255, 66, 23)
                drawBlastRays(canvas, cx, cy, reach)
                paint.strokeWidth = cell * .32f * (1f - progress * .4f)
                paint.color = Color.argb(alpha, 255, 171, 60)
                drawBlastRays(canvas, cx, cy, reach)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 255, 238, 166)
                canvas.drawCircle(cx, cy, cell * (.2f + .18f * (1f - progress)), paint)
            }
            index--
        }
    }

    private fun drawBlastRays(canvas: Canvas, cx: Float, cy: Float, reach: Float) {
        canvas.drawLine(cx, cy, cx - reach, cy, paint)
        canvas.drawLine(cx, cy, cx + reach, cy, paint)
        canvas.drawLine(cx, cy, cx, cy - reach, paint)
        canvas.drawLine(cx, cy, cx, cy + reach, paint)
    }

    private fun drawCharacter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        accessory: Accessory,
        label: String
    ) {
        val radius = size * .5f
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawOval(cx - radius * .72f, cy + radius * .6f, cx + radius * .72f,
            cy + radius * .95f, paint)
        bounds.set(cx - radius * .72f, cy - radius * .72f, cx + radius * .72f, cy + radius * .72f)
        paint.color = color
        canvas.drawRoundRect(bounds, radius * .48f, radius * .48f, paint)
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawRoundRect(bounds.left + radius * .12f, bounds.top + radius * .1f,
            bounds.right - radius * .12f, bounds.top + radius * .3f, radius * .12f, radius * .12f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx - radius * .24f, cy - radius * .05f, radius * .16f, paint)
        canvas.drawCircle(cx + radius * .24f, cy - radius * .05f, radius * .16f, paint)
        paint.color = Color.rgb(13, 23, 33)
        canvas.drawCircle(cx - radius * .2f, cy - radius * .04f, radius * .07f, paint)
        canvas.drawCircle(cx + radius * .28f, cy - radius * .04f, radius * .07f, paint)
        drawAccessory(canvas, cx, cy, radius * .8f, accessory)
        text(canvas, label, cx, cy + radius * .52f, radius * .31f, Color.WHITE, true, Paint.Align.CENTER)
    }

    private fun lobbyCardTop() = height * .16f
    private fun lobbyCardHeight() = min(height * .075f, width * .16f)
    private fun lobbyCardGap() = min(height * .012f, width * .025f)
    private fun lobbyActionsTop() = height * .62f

    private fun card(canvas: Canvas, rect: RectF, color: Int) {
        paint.color = color
        canvas.drawRoundRect(rect, 14f, 14f, paint)
    }

    private fun button(canvas: Canvas, label: String, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val unit = min(width, height).toFloat()
        val radius = unit * .018f
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(l, t + unit * .005f, r, b + unit * .005f, radius, radius, paint)
        paint.color = color
        canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, unit * .0015f)
        paint.color = Color.argb(75, 220, 244, 255)
        canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(26, 255, 255, 255)
        canvas.drawRoundRect(l + unit * .005f, t + unit * .004f, r - unit * .005f,
            t + (b - t) * .32f, radius * .8f, radius * .8f, paint)
        text(canvas, label, (l + r) / 2f, (t + b) / 2f + unit * .009f,
            min(unit * .026f, (b - t) * .34f), Color.WHITE, true, Paint.Align.CENTER)
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
