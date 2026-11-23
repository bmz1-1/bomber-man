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
    RACE("Race to Center", "Reach the gold tile at (7,7)"),
    SPLEEF("Spleef", "No walls · blast away the floor"),
    SOLO("Solo", "Find and defeat monsters hiding in blocks")
}

private enum class Screen { LOBBY, GAME, CUSTOMIZER, BUILDER, SETTINGS, HELP, RESULTS }
private enum class Tile { FLOOR, STEEL, BRICK, TELEPORT, GOAL, HOLE }
private enum class SpeedTier(val cooldown: Long) { SLOW(680), NORMAL(425), FAST(270), TURBO_DASH(160) }
private const val MOVE_REPEAT_INTERVAL = 110L
private const val SCREEN_TRANSITION_DURATION = 180L
private const val BOMB_FUSE_MS = 3_000L
private enum class Outfit(val color: Int) {
    RED(Color.rgb(235, 75, 75)), BLUE(Color.rgb(65, 145, 245)), GREEN(Color.rgb(65, 195, 115)),
    PURPLE(Color.rgb(180, 90, 225)), ORANGE(Color.rgb(245, 155, 55)), CYAN(Color.rgb(45, 205, 205))
}

private enum class Accessory { NONE, CROWN, HORNS, HALO, VISOR, TOP_HAT }
private enum class Perk { NONE, SHIELD, KICK, PIERCE }
private enum class Debuff(
    val code: String,
    val displayName: String,
    val color: Int,
    val symbol: String,
    val durationMillis: Long
) {
    SLOW("SL", "Sluggish", Color.rgb(174, 166, 91), "S", 10_000L),
    REVERSE("RV", "Dizzy", Color.rgb(207, 109, 145), "R", 10_000L),
    WEAK_FLAME("WF", "Weak Flame", Color.rgb(197, 112, 82), "W", 20_000L),
    BOMB_LOCK("BL", "Bomb Lock", Color.rgb(144, 124, 174), "L", 10_000L),
    STUN("ST", "Stun", Color.rgb(224, 172, 75), "!", 3_000L),
    FRAGILE("FR", "Fragile", Color.rgb(224, 99, 99), "F", 15_000L),
    POISON("PS", "Poison", Color.rgb(112, 194, 106), "P", 15_000L),
    SHIELD_BREAK("SB", "Shield Break", Color.rgb(92, 152, 199), "X", 0L),
    DASH_DRAIN("DD", "Dash Drain", Color.rgb(105, 179, 179), "D", 15_000L),
    CURSE("CR", "Curse", Color.rgb(156, 110, 183), "C", 0L);

    companion object {
        fun fromCode(code: String): Debuff? = entries.firstOrNull { it.code == code }
    }
}
private enum class PowerUp(val code: String, val displayName: String, val color: Int, val symbol: String) {
    FIRE("F+", "Fire Up", Color.rgb(255, 119, 49), "✦"),
    BOMB("B+", "Bomb Up", Color.rgb(255, 198, 71), "●"),
    DASH("DS", "Dash Boots", Color.rgb(51, 218, 223), "»"),
    SHIELD("SH", "Energy Shield", Color.rgb(76, 158, 255), "⬡"),
    KICK("KK", "Bomb Kick", Color.rgb(176, 112, 255), "➤"),
    BLINK("TP", "Phase Blink", Color.rgb(211, 105, 255), "◎"),
    VAMPIRE("VM", "Vampire Flame", Color.rgb(255, 76, 115), "♥"),
    CLUSTER("CL", "Cluster Bomb", Color.rgb(255, 144, 58), "✣"),
    PIERCE("PR", "Piercing Flame", Color.rgb(255, 91, 67), "↟"),
    HEART("EX", "Extra Heart", Color.rgb(79, 224, 142), "+");

    companion object {
        fun fromCode(code: String): PowerUp? = entries.firstOrNull { it.code == code }
    }
}

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
    var bombCapacity: Int = 1,
    var shieldCharges: Int = 0,
    var dashLevel: Int = 0,
    var canKickBombs: Boolean = false,
    var vampireFlame: Boolean = false,
    var clusterBombs: Boolean = false,
    var piercingFlame: Boolean = false,
    val collectedPowerUps: MutableSet<String> = mutableSetOf(),
    val collectedDebuffs: MutableSet<String> = mutableSetOf(),
    val activeDebuffs: MutableMap<Debuff, Long> = mutableMapOf(),
    val reversibleDebuffs: MutableSet<Debuff> = mutableSetOf(),
    var lastWalkAt: Long = 0L,
    var walkFromX: Int = x,
    var walkFromY: Int = y,
    var vulnerableHits: Int = 0,
    var bombLocked: Boolean = false
)

private data class Bomb(var x: Int, var y: Int, val owner: Int, val plantedAt: Long)
private data class BlastEffect(val x: Int, val y: Int, val range: Int, val startedAt: Long)
private data class Monster(var x: Int, var y: Int, var hidden: Boolean = true)

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
    private var resultTitle = ""
    private var resultSubtitle = ""
    private var bombs = mutableListOf<Bomb>()
    private val monsters = mutableListOf<Monster>()
    private val blastEffects = mutableListOf<BlastEffect>()
    private val droppedItems = mutableMapOf<Pair<Int, Int>, String>()
    private var showFullPowerUpNames = false
    private var lastPowerUpMessage = ""
    private var powerUpMessageUntil = 0L
    private var connectionStatus = "Bluetooth not connected"
    private var screenTransitionStartedAt = 0L
    private var heldMove: Pair<Int, Int>? = null
    private var holdingMovement = false
    private val moveRepeat = object : Runnable {
        override fun run() {
            val direction = heldMove ?: return
            movePlayer(direction.first, direction.second)
            postDelayed(this, MOVE_REPEAT_INTERVAL)
        }
    }
    private val maps = listOf(
        "Classic Yard" to 9, "Twin Teleporters" to 9, "Crossfire" to 9, "Blue Maze" to 11,
        "Purple Circuit" to 11, "Labyrinth" to 11, "Long Road" to 15, "Twisted Center" to 15
    )
    private val powerUps = PowerUp.entries.toList()
    fun setConnectionStatus(status: String) {
        connectionStatus = status
        invalidate()
    }

    fun handleRemoteCommand(command: String) {
        if (command.startsWith("MODE:")) {
            GameMode.entries.firstOrNull { it.name == command.substringAfter(':') }?.let {
                selectedMode = it
                invalidate()
                if (screen == Screen.GAME) startGame()
            }
            return
        }
        if (command.startsWith("MAP:")) {
            command.substringAfter(':').toIntOrNull()?.let { selectedMap = it.coerceIn(maps.indices) }
            invalidate()
            if (screen == Screen.GAME) startGame()
            return
        }
        if (command == "START" || command.startsWith("START:")) {
            val parts = command.split(':')
            parts.getOrNull(1)?.let { modeName ->
                GameMode.entries.firstOrNull { it.name == modeName }?.let { selectedMode = it }
            }
            parts.getOrNull(2)?.toIntOrNull()?.let { selectedMap = it.coerceIn(maps.indices) }
            startGame()
            return
        }
        if (command.startsWith("CHAIN:")) {
            val values = command.substringAfter(':').split(':')
            val x = values.getOrNull(0)?.toIntOrNull() ?: return
            val y = values.getOrNull(1)?.toIntOrNull() ?: return
            bombs.firstOrNull { it.x == x && it.y == y }?.let { detonate(it, fromChain = true) }
            return
        }
        activePlayer = 1
        handlingRemoteCommand = true
        try {
            when (command) {
                "UP" -> movePlayer(0, -1)
                "DOWN" -> movePlayer(0, 1)
                "LEFT" -> movePlayer(-1, 0)
                "RIGHT" -> movePlayer(1, 0)
                "BOMB" -> placeBomb()
                "DETONATE" -> Unit
            }
        } finally {
            handlingRemoteCommand = false
            if (command in setOf("UP", "DOWN", "LEFT", "RIGHT")) {
                val watchPlayer = players[activePlayer]
                val positionCommand = if (activePlayer == 0) "HOST_POS" else "WATCH_POS"
                sendToWatch("$positionCommand:${watchPlayer.x}:${watchPlayer.y}")
            }
            invalidate()
        }
    }

    fun sendWatchSetup() {
        if (screen == Screen.GAME) sendToWatch("START:${selectedMode.name}:$selectedMap")
        else sendToWatch("MODE:${selectedMode.name}")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        players.forEach { expireDebuffs(it, now) }
        drawBackground(canvas)
        val transitionProgress = if (screenTransitionStartedAt == 0L) 1f else {
            ((SystemClock.uptimeMillis() - screenTransitionStartedAt).toFloat() / SCREEN_TRANSITION_DURATION)
                .coerceIn(0f, 1f)
        }
        val layer = if (transitionProgress < 1f) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                (transitionProgress * 255).toInt())
        } else {
            -1
        }
        canvas.save()
        canvas.translate(0f, (1f - transitionProgress) * height * .025f)
        when (screen) {
            Screen.LOBBY -> drawLobby(canvas)
            Screen.GAME -> drawGame(canvas)
            Screen.CUSTOMIZER -> drawCustomizer(canvas)
            Screen.BUILDER -> drawBuilder(canvas)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.HELP -> drawHelp(canvas)
            Screen.RESULTS -> drawResults(canvas)
        }
        canvas.restore()
        if (layer >= 0) canvas.restoreToCount(layer)
        if (transitionProgress < 1f) postInvalidateOnAnimation()
        if (screen == Screen.GAME && (bombs.isNotEmpty() || blastEffects.isNotEmpty() ||
                SystemClock.uptimeMillis() < powerUpMessageUntil)
        ) {
            postInvalidateOnAnimation()
        }
        if (screen == Screen.GAME && players.any { it.activeDebuffs.isNotEmpty() }) {
            postInvalidateDelayed(250L)
        }
        if (screen == Screen.GAME && players.any { now - it.lastWalkAt in 0L..420L }) {
            postInvalidateOnAnimation()
        }
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
        button(canvas, "SETTINGS", w * .055f, h * .75f, w * .49f, h * .81f,
            Color.rgb(35, 54, 74))
        button(canvas, "HOW TO PLAY", w * .51f, h * .75f, w * .945f, h * .81f,
            Color.rgb(35, 54, 74))
        text(canvas, connectionStatus, w * .075f, h * .88f, unit * .02f,
            Color.rgb(158, 177, 201))
        button(canvas, "START MATCH", w * .055f, h * .93f, w * .945f, h * .985f,
            Color.rgb(24, 153, 117))
    }

    private fun drawGame(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        text(canvas, selectedMode.title.uppercase(), w * .06f, h * .052f, unit * .048f,
            Color.WHITE, true)
        val mapLabel = when (selectedMode) {
            GameMode.RACE -> "Twisted Labyrinth · 15×15"
            GameMode.SPLEEF -> "Open Floor · ${board.size}×${board.size}"
            GameMode.SOLO -> "Monster Hunt · ${maps[selectedMap].first}"
            else -> "${maps[selectedMap].first} · ${board.size}×${board.size}"
        }
        text(canvas, mapLabel, w * .06f, h * .083f, unit * .022f, Color.rgb(161, 181, 202))
        val active = players[activePlayer]
        drawPanel(canvas, RectF(w * .69f, h * .025f, w * .94f, h * .09f),
            Color.rgb(18, 30, 45), Color.rgb(54, 91, 112))
        val maxHp = when {
            selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer -> 2250
            selectedMode == GameMode.HARDCORE -> 50
            else -> active.spec.maxHp
        }
        text(canvas, "PLAYER ${activePlayer + 1}", w * .715f, h * .052f,
            unit * .018f, Color.rgb(176, 197, 216), true)
        text(canvas, "${active.hp} HP", w * .915f, h * .052f,
            unit * .021f, Color.rgb(134, 241, 197), true, Paint.Align.RIGHT)
        drawHealthBar(canvas, w * .715f, h * .064f, w * .915f, h * .078f,
            active.hp.toFloat() / maxHp.coerceAtLeast(1))
        drawBoard(canvas, RectF(w * .045f, h * .115f, w * .955f, h * .555f))
        if (SystemClock.uptimeMillis() < powerUpMessageUntil) {
            text(canvas, lastPowerUpMessage, w * .5f, h * .583f, unit * .018f,
                Color.rgb(255, 221, 130), true, Paint.Align.CENTER)
        }
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
                val cx = left + (x + .5f) * cell
                val cy = top + (y + .5f) * cell
                PowerUp.fromCode(code)?.let {
                    drawPowerUp(canvas, cx, cy, cell, it)
                } ?: Debuff.fromCode(code)?.let {
                    paint.color = it.color
                    canvas.drawCircle(cx, cy, cell * .3f, paint)
                    text(canvas, it.symbol, cx, cy + cell * .1f, cell * .25f,
                        Color.WHITE, true, Paint.Align.CENTER)
                    text(canvas, it.code, cx, cy + cell * .4f, cell * .15f,
                        Color.WHITE, true, Paint.Align.CENTER)
                }
            }
        }
        bombs.forEach { bomb ->
            drawBomb(canvas, left + (bomb.x + .5f) * cell, top + (bomb.y + .5f) * cell,
                cell, bomb.plantedAt)
        }
        drawBlastEffects(canvas, left, top, cell)
        val now = SystemClock.uptimeMillis()
        players.forEachIndexed { index, player ->
            if (selectedMode == GameMode.SOLO && index == 1) return@forEachIndexed
            val walkProgress = ((now - player.lastWalkAt) / 170f).coerceIn(0f, 1f)
            val visualX = if (walkProgress < 1f) {
                player.walkFromX + (player.x - player.walkFromX) * walkProgress
            } else player.x.toFloat()
            val visualY = if (walkProgress < 1f) {
                player.walkFromY + (player.y - player.walkFromY) * walkProgress
            } else player.y.toFloat()
            val cx = left + (visualX + .5f) * cell
            val cy = top + (visualY + .5f) * cell
            val size = cell * if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) .86f else .7f
            val maxHp = when {
                selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer -> 2250
                selectedMode == GameMode.HARDCORE -> 50
                else -> player.spec.maxHp
            }
            drawCharacter(canvas, cx, cy, size, player.spec.outfit.color, player.spec.accessory,
                "${index + 1}", player.shieldCharges > 0,
                walking = SystemClock.uptimeMillis() - player.lastWalkAt in 0L..420L,
                healthRatio = player.hp.toFloat() / maxHp)
            if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) {
                text(canvas, "${player.hp}", cx, cy + cell * .47f, cell * .2f, Color.WHITE, true, Paint.Align.CENTER)
            }
        }
        monsters.filterNot { it.hidden }.forEach { monster ->
            drawCharacter(canvas, left + (monster.x + .5f) * cell, top + (monster.y + .5f) * cell,
                cell * .68f, Color.rgb(148, 202, 82), Accessory.HORNS, "M")
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
        val active = players[activePlayer]
        val bombsPlaced = bombs.count { it.owner == activePlayer }
        val bombLabel = if (active.bombLocked) "✹  BOMB LOCKED"
            else "✹  PLACE BOMB  $bombsPlaced/${active.bombCapacity}"
        button(canvas, bombLabel, w * .58f, top + h * .06f, w * .93f,
            top + h * .17f, Color.rgb(174, 66, 54))
        text(canvas, "LOADOUT", w * .755f, top + h * .225f, unit * .019f,
            Color.rgb(128, 155, 179), true, Paint.Align.CENTER)
        val powerUpCodes = active.collectedPowerUps.toList().takeLast(6)
        text(canvas, if (powerUpCodes.isEmpty()) "Power-ups: none" else "Power-ups: ${powerUpCodes.joinToString(" ")}",
            w * .585f, top + h * .265f, unit * .015f, Color.rgb(135, 224, 185))
        text(canvas, activeDebuffSummary(active, SystemClock.uptimeMillis()),
            w * .585f, top + h * .30f, unit * .015f, Color.rgb(240, 151, 145))
        text(canvas, "P1  ${players[0].hp} HP", w * .27f, h * .94f, unit * .023f,
            if (activePlayer == 0) Color.rgb(125, 232, 203) else Color.rgb(165, 181, 197),
            true, Paint.Align.CENTER)
        text(canvas, if (selectedMode == GameMode.SOLO) "MONSTERS  ${monsters.size}" else "P2  ${players[1].hp} HP",
            w * .73f, h * .94f, unit * .023f,
            if (activePlayer == 1) Color.rgb(255, 139, 151) else Color.rgb(165, 181, 197),
            true, Paint.Align.CENTER)
    }

    private fun drawPowerUp(canvas: Canvas, cx: Float, cy: Float, cell: Float, powerUp: PowerUp) {
        paint.color = Color.argb(55, Color.red(powerUp.color), Color.green(powerUp.color), Color.blue(powerUp.color))
        canvas.drawCircle(cx, cy, cell * .43f, paint)
        paint.color = Color.rgb(12, 19, 30)
        canvas.drawCircle(cx, cy, cell * .34f, paint)
        paint.color = powerUp.color
        canvas.drawCircle(cx, cy, cell * .29f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, cell * .035f)
        paint.color = Color.argb(210, 255, 255, 255)
        canvas.drawCircle(cx, cy, cell * .29f, paint)
        paint.style = Paint.Style.FILL
        text(canvas, powerUp.symbol, cx, cy + cell * .08f, cell * .3f,
            Color.WHITE, true, Paint.Align.CENTER)
        text(canvas, powerUp.code, cx, cy + cell * .4f, cell * .15f,
            Color.WHITE, true, Paint.Align.CENTER)
    }

    private fun drawHealthBar(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, health: Float) {
        bounds.set(left, top, right, bottom)
        paint.color = Color.rgb(35, 49, 62)
        canvas.drawRoundRect(bounds, bounds.height() / 2f, bounds.height() / 2f, paint)
        val fillRight = left + bounds.width() * health.coerceIn(0f, 1f)
        if (fillRight > left) {
            paint.color = if (health > .35f) Color.rgb(76, 222, 164) else Color.rgb(255, 105, 92)
            canvas.drawRoundRect(left, top, fillRight, bottom, bounds.height() / 2f, bounds.height() / 2f, paint)
        }
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

    private fun drawSettings(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        drawScreenTitle(canvas, "GAME SETTINGS", "Make the match easier to read and control")
        drawPanel(canvas, RectF(w * .07f, h * .2f, w * .93f, h * .33f),
            Color.rgb(16, 26, 40), Color.rgb(38, 63, 81))
        text(canvas, "POWER-UP NAMES", w * .12f, h * .245f, unit * .025f,
            Color.WHITE, true)
        text(canvas, "Show short codes or full item names", w * .12f, h * .29f,
            unit * .019f, Color.rgb(158, 177, 201))
        button(canvas, "POWER-UP NAMES  ·  ${if (showFullPowerUpNames) "FULL" else "SHORT"}",
            w * .1f, h * .36f, w * .9f, h * .43f, Color.rgb(35, 57, 76))
        drawPanel(canvas, RectF(w * .07f, h * .47f, w * .93f, h * .68f),
            Color.rgb(16, 26, 40), Color.rgb(38, 63, 81))
        text(canvas, "HOLD TO MOVE", w * .12f, h * .52f, unit * .025f,
            Color.WHITE, true)
        text(canvas, "Keep a direction pressed to move repeatedly.", w * .12f, h * .57f,
            unit * .019f, Color.rgb(158, 177, 201))
        text(canvas, "Movement speed can be changed in Characters.", w * .12f, h * .62f,
            unit * .018f, Color.rgb(158, 177, 201))
        button(canvas, "BACK TO MENU", w * .1f, h * .86f, w * .9f, h * .93f,
            Color.rgb(24, 153, 117))
    }

    private fun drawHelp(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        drawScreenTitle(canvas, "HOW TO PLAY", "Quick guide to the arena")
        val tips = listOf(
            "MOVE" to "Tap or hold a D-pad direction to walk.",
            "BOMBS" to "3-second fuse; blasts can trigger other bombs.",
            "POWER-UPS" to "Break bricks and collect upgrades for your bomber.",
            "MATCHES" to "Choose a mode and map, then start a local match.",
            "BLUETOOTH" to "Host on your phone, then connect a paired watch."
        )
        tips.forEachIndexed { index, tip ->
            val top = h * (.19f + index * .12f)
            drawPanel(canvas, RectF(w * .07f, top, w * .93f, top + h * .095f),
                Color.rgb(16, 26, 40), Color.rgb(38, 63, 81))
            text(canvas, tip.first, w * .11f, top + h * .038f, unit * .021f,
                Color.rgb(115, 235, 197), true)
            text(canvas, tip.second, w * .11f, top + h * .073f, unit * .017f,
                Color.rgb(190, 204, 219))
        }
        button(canvas, "BACK TO MENU", w * .1f, h * .86f, w * .9f, h * .93f,
            Color.rgb(24, 153, 117))
    }

    private fun drawResults(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        drawScreenTitle(canvas, resultTitle, resultSubtitle)
        drawPanel(canvas, RectF(w * .08f, h * .28f, w * .92f, h * .55f),
            Color.rgb(16, 26, 40), Color.rgb(68, 225, 209))
        text(canvas, selectedMode.title.uppercase(), w * .5f, h * .37f, unit * .034f,
            Color.rgb(111, 226, 238), true, Paint.Align.CENTER)
        text(canvas, resultSubtitle, w * .5f, h * .45f, unit * .024f,
            Color.WHITE, true, Paint.Align.CENTER)
        button(canvas, "REPLAY", w * .1f, h * .62f, w * .9f, h * .7f,
            Color.rgb(24, 153, 117))
        button(canvas, "PLAY SOLO", w * .1f, h * .73f, w * .9f, h * .81f,
            Color.rgb(35, 89, 104))
        button(canvas, "MAIN MENU", w * .1f, h * .84f, w * .9f, h * .92f,
            Color.rgb(34, 48, 65))
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (screen == Screen.GAME) {
                    directionAt(event.x, event.y)?.let { direction ->
                        holdingMovement = true
                        heldMove = direction
                        movePlayer(direction.first, direction.second)
                        postDelayed(moveRepeat, MOVE_REPEAT_INTERVAL)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                stopHeldMovement()
                if (!holdingMovement) {
                    when (screen) {
                        Screen.LOBBY -> handleLobbyTap(event.x, event.y)
                        Screen.GAME -> handleGameTap(event.x, event.y)
                        Screen.CUSTOMIZER -> handleCustomizerTap(event.x, event.y)
                        Screen.BUILDER -> handleBuilderTap(event.x, event.y)
                        Screen.SETTINGS -> handleSettingsTap(event.x, event.y)
                        Screen.HELP -> changeScreen(Screen.LOBBY)
                        Screen.RESULTS -> handleResultsTap(event.y)
                    }
                }
                holdingMovement = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopHeldMovement()
                holdingMovement = false
                return true
            }
        }
        return true
    }

    private fun directionAt(x: Float, y: Float): Pair<Int, Int>? {
        val centerX = width * .27f
        val centerY = height * .59f + height * .13f
        val buttonSize = min(width * .18f, height * .075f)
        val dx = x - centerX
        val dy = y - centerY
        if (kotlin.math.abs(dx) > buttonSize * 1.2f || kotlin.math.abs(dy) > buttonSize * 1.2f) {
            return null
        }
        return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            (if (dx < 0f) -1 else 1) to 0
        } else {
            0 to (if (dy < 0f) -1 else 1)
        }
    }

    private fun stopHeldMovement() {
        removeCallbacks(moveRepeat)
        heldMove = null
    }

    private fun handleLobbyTap(x: Float, y: Float) {
        val h = height.toFloat()
        val cardH = lobbyCardHeight()
        val gap = lobbyCardGap()
        GameMode.entries.forEachIndexed { index, mode ->
            val top = lobbyCardTop() + index * (cardH + gap)
            if (y in top..(top + cardH)) {
                selectedMode = mode
                sendToWatch("MODE:${mode.name}")
                if (mode == GameMode.JUGGERNAUT) juggernautIsPhone = !juggernautIsPhone
                return
            }
        }
        val actionsY = lobbyActionsTop()
        if (y in actionsY..(actionsY + h * .065f)) {
            if (x < width * .5f) changeScreen(Screen.CUSTOMIZER) else changeScreen(Screen.BUILDER)
        } else if (y in (actionsY + h * .08f)..(actionsY + h * .145f)) {
            if (x < width * .5f) {
                selectedMap = (selectedMap + 1) % maps.size
                sendToWatch("MAP:$selectedMap")
            }
            else onHostRequested?.invoke()
        } else if (y in h * .75f..h * .81f) {
            changeScreen(if (x < width * .5f) Screen.SETTINGS else Screen.HELP)
        } else if (y > h * .92f) {
            startGame()
        }
    }

    private fun handleGameTap(x: Float, y: Float) {
        if (y > height * .96f) {
            changeScreen(Screen.LOBBY)
            return
        }
        if (y > height * .56f) {
            if (x > width * .55f) {
                if (y in height * .65f..height * .77f) placeBomb()
            } else {
                val centerX = width * .27f
                val centerY = height * .59f + height * .13f
                val dx = x - centerX
                val dy = y - centerY
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) movePlayer(if (dx < 0) -1 else 1, 0)
                else movePlayer(0, if (dy < 0) -1 else 1)
            }
        } else if (x > width * .5f) {
            activePlayer = if (selectedMode == GameMode.SOLO) 0 else 1
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
            y > height * .87f -> changeScreen(Screen.LOBBY)
        }
    }

    private fun handleSettingsTap(x: Float, y: Float) {
        when {
            y in height * .36f..height * .43f -> showFullPowerUpNames = !showFullPowerUpNames
            y > height * .84f -> changeScreen(Screen.LOBBY)
            y < height * .12f && x < width * .25f -> changeScreen(Screen.LOBBY)
        }
    }

    private fun handleResultsTap(y: Float) {
        when {
            y in height * .62f..height * .7f -> startGame()
            y in height * .73f..height * .81f -> {
                selectedMode = GameMode.SOLO
                startGame()
            }
            y > height * .84f -> changeScreen(Screen.LOBBY)
        }
    }

    private fun handleBuilderTap(x: Float, y: Float) {
        if (y < height * .13f && x > width * .7f) {
            changeScreen(Screen.LOBBY)
            return
        }
        if (y > height * .85f) {
            board = Array(11) { row -> builder[row].clone() }
            selectedMap = 7
            changeScreen(Screen.GAME)
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
                Tile.TELEPORT, Tile.GOAL, Tile.HOLE -> Tile.FLOOR
            }
        }
    }

    private fun startGame() {
        val size = when (selectedMode) {
            GameMode.RACE -> 15
            GameMode.SPLEEF -> 13
            else -> maps[selectedMap].second
        }
        board = if (selectedMode == GameMode.SPLEEF) {
            Array(size) { Array(size) { Tile.FLOOR } }
        } else {
            emptyBoard(size)
        }
        if (selectedMode == GameMode.RACE) {
            for (x in 1..7) board[1][x] = Tile.FLOOR
            for (y in 1..7) board[y][7] = Tile.FLOOR
            for (x in 7..13) board[13][x] = Tile.FLOOR
            for (y in 7..13) board[y][7] = Tile.FLOOR
            board[7][7] = Tile.GOAL
        } else if (selectedMode != GameMode.SPLEEF && selectedMap > 0) {
            board[2][1] = Tile.TELEPORT
            board[size - 3][size - 2] = Tile.TELEPORT
        }
        changeScreen(Screen.GAME)
        bombs.clear()
        blastEffects.clear()
        droppedItems.clear()
        monsters.clear()
        raceWon = false
        powerUpMessageUntil = 0L
        startPlayers()
        sendToWatch("START:${selectedMode.name}:$selectedMap")
        if (selectedMode == GameMode.SOLO) {
            val monsterBricks = board.indices.flatMap { y ->
                board[y].indices.mapNotNull { x -> if (board[y][x] == Tile.BRICK) x to y else null }
            }.filter { (x, y) -> x > 2 && y > 2 && x < board.lastIndex - 2 && y < board.lastIndex - 2 }
            monsterBricks.shuffled().take(4).forEach { (x, y) -> monsters.add(Monster(x, y)) }
        }
    }

    private fun startPlayers() {
        val last = board.size - 2
        activePlayer = 0
        players = mutableListOf(
            Player(1, 1, if (selectedMode == GameMode.JUGGERNAUT && juggernautPlayer == 0) 2250 else specs[0].maxHp, specs[0]),
            Player(last, last, if (selectedMode == GameMode.JUGGERNAUT && juggernautPlayer == 1) 2250 else specs[1].maxHp, specs[1])
        )
        if (selectedMode == GameMode.SPLEEF) {
            players[0].x = 1
            players[0].y = 1
            players[1].x = last - 1
            players[1].y = last - 1
        }
        if (selectedMode == GameMode.SOLO) players[1].hp = 0
        if (selectedMode == GameMode.HARDCORE) players.forEach { it.hp = 50 }
    }

    private fun changeScreen(next: Screen) {
        if (screen == next) return
        screen = next
        screenTransitionStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun finishMatch(winner: Int?, message: String) {
        if (screen != Screen.GAME) return
        stopHeldMovement()
        holdingMovement = false
        resultTitle = when {
            selectedMode == GameMode.SOLO && winner == 0 -> "YOU WIN!"
            selectedMode == GameMode.SOLO -> "GAME OVER"
            winner == 0 -> "PLAYER 1 WINS"
            winner == 1 -> "PLAYER 2 WINS"
            else -> "DRAW"
        }
        resultSubtitle = message
        bombs.clear()
        sendToWatch("RESULT:${winner ?: -1}|$message")
        changeScreen(Screen.RESULTS)
    }

    private fun updateMonsters(affectedCells: Set<Pair<Int, Int>> = emptySet()) {
        if (selectedMode != GameMode.SOLO || screen != Screen.GAME) return
        monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells }
        val player = players[0]
        monsters.filterNot { it.hidden }.forEach { monster ->
            val distance = kotlin.math.abs(monster.x - player.x) + kotlin.math.abs(monster.y - player.y)
            if (distance <= 1) {
                player.hp = (player.hp - 25).coerceAtLeast(0)
                if (player.hp == 0) {
                    finishMatch(null, "The monsters defeated you.")
                    return
                }
            } else {
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    .map { (dx, dy) -> monster.x + dx to monster.y + dy }
                    .filter { (x, y) ->
                        x in board.indices && y in board.indices &&
                            board[y][x] == Tile.FLOOR && bombs.none { it.x == x && it.y == y }
                    }
                    .minByOrNull { (x, y) -> kotlin.math.abs(x - player.x) + kotlin.math.abs(y - player.y) }
                    ?.let { (x, y) ->
                        monster.x = x
                        monster.y = y
                    }
                if (kotlin.math.abs(monster.x - player.x) + kotlin.math.abs(monster.y - player.y) <= 1) {
                    player.hp = (player.hp - 25).coerceAtLeast(0)
                    if (player.hp == 0) {
                        finishMatch(null, "The monsters defeated you.")
                        return
                    }
                }
            }
        }
        if (monsters.isEmpty()) finishMatch(0, "All monsters defeated.")
        invalidate()
    }

    private fun movePlayer(dx: Int, dy: Int) {
        if (screen != Screen.GAME) return
        val player = players[activePlayer]
        expireDebuffs(player, SystemClock.uptimeMillis())
        if (Debuff.STUN in player.activeDebuffs) {
            invalidate()
            return
        }
        val moveDx = if (Debuff.REVERSE in player.activeDebuffs) -dx else dx
        val moveDy = if (Debuff.REVERSE in player.activeDebuffs) -dy else dy
        val now = System.currentTimeMillis()
        val baseCooldown = if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer) {
            850L
        } else {
            player.spec.speed.cooldown + if (Debuff.SLOW in player.activeDebuffs) 220L else 0L
        }
        val cooldown = (baseCooldown - player.dashLevel * 65L).coerceAtLeast(120L)
        if (now - player.lastMoveAt < cooldown) return
        val nx = player.x + moveDx
        val ny = player.y + moveDy
        if (selectedMode == GameMode.SPLEEF &&
            (nx !in board.indices || ny !in board.indices || board[ny][nx] == Tile.HOLE)
        ) {
            finishMatch(1 - activePlayer, "Player ${activePlayer + 1} fell off the arena.")
            return
        }
        if (nx !in board.indices || ny !in board.indices) return
        if (board[ny][nx] == Tile.STEEL) return
        val blockingBomb = bombs.firstOrNull { it.x == nx && it.y == ny }
        if (blockingBomb != null) {
            val pushX = nx + moveDx
            val pushY = ny + moveDy
            if (!player.canKickBombs || pushX !in board.indices || pushY !in board.indices ||
                board[pushY][pushX] == Tile.STEEL || board[pushY][pushX] == Tile.BRICK ||
                bombs.any { it !== blockingBomb && it.x == pushX && it.y == pushY } ||
                (selectedMode != GameMode.SOLO && players.any { it !== player && it.hp > 0 && it.x == pushX && it.y == pushY })
            ) return
            blockingBomb.x = pushX
            blockingBomb.y = pushY
        }
        if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer && board[ny][nx] == Tile.BRICK) {
            board[ny][nx] = Tile.FLOOR
            sendToWatch("BREAK:$nx:$ny")
        } else if (board[ny][nx] == Tile.BRICK) return
        player.walkFromX = player.x
        player.walkFromY = player.y
        player.x = nx
        player.y = ny
        player.lastMoveAt = now
        player.lastWalkAt = SystemClock.uptimeMillis()
        if (Debuff.POISON in player.activeDebuffs) {
            player.hp = (player.hp - 5).coerceAtLeast(0)
            if (player.hp == 0) {
                finishMatch(if (selectedMode == GameMode.SOLO) null else 1 - activePlayer, "Poison defeated Player ${activePlayer + 1}.")
                return
            }
        }
        if (board[ny][nx] == Tile.TELEPORT) {
            val teleportX = (board.size - 1 - nx).coerceIn(1, board.lastIndex - 1)
            val teleportY = (board.size - 1 - ny).coerceIn(1, board.lastIndex - 1)
            if (bombs.none { it.x == teleportX && it.y == teleportY } &&
                players.none { it !== player && it.x == teleportX && it.y == teleportY }
            ) {
                player.x = teleportX
                player.y = teleportY
                player.walkFromX = teleportX
                player.walkFromY = teleportY
            }
        }
        droppedItems.remove(nx to ny)?.let { collectPowerUp(player, it) }
        if (selectedMode == GameMode.RACE && board[ny][nx] == Tile.GOAL) {
            raceWon = true
            finishMatch(activePlayer, "Reached the center goal.")
        }
        if (selectedMode == GameMode.SOLO) updateMonsters()
        val positionCommand = if (activePlayer == 0) "HOST_POS" else "WATCH_POS"
        if (!handlingRemoteCommand) sendToWatch("$positionCommand:${player.x}:${player.y}")
    }

    private fun placeBomb() {
        if (screen != Screen.GAME) return
        val player = players[activePlayer]
        expireDebuffs(player, SystemClock.uptimeMillis())
        if (player.bombLocked) {
            lastPowerUpMessage = "BOMB LOCKED"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1400L
            invalidate()
            return
        }
        if (bombs.any { it.x == player.x && it.y == player.y }) return
        if (bombs.count { it.owner == activePlayer } >= player.bombCapacity) {
            lastPowerUpMessage = "BOMB LIMIT  ·  ${player.bombCapacity}"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1400L
            invalidate()
            return
        }
        val bomb = Bomb(player.x, player.y, activePlayer, SystemClock.uptimeMillis())
        bombs.add(bomb)
        if (!handlingRemoteCommand) sendToWatch(if (activePlayer == 0) "HOST_BOMB" else "WATCH_BOMB")
        postDelayed({ if (bombs.contains(bomb)) detonate(bomb) }, BOMB_FUSE_MS)
        invalidate()
    }

    private fun collectPowerUp(player: Player, code: String) {
        val debuff = Debuff.fromCode(code)
        if (debuff != null) {
            applyDebuff(player, debuff)
            lastPowerUpMessage = if (showFullPowerUpNames) "DEBUFF · ${debuff.displayName}" else "DEBUFF · ${debuff.code}"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
            return
        }
        val powerUp = PowerUp.fromCode(code) ?: return
        player.collectedPowerUps.add(code)
        when (powerUp) {
            PowerUp.FIRE -> player.blastRange = (player.blastRange + 1).coerceAtMost(8)
            PowerUp.BOMB -> player.bombCapacity = (player.bombCapacity + 1).coerceAtMost(5)
            PowerUp.DASH -> player.dashLevel = (player.dashLevel + 1).coerceAtMost(4)
            PowerUp.SHIELD -> player.shieldCharges = (player.shieldCharges + 1).coerceAtMost(3)
            PowerUp.KICK -> player.canKickBombs = true
            PowerUp.BLINK -> {
                val destination = randomSafeFloor()
                player.x = destination.first
                player.y = destination.second
            }
            PowerUp.VAMPIRE -> player.vampireFlame = true
            PowerUp.CLUSTER -> player.clusterBombs = true
            PowerUp.PIERCE -> player.piercingFlame = true
            PowerUp.HEART -> {
                val maxHp = if (selectedMode == GameMode.JUGGERNAUT && players.indexOf(player) == juggernautPlayer) {
                    2250
                } else {
                    player.spec.maxHp
                }
                player.hp = (player.hp + 50).coerceAtMost(maxHp)
            }
        }
        lastPowerUpMessage = if (showFullPowerUpNames) {
            "POWER UP  ·  ${powerUp.displayName}"
        } else {
            "POWER UP  ·  ${powerUp.code}"
        }
        powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
    }

    private fun applyDebuff(player: Player, debuff: Debuff) {
        player.collectedDebuffs.add(debuff.code)
        val now = SystemClock.uptimeMillis()
        expireDebuffs(player, now)
        val alreadyActive = debuff in player.activeDebuffs
        if (debuff.durationMillis > 0L) {
            player.activeDebuffs[debuff] = now + debuff.durationMillis
        }
        when (debuff) {
            Debuff.WEAK_FLAME -> if (!alreadyActive && player.blastRange > 1) {
                player.blastRange--
                player.reversibleDebuffs.add(debuff)
            }
            Debuff.BOMB_LOCK -> player.bombLocked = true
            Debuff.FRAGILE -> player.vulnerableHits = 2
            Debuff.SHIELD_BREAK -> player.shieldCharges = 0
            Debuff.DASH_DRAIN -> if (!alreadyActive && player.dashLevel > 0) {
                player.dashLevel--
                player.reversibleDebuffs.add(debuff)
            }
            Debuff.CURSE -> player.hp = (player.hp - 25).coerceAtLeast(0)
            Debuff.SLOW, Debuff.REVERSE, Debuff.STUN, Debuff.POISON -> Unit
        }
        if (player.hp == 0) {
            finishMatch(if (selectedMode == GameMode.SOLO) null else 1 - activePlayer, "A curse defeated Player ${activePlayer + 1}.")
        }
    }

    private fun expireDebuffs(player: Player, now: Long) {
        val expired = player.activeDebuffs.filterValues { it <= now }.keys
        expired.forEach { debuff ->
            player.activeDebuffs.remove(debuff)
            when (debuff) {
                Debuff.BOMB_LOCK -> player.bombLocked = false
                Debuff.FRAGILE -> player.vulnerableHits = 0
                else -> Unit
            }
            if (player.reversibleDebuffs.remove(debuff)) {
                when (debuff) {
                    Debuff.WEAK_FLAME -> player.blastRange = (player.blastRange + 1).coerceAtMost(8)
                    Debuff.DASH_DRAIN -> player.dashLevel = (player.dashLevel + 1).coerceAtMost(4)
                    else -> Unit
                }
            }
        }
    }

    private fun activeDebuffSummary(player: Player, now: Long): String {
        val effects = player.activeDebuffs.entries
            .sortedBy { it.value }
            .take(4)
            .joinToString(" ") { (debuff, expiresAt) ->
                val seconds = ((expiresAt - now).coerceAtLeast(0L) + 999L) / 1000L
                "${debuff.code} ${seconds}s"
            }
        return if (effects.isEmpty()) "Debuffs: none" else "Debuffs: $effects"
    }

    private fun randomSafeFloor(): Pair<Int, Int> {
        val safeFloors = mutableListOf<Pair<Int, Int>>()
        for (y in 1 until board.lastIndex) for (x in 1 until board.lastIndex) {
            if (board[y][x] == Tile.FLOOR && bombs.none { it.x == x && it.y == y } &&
                players.none { it.x == x && it.y == y }
            ) safeFloors.add(x to y)
        }
        return safeFloors.randomOrNull() ?: (players[activePlayer].x to players[activePlayer].y)
    }

    private fun detonate(bomb: Bomb, fromChain: Boolean = false) {
        if (screen != Screen.GAME || !bombs.contains(bomb)) return
        val now = SystemClock.uptimeMillis()
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val owner = players[bomb.owner]
        val range = if (selectedMode == GameMode.JUGGERNAUT && bomb.owner == juggernautPlayer) 4 else owner.blastRange
        val affectedCells = mutableSetOf(bomb.x to bomb.y)
        val newlyRevealed = mutableSetOf<Pair<Int, Int>>()
        blastEffects.add(BlastEffect(bomb.x, bomb.y, range, now))
        for ((dx, dy) in directions) {
            for (step in 1..range) {
                val x = bomb.x + dx * step
                val y = bomb.y + dy * step
                if (x !in board.indices || y !in board.indices || board[y][x] == Tile.STEEL) break
                affectedCells.add(x to y)
                if (board[y][x] == Tile.BRICK) {
                    board[y][x] = Tile.FLOOR
                    sendToWatch("BREAK:$x:$y")
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.let {
                        it.hidden = false
                        newlyRevealed.add(x to y)
                    }
                    val dropChance = when (selectedMode) {
                        GameMode.CARNAGE -> 85
                        GameMode.CASUAL, GameMode.JUGGERNAUT, GameMode.SOLO -> 45
                        GameMode.HARDCORE -> 0
                        GameMode.RACE, GameMode.SPLEEF -> 0
                    }
                    if (Random.nextInt(100) < dropChance) {
                        val code = if (Random.nextInt(100) < 25) {
                            Debuff.entries.random().code
                        } else {
                            powerUps.random().code
                        }
                        droppedItems[x to y] = code
                        sendToWatch("DROP:$x:$y:$code")
                    }
                    if (!owner.piercingFlame) break
                }
            }
        }
        if (owner.clusterBombs) {
            for ((dx, dy) in directions) {
                val x = bomb.x + dx * 2
                val y = bomb.y + dy * 2
                if (x in board.indices && y in board.indices && board[y][x] != Tile.STEEL) {
                    blastEffects.add(BlastEffect(x, y, 1, now))
                    affectedCells.add(x to y)
                    for ((extraDx, extraDy) in directions) {
                        val extraX = x + extraDx
                        val extraY = y + extraDy
                        if (extraX in board.indices && extraY in board.indices &&
                            board[extraY][extraX] != Tile.STEEL
                        ) affectedCells.add(extraX to extraY)
                    }
                }
            }
        }
        bombs.filter { it != bomb && (it.x to it.y) in affectedCells }.toList().forEach { chained ->
            if (!fromChain) sendToWatch("CHAIN:${chained.x}:${chained.y}")
            post { detonate(chained, fromChain = true) }
        }
        if (selectedMode == GameMode.SPLEEF) {
            affectedCells.forEach { (x, y) -> if (x in board.indices && y in board.indices) board[y][x] = Tile.HOLE }
        }
        if (selectedMode == GameMode.SOLO) {
            monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
        }
        val hitPlayers = players.mapIndexed { index, player ->
            index to (player.hp > 0 && (selectedMode != GameMode.SOLO || index == 0) &&
                (player.x to player.y) in affectedCells)
        }.filter { it.second }.map { it.first }
        if (selectedMode == GameMode.SPLEEF && hitPlayers.isNotEmpty()) {
            val winner = if (hitPlayers.size == 2) null else 1 - hitPlayers.single()
            finishMatch(winner, "A blast broke the floor under a player.")
        } else {
            hitPlayers.forEach { index ->
                val player = players[index]
                if (player.shieldCharges > 0) {
                    player.shieldCharges--
                } else if (selectedMode == GameMode.RACE) {
                    val last = board.size - 2
                    player.x = if (index == 0) 1 else last
                    player.y = if (index == 0) 1 else last
                } else {
                    val damage = if (Debuff.FRAGILE in player.activeDebuffs && player.vulnerableHits > 0) 100 else 50
                    if (player.vulnerableHits > 0) {
                        player.vulnerableHits--
                        if (player.vulnerableHits == 0) player.activeDebuffs.remove(Debuff.FRAGILE)
                    }
                    player.hp = (player.hp - damage).coerceAtLeast(0)
                }
                if (index != bomb.owner && owner.vampireFlame) {
                    val maxHp = if (selectedMode == GameMode.JUGGERNAUT && bomb.owner == juggernautPlayer) 2250 else owner.spec.maxHp
                    owner.hp = (owner.hp + 20).coerceAtMost(maxHp)
                }
            }
            if (selectedMode == GameMode.SOLO && players[0].hp <= 0) {
                finishMatch(null, "Player 1 was eliminated.")
            } else if (selectedMode != GameMode.SOLO) {
                val defeated = players.indices.filter { players[it].hp <= 0 }
                if (defeated.isNotEmpty()) {
                    val winner = if (defeated.size == 2) null else 1 - defeated.single()
                    finishMatch(winner, "A player was eliminated.")
                }
            }
        }
        bombs.remove(bomb)
        if (selectedMode == GameMode.SOLO && screen == Screen.GAME) updateMonsters(affectedCells - newlyRevealed)
        if (selectedMode == GameMode.SOLO && monsters.isEmpty() && screen == Screen.GAME) {
            finishMatch(0, "All monsters defeated.")
        }
        invalidate()
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
        canvas.drawColor(Color.rgb(5, 9, 17))
        paint.color = Color.argb(22, 23, 205, 190)
        canvas.drawCircle(width * .92f, height * .14f, min(width, height) * .38f, paint)
        paint.color = Color.argb(19, 70, 101, 238)
        canvas.drawCircle(width * .02f, height * .82f, min(width, height) * .46f, paint)
        paint.color = Color.argb(15, 207, 123, 80)
        canvas.drawCircle(width * .95f, height * .72f, min(width, height) * .22f, paint)

        val unit = min(width, height).toFloat()
        paint.color = Color.argb(24, 152, 206, 220)
        paint.strokeWidth = maxOf(1f, unit * .001f)
        canvas.drawLine(width * .05f, height * .155f, width * .95f, height * .155f, paint)
        canvas.drawLine(width * .05f, height * .895f, width * .95f, height * .895f, paint)
        paint.color = Color.argb(35, 75, 221, 205)
        canvas.drawLine(width * .075f, height * .158f, width * .24f, height * .158f, paint)
        canvas.drawLine(width * .76f, height * .892f, width * .925f, height * .892f, paint)

        for (index in 0 until 36) {
            val x = ((index * 83 + 31) % 997) / 997f * width
            val y = ((index * 137 + 59) % 991) / 991f * height
            val alpha = 18 + index % 4 * 7
            paint.color = Color.argb(alpha, 172, 210, 225)
            canvas.drawCircle(x, y, unit * if (index % 7 == 0) .0022f else .0012f, paint)
        }
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
                paint.color = Color.argb(24, 130, 205, 204)
                canvas.drawRoundRect(left + cell * .1f, top + cell * .1f,
                    right - cell * .1f, bottom - cell * .1f, cell * .045f, cell * .045f, paint)
                paint.color = Color.argb(58, 124, 191, 194)
                canvas.drawCircle(left + cell * .18f, top + cell * .18f, cell * .035f, paint)
                canvas.drawCircle(right - cell * .18f, bottom - cell * .18f, cell * .035f, paint)
            }
            Tile.STEEL -> {
                paint.color = Color.rgb(36, 54, 70)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                paint.color = Color.rgb(92, 123, 144)
                canvas.drawRoundRect(left + cell * .08f, top + cell * .07f, right - cell * .08f,
                    bottom - cell * .08f, cell * .07f, cell * .07f, paint)
                paint.color = Color.rgb(53, 74, 91)
                canvas.drawRoundRect(left + cell * .15f, top + cell * .15f, right - cell * .15f,
                    bottom - cell * .15f, cell * .045f, cell * .045f, paint)
                paint.color = Color.argb(100, 191, 224, 235)
                canvas.drawLine(left + cell * .2f, top + cell * .1f, right - cell * .2f,
                    top + cell * .1f, paint.apply { strokeWidth = maxOf(1f, cell * .025f) })
                paint.color = Color.rgb(174, 207, 219)
                for ((boltX, boltY) in listOf(.2f to .2f, .8f to .2f, .2f to .8f, .8f to .8f)) {
                    canvas.drawCircle(left + cell * boltX, top + cell * boltY, cell * .035f, paint)
                }
            }
            Tile.BRICK -> {
                paint.color = Color.rgb(91, 40, 39)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(173, 71, 54)
                canvas.drawRoundRect(left + cell * .06f, top + cell * .06f, right - cell * .06f,
                    bottom - cell * .08f, cell * .055f, cell * .055f, paint)
                paint.color = Color.rgb(107, 48, 43)
                canvas.drawRect(left + cell * .06f, top + cell * .48f, right - cell * .06f,
                    top + cell * .53f, paint)
                canvas.drawRect(left + cell * .47f, top + cell * .06f, left + cell * .52f,
                    top + cell * .48f, paint)
                canvas.drawRect(left + cell * .29f, top + cell * .53f, left + cell * .34f,
                    bottom - cell * .08f, paint)
                paint.color = Color.argb(90, 255, 177, 127)
                canvas.drawLine(left + cell * .12f, top + cell * .12f, right - cell * .12f,
                    top + cell * .12f, paint.apply { strokeWidth = maxOf(1f, cell * .025f) })
            }
            Tile.TELEPORT -> {
                paint.color = Color.rgb(50, 27, 85)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, cell * .055f)
                paint.color = Color.argb(120, 213, 147, 255)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .38f, paint)
                paint.style = Paint.Style.FILL
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
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, cell * .05f)
                paint.color = Color.argb(150, 255, 225, 141)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .39f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(255, 195, 61)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .36f, paint)
                paint.color = Color.rgb(255, 232, 150)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .23f, paint)
                paint.color = Color.rgb(227, 143, 37)
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .1f, paint)
            }
            Tile.HOLE -> {
                paint.color = Color.rgb(3, 5, 9)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(63, 76, 82)
                paint.strokeWidth = maxOf(1f, cell * .025f)
                canvas.drawLine(left + cell * .18f, top + cell * .2f,
                    right - cell * .2f, bottom - cell * .18f, paint)
                canvas.drawLine(right - cell * .18f, top + cell * .2f,
                    left + cell * .2f, bottom - cell * .18f, paint)
            }
        }
    }

    private fun drawBomb(canvas: Canvas, cx: Float, cy: Float, cell: Float, plantedAt: Long) {
        val pulse = 1f + .07f * kotlin.math.sin(SystemClock.uptimeMillis() / 95f)
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawOval(cx - cell * .34f, cy + cell * .17f, cx + cell * .34f, cy + cell * .34f, paint)
        paint.color = Color.rgb(7, 11, 18)
        canvas.drawCircle(cx, cy, cell * .32f * pulse, paint)
        paint.color = Color.rgb(37, 49, 65)
        canvas.drawCircle(cx, cy - cell * .025f, cell * .27f * pulse, paint)
        paint.color = Color.rgb(91, 111, 130)
        canvas.drawCircle(cx - cell * .1f, cy - cell * .11f, cell * .075f * pulse, paint)
        paint.color = Color.rgb(201, 116, 51)
        canvas.drawLine(cx + cell * .12f, cy - cell * .24f, cx + cell * .24f, cy - cell * .38f, paint.apply {
            strokeWidth = cell * .055f
            strokeCap = Paint.Cap.ROUND
        })
        val fuseColor = if (SystemClock.uptimeMillis() % 360L < 180L) {
            Color.rgb(255, 91, 61)
        } else {
            Color.rgb(255, 218, 91)
        }
        paint.color = Color.argb(90, 255, 148, 57)
        canvas.drawCircle(cx + cell * .25f, cy - cell * .4f, cell * .12f, paint)
        paint.color = fuseColor
        canvas.drawCircle(cx + cell * .25f, cy - cell * .4f, cell * .06f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        val secondsLeft = ((BOMB_FUSE_MS - (SystemClock.uptimeMillis() - plantedAt)).coerceAtLeast(0L) + 999L) / 1000L
        text(canvas, "$secondsLeft", cx, cy + cell * .48f, cell * .18f,
            Color.WHITE, true, Paint.Align.CENTER)
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
                paint.color = Color.argb(alpha * 3 / 4, 255, 124, 49)
                for (spark in 0 until 6) {
                    val angle = spark * (Math.PI.toFloat() / 3f) + progress * 2f
                    val distance = cell * (.42f + progress * .38f)
                    canvas.drawCircle(cx + kotlin.math.cos(angle) * distance,
                        cy + kotlin.math.sin(angle) * distance, cell * .035f * (1f - progress), paint)
                }
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
        label: String,
        shielded: Boolean = false,
        walking: Boolean = false,
        healthRatio: Float? = null
    ) {
        val radius = size * .5f
        val walk = if (walking) kotlin.math.sin(SystemClock.uptimeMillis() / 65f) else 0f
        if (shielded) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, radius * .08f)
            paint.color = Color.argb(190, 96, 191, 255)
            canvas.drawCircle(cx, cy, radius * .94f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawOval(cx - radius * .62f, cy + radius * .78f, cx + radius * .62f,
            cy + radius * .98f, paint)

        paint.color = Color.rgb(
            (Color.red(color) * .68f).toInt(),
            (Color.green(color) * .68f).toInt(),
            (Color.blue(color) * .68f).toInt()
        )
        paint.strokeWidth = maxOf(1f, radius * .13f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - radius * .16f, cy + radius * .37f,
            cx - radius * (.18f + walk * .18f), cy + radius * .86f, paint)
        canvas.drawLine(cx + radius * .16f, cy + radius * .37f,
            cx + radius * (.18f - walk * .18f), cy + radius * .86f, paint)
        canvas.drawLine(cx - radius * .3f, cy - radius * .1f,
            cx - radius * (.48f - walk * .2f), cy + radius * (.28f + walk * .08f), paint)
        canvas.drawLine(cx + radius * .3f, cy - radius * .1f,
            cx + radius * (.48f + walk * .2f), cy + radius * (.28f - walk * .08f), paint)
        paint.strokeCap = Paint.Cap.BUTT

        bounds.set(cx - radius * .34f, cy - radius * .24f, cx + radius * .34f, cy + radius * .48f)
        paint.color = color
        canvas.drawRoundRect(bounds, radius * .16f, radius * .16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * .045f)
        paint.color = Color.argb(190, 9, 20, 31)
        canvas.drawRoundRect(bounds, radius * .16f, radius * .16f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawRoundRect(bounds.left + radius * .12f, bounds.top + radius * .1f,
            bounds.right - radius * .12f, bounds.top + radius * .22f, radius * .08f, radius * .08f, paint)
        paint.color = color
        canvas.drawCircle(cx, cy - radius * .48f, radius * .24f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * .04f)
        paint.color = Color.argb(190, 9, 20, 31)
        canvas.drawCircle(cx, cy - radius * .48f, radius * .24f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx - radius * .09f, cy - radius * .5f, radius * .06f, paint)
        canvas.drawCircle(cx + radius * .09f, cy - radius * .5f, radius * .06f, paint)
        paint.color = Color.rgb(13, 23, 33)
        canvas.drawCircle(cx - radius * .08f, cy - radius * .5f, radius * .025f, paint)
        canvas.drawCircle(cx + radius * .1f, cy - radius * .5f, radius * .025f, paint)
        drawAccessory(canvas, cx, cy - radius * .48f, radius * .3f, accessory)
        text(canvas, label, cx, cy + radius * .34f, radius * .24f, Color.WHITE, true, Paint.Align.CENTER)
        healthRatio?.let { ratio ->
            val barWidth = radius * 1.6f
            val barHeight = maxOf(1f, radius * .14f)
            val barTop = cy - radius * 1.15f
            bounds.set(cx - barWidth / 2f, barTop, cx + barWidth / 2f, barTop + barHeight)
            paint.color = Color.argb(210, 8, 14, 22)
            canvas.drawRoundRect(bounds, barHeight / 2f, barHeight / 2f, paint)
            val fillWidth = barWidth * ratio.coerceIn(0f, 1f)
            if (fillWidth > 0f) {
                bounds.set(cx - barWidth / 2f, barTop, cx - barWidth / 2f + fillWidth, barTop + barHeight)
                paint.color = when {
                    ratio > .6f -> Color.rgb(85, 225, 145)
                    ratio > .3f -> Color.rgb(255, 190, 75)
                    else -> Color.rgb(255, 89, 92)
                }
                canvas.drawRoundRect(bounds, barHeight / 2f, barHeight / 2f, paint)
            }
        }
    }

    private fun lobbyCardTop() = height * .16f
    private fun lobbyCardHeight() = min(height * .052f, width * .11f)
    private fun lobbyCardGap() = min(height * .006f, width * .015f)
    private fun lobbyActionsTop() = height * .58f

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
        paint.strokeWidth = maxOf(1f, unit * .002f)
        paint.color = Color.argb(175, 116, 217, 220)
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
