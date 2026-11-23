package com.wearbomber.watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private enum class WatchTile { FLOOR, STEEL, BRICK, TELEPORT, GOAL, HOLE }
private enum class WatchScreen { GAME, SETTINGS, RESULTS }

private enum class WatchDebuff(val code: String, val label: String, val color: Int, val symbol: String) {
    SLOW("SL", "Sluggish", Color.rgb(174, 166, 91), "S"),
    REVERSE("RV", "Dizzy", Color.rgb(207, 109, 145), "R"),
    WEAK_FLAME("WF", "Weak Flame", Color.rgb(197, 112, 82), "W"),
    BOMB_LOCK("BL", "Bomb Lock", Color.rgb(144, 124, 174), "L"),
    STUN("ST", "Stun", Color.rgb(224, 172, 75), "!"),
    FRAGILE("FR", "Fragile", Color.rgb(224, 99, 99), "F"),
    POISON("PS", "Poison", Color.rgb(112, 194, 106), "P"),
    SHIELD_BREAK("SB", "Shield Break", Color.rgb(92, 152, 199), "X"),
    DASH_DRAIN("DD", "Dash Drain", Color.rgb(105, 179, 179), "D"),
    CURSE("CR", "Curse", Color.rgb(156, 110, 183), "C");

    companion object {
        fun fromCode(code: String): WatchDebuff? = entries.firstOrNull { it.code == code }
    }
}

private enum class WatchPowerUp(val code: String, val displayName: String, val color: Int, val symbol: String) {
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
        fun fromCode(code: String): WatchPowerUp? = entries.firstOrNull { it.code == code }
    }
}

private data class WatchBomb(val x: Int, val y: Int, val plantedAt: Long)
private data class WatchExplosion(
    val x: Int,
    val y: Int,
    val startedAt: Long,
    val range: Int,
    val piercing: Boolean
)
private data class WatchMonster(var x: Int, var y: Int, var hidden: Boolean = true)

class WatchGameView(
    context: Context,
    private val sendCommand: (String) -> Unit,
    private val connect: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cellRect = RectF()
    private val modes = listOf("CARNAGE", "CASUAL", "HARDCORE", "JUGGERNAUT", "RACE", "SPLEEF", "SOLO")
    private val mapSizes = listOf(9, 9, 9, 11, 11, 11, 15, 15)
    private var screen = WatchScreen.GAME
    private var mode = 0
    private var selectedMap = 7
    private var board = createBoard(mapSizes[selectedMap])
    private val monsters = mutableListOf<WatchMonster>()
    private var playerX = 1
    private var playerY = 1
    private var opponentX = 13
    private var opponentY = 13
    private var connectionStatus = "Pair phone, then tap LINK"
    private var connectedToPhone = false
    private var highContrast = false
    private var effectsEnabled = true
    private var hapticsEnabled = true
    private var showFullPowerUpNames = false
    private var bombCapacity = 1
    private var blastRange = 2
    private var shieldCharges = 0
    private var dashLevel = 0
    private var canKickBombs = false
    private var vampireFlame = false
    private var clusterBombs = false
    private var piercingFlame = false
    private var playerHealth = 150
    private var opponentHealth = 150
    private var lastPowerUpMessage = ""
    private var powerUpMessageUntil = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastMoveAt = 0L
    private var slowMoves = 0
    private var reverseMoves = 0
    private var stunMoves = 0
    private var poisonMoves = 0
    private var vulnerableHits = 0
    private var bombLocked = false
    private val collectedPowerUps = linkedSetOf<String>()
    private val collectedDebuffs = linkedSetOf<String>()
    private var resultTitle = ""
    private var resultMessage = ""
    private val bombs = mutableListOf<WatchBomb>()
    private val explosions = mutableListOf<WatchExplosion>()
    private val droppedItems = mutableMapOf<Pair<Int, Int>, String>()
    private val powerUps = WatchPowerUp.entries.toList()

    fun setConnectionStatus(status: String) {
        connectionStatus = status
        connectedToPhone = status == "Connected"
        invalidate()
    }

    fun handleRemoteCommand(command: String) {
        when {
            command.startsWith("MODE:") -> {
                modes.indexOf(command.substringAfter(':')).takeIf { it >= 0 }?.let { mode = it }
                invalidate()
            }
            command.startsWith("MAP:") -> {
                selectedMap = (command.substringAfter(':').toIntOrNull() ?: return).coerceIn(mapSizes.indices)
                invalidate()
            }
            command == "START" || command.startsWith("START:") -> {
                val parts = command.split(':')
                parts.getOrNull(1)?.let { modeName -> modes.indexOf(modeName).takeIf { it >= 0 }?.let { mode = it } }
                parts.getOrNull(2)?.toIntOrNull()?.let { selectedMap = it.coerceIn(mapSizes.indices) }
                startRound()
            }
            command.startsWith("RESULT:") -> {
                val parts = command.removePrefix("RESULT:").split("|", limit = 2)
                val winner = parts.firstOrNull()?.toIntOrNull() ?: -1
                resultTitle = when {
                    modes[mode] == "SOLO" && winner == 0 -> "YOU WIN!"
                    modes[mode] == "SOLO" -> "GAME OVER"
                    winner == 1 -> "YOU WIN!"
                    winner == 0 -> "YOU LOSE"
                    else -> "DRAW"
                }
                resultMessage = parts.getOrNull(1).orEmpty().take(40)
                bombs.clear()
                screen = WatchScreen.RESULTS
                invalidate()
            }
            command.startsWith("HOST_POS:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                if (modes[mode] == "SOLO") {
                    playerX = x.coerceIn(board.indices)
                    playerY = y.coerceIn(board.indices)
                } else {
                    opponentX = x.coerceIn(board.indices)
                    opponentY = y.coerceIn(board.indices)
                }
                invalidate()
            }
            command.startsWith("WATCH_POS:") -> {
                val values = command.substringAfter(':').split(':')
                playerX = (values.getOrNull(0)?.toIntOrNull() ?: return).coerceIn(board.indices)
                playerY = (values.getOrNull(1)?.toIntOrNull() ?: return).coerceIn(board.indices)
                invalidate()
            }
            command.startsWith("DROP:") -> {
                val values = command.split(':')
                val x = values.getOrNull(1)?.toIntOrNull() ?: return
                val y = values.getOrNull(2)?.toIntOrNull() ?: return
                val code = values.getOrNull(3) ?: return
                if (x in board.indices && y in board.indices &&
                    (WatchPowerUp.fromCode(code) != null || WatchDebuff.fromCode(code) != null)
                ) droppedItems[x to y] = code
                invalidate()
            }
            command.startsWith("BREAK:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                if (x in board.indices && y in board.indices && board[y][x] == WatchTile.BRICK) {
                    board[y][x] = WatchTile.FLOOR
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.hidden = false
                }
                invalidate()
            }
            command == "HOST_BOMB" -> placeRemoteBomb(
                if (modes[mode] == "SOLO") playerX to playerY else opponentX to opponentY, true
            )
            command == "WATCH_BOMB" -> placeRemoteBomb(playerX to playerY, true)
            command == "BOMB" -> placeRemoteBomb(
                if (modes[mode] == "SOLO") playerX to playerY else opponentX to opponentY, true
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(4, 7, 13))
        if (screen == WatchScreen.SETTINGS) {
            drawSettings(canvas)
            return
        }
        if (screen == WatchScreen.RESULTS) {
            drawResults(canvas)
            return
        }

        val diameter = min(width, height).toFloat()
        if (diameter <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val boardSize = diameter * .68f
        val cell = boardSize / 9f
        val left = cx - boardSize / 2f
        val top = cy - boardSize / 2f
        val cameraX = cameraLeft()
        val cameraY = cameraTop()

        text(canvas, "WEAR BOMBER", cx, cy - diameter * .432f, diameter * .032f, Color.WHITE, true)
        text(canvas, modes[mode], cx, cy - diameter * .365f, diameter * .026f, Color.rgb(118, 222, 255), true)
        drawBoard(canvas, left, top, cell, cameraX, cameraY)
        drawPowerUps(canvas, left, top, cell, cameraX, cameraY)
        drawBombsAndExplosions(canvas, left, top, cell, cameraX, cameraY)
        drawPlayer(
            canvas,
            left + (playerX - cameraX + .5f) * cell,
            top + (playerY - cameraY + .5f) * cell,
            cell,
            Color.rgb(59, 174, 255),
            "1",
            shieldCharges > 0
        )
        if (modes[mode] != "SOLO") {
            drawPlayer(
                canvas,
                left + (opponentX - cameraX + .5f) * cell,
                top + (opponentY - cameraY + .5f) * cell,
                cell,
                Color.rgb(255, 91, 111),
                "2"
            )
        }
        monsters.filterNot { it.hidden }.forEach { monster ->
            drawPlayer(canvas, left + (monster.x - cameraX + .5f) * cell,
                top + (monster.y - cameraY + .5f) * cell, cell, Color.rgb(148, 202, 82), "M")
        }

        val powerSummary = collectedPowerUps.toList().takeLast(3).joinToString(" ")
        val debuffSummary = collectedDebuffs.toList().takeLast(3).joinToString(" ")
        text(canvas, "P:${powerSummary.ifEmpty { "-" }} D:${debuffSummary.ifEmpty { "-" }}",
            cx, cy + diameter * .325f, diameter * .016f, Color.rgb(178, 204, 218))
        text(canvas, connectionStatus.take(27), cx, cy + diameter * .377f,
            diameter * .022f, Color.rgb(195, 207, 220))
        if (SystemClock.uptimeMillis() < powerUpMessageUntil) {
            text(canvas, lastPowerUpMessage.take(25), cx, cy + diameter * .355f,
                diameter * .021f, Color.rgb(255, 221, 130), true)
        }
        drawNavigation(canvas, cx, cy, diameter)

        if (bombs.isNotEmpty() || explosions.isNotEmpty() ||
            SystemClock.uptimeMillis() < powerUpMessageUntil
        ) postInvalidateOnAnimation()
    }

    private fun drawBoard(canvas: Canvas, left: Float, top: Float, cell: Float, cameraX: Int, cameraY: Int) {
        rect.set(left - cell * .09f, top - cell * .09f, left + cell * 9.09f, top + cell * 9.09f)
        paint.color = Color.rgb(15, 23, 35)
        canvas.drawRoundRect(rect, cell * .22f, cell * .22f, paint)

        for (visibleY in 0 until 9) {
            for (visibleX in 0 until 9) {
                val x = visibleX + cameraX
                val y = visibleY + cameraY
                val tile = if (y in board.indices && x in board.indices) board[y][x] else WatchTile.STEEL
                val x0 = left + visibleX * cell
                val y0 = top + visibleY * cell
                cellRect.set(x0 + cell * .035f, y0 + cell * .035f,
                    x0 + cell * .965f, y0 + cell * .965f)
                drawTile(canvas, tile, x0, y0, cell, x, y)
            }
        }
    }

    private fun drawPowerUps(canvas: Canvas, left: Float, top: Float, cell: Float, cameraX: Int, cameraY: Int) {
        droppedItems.forEach { (position, code) ->
            val visibleX = position.first - cameraX
            val visibleY = position.second - cameraY
            if (visibleX in 0..8 && visibleY in 0..8) {
                val cx = left + (visibleX + .5f) * cell
                val cy = top + (visibleY + .5f) * cell
                val powerUp = WatchPowerUp.fromCode(code)
                if (powerUp != null) {
                    drawPowerUp(canvas, cx, cy, cell, powerUp)
                } else {
                    val debuff = WatchDebuff.fromCode(code) ?: return@forEach
                    paint.color = debuff.color
                    canvas.drawCircle(cx, cy, cell * .3f, paint)
                    text(canvas, debuff.symbol, cx, cy + cell * .1f, cell * .25f,
                        Color.WHITE, true)
                    text(canvas, debuff.code, cx, cy + cell * .41f, cell * .15f,
                        Color.WHITE, true)
                }
            }
        }
    }

    private fun drawPowerUp(canvas: Canvas, cx: Float, cy: Float, cell: Float, powerUp: WatchPowerUp) {
        paint.color = Color.argb(65, Color.red(powerUp.color), Color.green(powerUp.color), Color.blue(powerUp.color))
        canvas.drawCircle(cx, cy, cell * .46f, paint)
        paint.color = Color.rgb(10, 17, 26)
        canvas.drawCircle(cx, cy, cell * .36f, paint)
        paint.color = powerUp.color
        canvas.drawCircle(cx, cy, cell * .3f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, cell * .035f)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, cell * .3f, paint)
        paint.style = Paint.Style.FILL
        text(canvas, powerUp.symbol, cx, cy + cell * .1f, cell * .32f, Color.WHITE, true)
        text(canvas, powerUp.code, cx, cy + cell * .41f, cell * .15f, Color.WHITE, true)
    }

    private fun drawTile(canvas: Canvas, tile: WatchTile, x: Float, y: Float, cell: Float, gridX: Int, gridY: Int) {
        when (tile) {
            WatchTile.FLOOR -> {
                paint.color = if (highContrast) {
                    if ((gridX + gridY) % 2 == 0) Color.rgb(34, 52, 65) else Color.rgb(46, 68, 80)
                } else {
                    if ((gridX + gridY) % 2 == 0) Color.rgb(22, 38, 48) else Color.rgb(27, 45, 54)
                }
                canvas.drawRoundRect(cellRect, cell * .09f, cell * .09f, paint)
                paint.color = Color.argb(35, 155, 206, 219)
                canvas.drawCircle(x + cell * .18f, y + cell * .18f, cell * .035f, paint)
            }
            WatchTile.STEEL -> {
                paint.color = Color.rgb(72, 91, 108)
                canvas.drawRoundRect(cellRect, cell * .12f, cell * .12f, paint)
                rect.set(x + cell * .12f, y + cell * .1f, x + cell * .88f, y + cell * .83f)
                paint.color = Color.rgb(111, 135, 151)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                rect.set(x + cell * .2f, y + cell * .2f, x + cell * .8f, y + cell * .76f)
                paint.color = Color.rgb(77, 98, 116)
                canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
                paint.color = Color.rgb(153, 182, 192)
                canvas.drawCircle(x + cell * .25f, y + cell * .25f, cell * .045f, paint)
                canvas.drawCircle(x + cell * .75f, y + cell * .72f, cell * .045f, paint)
            }
            WatchTile.BRICK -> {
                paint.color = Color.rgb(119, 59, 49)
                canvas.drawRoundRect(cellRect, cell * .1f, cell * .1f, paint)
                rect.set(x + cell * .08f, y + cell * .09f, x + cell * .92f, y + cell * .91f)
                paint.color = Color.rgb(193, 91, 64)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(129, 58, 48)
                canvas.drawRect(x + cell * .08f, y + cell * .48f, x + cell * .92f, y + cell * .55f, paint)
                canvas.drawRect(x + cell * .47f, y + cell * .1f, x + cell * .54f, y + cell * .48f, paint)
                canvas.drawRect(x + cell * .3f, y + cell * .55f, x + cell * .37f, y + cell * .91f, paint)
            }
            WatchTile.TELEPORT -> {
                paint.color = Color.rgb(50, 28, 91)
                canvas.drawRoundRect(cellRect, cell * .12f, cell * .12f, paint)
                paint.color = Color.rgb(181, 91, 255)
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * .34f, paint)
                paint.color = Color.rgb(66, 39, 130)
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * .21f, paint)
                paint.color = Color.rgb(225, 184, 255)
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * .09f, paint)
            }
            WatchTile.GOAL -> {
                paint.color = Color.rgb(94, 70, 26)
                canvas.drawRoundRect(cellRect, cell * .12f, cell * .12f, paint)
                paint.color = Color.rgb(255, 202, 71)
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * .35f, paint)
                paint.color = Color.rgb(255, 239, 157)
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * .22f, paint)
                paint.color = Color.rgb(226, 147, 36)
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * .09f, paint)
            }
            WatchTile.HOLE -> {
                paint.color = Color.rgb(3, 5, 9)
                canvas.drawRoundRect(cellRect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(63, 76, 82)
                paint.strokeWidth = maxOf(1f, cell * .025f)
                canvas.drawLine(x + cell * .18f, y + cell * .2f,
                    x + cell * .82f, y + cell * .8f, paint)
                canvas.drawLine(x + cell * .82f, y + cell * .2f,
                    x + cell * .18f, y + cell * .8f, paint)
            }
        }
    }

    private fun drawBombsAndExplosions(
        canvas: Canvas,
        left: Float,
        top: Float,
        cell: Float,
        cameraX: Int,
        cameraY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        var index = bombs.lastIndex
        while (index >= 0) {
            val bomb = bombs[index]
            val age = now - bomb.plantedAt
            val pulse = 1f + .07f * sin(age / 95f)
            val x = left + (bomb.x - cameraX + .5f) * cell
            val y = top + (bomb.y - cameraY + .5f) * cell
            paint.color = Color.rgb(7, 10, 16)
            canvas.drawCircle(x, y + cell * .035f, cell * .36f * pulse, paint)
            paint.color = Color.rgb(29, 34, 46)
            canvas.drawCircle(x, y, cell * .31f * pulse, paint)
            paint.color = Color.rgb(94, 105, 124)
            canvas.drawCircle(x - cell * .1f, y - cell * .1f, cell * .08f, paint)
            paint.color = if (age % 360L < 180L) Color.rgb(255, 92, 63) else Color.rgb(255, 203, 87)
            canvas.drawCircle(x + cell * .19f, y - cell * .25f, cell * .075f, paint)
            text(canvas, "${((FUSE_MS - age).coerceAtLeast(0L) + 999L) / 1000L}",
                x, y + cell * .47f, cell * .17f, Color.WHITE, true)
            index--
        }

        index = explosions.lastIndex
        while (index >= 0) {
            val explosion = explosions[index]
            val progress = (now - explosion.startedAt).toFloat() / EXPLOSION_MS
            if (progress >= 1f) {
                explosions.removeAt(index)
            } else {
                drawExplosion(canvas, explosion, progress, left, top, cell, cameraX, cameraY)
            }
            index--
        }
    }

    private fun drawExplosion(
        canvas: Canvas,
        explosion: WatchExplosion,
        progress: Float,
        left: Float,
        top: Float,
        cell: Float,
        cameraX: Int,
        cameraY: Int
    ) {
        if (!effectsEnabled) return
        val px = explosion.x - cameraX
        val py = explosion.y - cameraY
        if (px !in 0..8 || py !in 0..8) return
        val cx = left + (px + .5f) * cell
        val cy = top + (py + .5f) * cell
        val alpha = ((1f - progress) * 230f).toInt().coerceIn(0, 230)
        val glowWidth = cell * .7f * (1f - progress * .45f)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = glowWidth
        paint.color = Color.argb(alpha / 3, 255, 76, 24)
        drawBlastArms(canvas, cx, cy, cell, explosion, progress)
        paint.strokeWidth = glowWidth * .42f
        paint.color = Color.argb(alpha, 255, 169, 55)
        drawBlastArms(canvas, cx, cy, cell, explosion, progress)
        paint.style = Paint.Style.FILL

        paint.color = Color.argb(alpha, 255, 235, 155)
        canvas.drawCircle(cx, cy, cell * (.22f + .18f * (1f - progress)), paint)
        paint.color = Color.argb(alpha * 3 / 4, 255, 105, 44)
        for (spark in 0 until 8) {
            val angle = spark * (Math.PI.toFloat() / 4f) + progress * 2f
            val distance = cell * (.38f + progress * .45f)
            canvas.drawCircle(cx + cos(angle) * distance, cy + sin(angle) * distance,
                cell * .045f * (1f - progress), paint)
        }
    }

    private fun drawBlastArms(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        cell: Float,
        explosion: WatchExplosion,
        progress: Float
    ) {
        drawBlastRay(canvas, cx, cy, cell, explosion, progress, -1, 0)
        drawBlastRay(canvas, cx, cy, cell, explosion, progress, 1, 0)
        drawBlastRay(canvas, cx, cy, cell, explosion, progress, 0, -1)
        drawBlastRay(canvas, cx, cy, cell, explosion, progress, 0, 1)
    }

    private fun drawBlastRay(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        cell: Float,
        explosion: WatchExplosion,
        progress: Float,
        dx: Int,
        dy: Int
    ) {
        var distance = 0
        for (step in 1..explosion.range) {
            val x = explosion.x + dx * step
            val y = explosion.y + dy * step
            if (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.STEEL) break
            distance = step
            if (board[y][x] == WatchTile.BRICK && !explosion.piercing) break
        }
        val reach = cell * maxOf(.28f, distance * (1f - (1f - progress) * (1f - progress)))
        canvas.drawLine(cx, cy, cx + dx * reach, cy + dy * reach, paint)
    }

    private fun drawPlayer(
        canvas: Canvas,
        x: Float,
        y: Float,
        cell: Float,
        color: Int,
        label: String,
        shielded: Boolean = false
    ) {
        if (x < width / 2f - cell * 4.5f || x > width / 2f + cell * 4.5f ||
            y < height / 2f - cell * 4.5f || y > height / 2f + cell * 4.5f
        ) return
        if (shielded) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, cell * .06f)
            paint.color = Color.argb(180, 95, 190, 255)
            canvas.drawCircle(x, y, cell * .43f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawOval(x - cell * .33f, y + cell * .18f, x + cell * .33f, y + cell * .38f, paint)
        rect.set(x - cell * .31f, y - cell * .34f, x + cell * .31f, y + cell * .31f)
        paint.color = color
        canvas.drawRoundRect(rect, cell * .21f, cell * .21f, paint)
        rect.set(x - cell * .25f, y - cell * .29f, x + cell * .25f, y - cell * .2f)
        paint.color = Color.argb(100, 255, 255, 255)
        canvas.drawRoundRect(rect, cell * .05f, cell * .05f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x - cell * .105f, y - cell * .06f, cell * .06f, paint)
        canvas.drawCircle(x + cell * .105f, y - cell * .06f, cell * .06f, paint)
        paint.color = Color.rgb(15, 24, 34)
        canvas.drawCircle(x - cell * .09f, y - cell * .05f, cell * .025f, paint)
        canvas.drawCircle(x + cell * .12f, y - cell * .05f, cell * .025f, paint)
        text(canvas, label, x, y + cell * .18f, cell * .14f, Color.WHITE, true)
    }

    private fun drawNavigation(canvas: Canvas, cx: Float, cy: Float, diameter: Float) {
        val y = cy + diameter * .432f
        val radius = diameter * .047f
        val x1 = cx - diameter * .24f
        val x2 = cx
        val x3 = cx + diameter * .24f
        paint.color = Color.rgb(22, 34, 49)
        canvas.drawCircle(x1, y - diameter * .008f, radius, paint)
        canvas.drawCircle(x2, y - diameter * .008f, radius, paint)
        canvas.drawCircle(x3, y - diameter * .008f, radius, paint)
        text(canvas, "MAP", x1, y + diameter * .005f, diameter * .018f, Color.rgb(173, 208, 228), true)
        text(canvas, "LINK", x2, y + diameter * .005f, diameter * .018f, Color.rgb(125, 239, 184), true)
        text(canvas, "SET", x3, y + diameter * .005f, diameter * .018f, Color.rgb(211, 183, 255), true)
    }

    private fun drawSettings(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        text(canvas, "GAME SETTINGS", cx, cy - diameter * .3f, diameter * .038f, Color.WHITE, true)
        text(canvas, "Tuned for a clear, smooth watch view", cx, cy - diameter * .235f,
            diameter * .021f, Color.rgb(164, 180, 198))
        settingCard(canvas, cx, cy - diameter * .13f, diameter, "EXPLOSION FX",
            if (effectsEnabled) "ON" else "OFF", Color.rgb(255, 151, 68))
        settingCard(canvas, cx, cy - diameter * .015f, diameter, "BOARD CONTRAST",
            if (highContrast) "HIGH" else "STANDARD", Color.rgb(77, 201, 220))
        settingCard(canvas, cx, cy + diameter * .10f, diameter, "POWER-UP NAMES",
            if (showFullPowerUpNames) "FULL" else "SHORT", Color.rgb(211, 183, 255))
        settingCard(canvas, cx, cy + diameter * .225f, diameter, "TOUCH HAPTICS",
            if (hapticsEnabled) "ON" else "OFF", Color.rgb(174, 127, 255))
        text(canvas, "TAP A ROW TO CHANGE", cx, cy + diameter * .34f, diameter * .021f,
            Color.rgb(164, 180, 198), true)
        text(canvas, "BACK", cx, cy + diameter * .43f, diameter * .024f,
            Color.rgb(125, 239, 184), true)
    }

    private fun drawResults(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        text(canvas, resultTitle, cx, cy - diameter * .18f, diameter * .06f,
            Color.WHITE, true)
        text(canvas, resultMessage.take(25), cx, cy - diameter * .08f, diameter * .025f,
            Color.rgb(170, 197, 216))
        settingCard(canvas, cx, cy + diameter * .04f, diameter, "REPLAY", "TAP", Color.rgb(125, 239, 184))
        settingCard(canvas, cx, cy + diameter * .17f, diameter, "PLAY SOLO", "TAP", Color.rgb(77, 201, 220))
        text(canvas, "MAIN MENU", cx, cy + diameter * .43f, diameter * .024f,
            Color.rgb(125, 239, 184), true)
    }

    private fun settingCard(canvas: Canvas, cx: Float, cy: Float, diameter: Float, title: String, value: String, accent: Int) {
        rect.set(cx - diameter * .37f, cy - diameter * .052f, cx + diameter * .37f, cy + diameter * .052f)
        paint.color = Color.rgb(17, 26, 40)
        canvas.drawRoundRect(rect, diameter * .025f, diameter * .025f, paint)
        rect.set(cx - diameter * .37f, cy - diameter * .052f, cx - diameter * .35f, cy + diameter * .052f)
        paint.color = accent
        canvas.drawRoundRect(rect, diameter * .01f, diameter * .01f, paint)
        text(canvas, title, cx - diameter * .3f, cy + diameter * .009f, diameter * .022f,
            Color.rgb(218, 228, 238), true, Paint.Align.LEFT)
        text(canvas, value, cx + diameter * .29f, cy + diameter * .009f, diameter * .021f,
            accent, true, Paint.Align.RIGHT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val swipeThreshold = maxOf(8f, 24f - dashLevel * 4f)
                if (screen == WatchScreen.GAME && (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold)) {
                    if (abs(dx) > abs(dy)) move(if (dx > 0) 1 else -1, 0)
                    else move(0, if (dy > 0) 1 else -1)
                    touchFeedback()
                    invalidate()
                    return true
                }
                if (screen == WatchScreen.RESULTS) {
                    handleResultsTap(event.y)
                    return true
                }
                if (screen == WatchScreen.SETTINGS) {
                    handleSettingsTap(event.y)
                    return true
                }
                handleGameTap(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun handleGameTap(x: Float, y: Float) {
        val diameter = min(width, height).toFloat()
        val centerY = height / 2f
        if (y > centerY + diameter * .34f) {
            when {
                x < width * .37f -> {
                    selectedMap = (selectedMap + 1) % mapSizes.size
                    sendCommand("MAP:$selectedMap")
                    startRound()
                    resetRoundPowerUps()
                }
                x > width * .68f -> screen = WatchScreen.SETTINGS
                else -> connect()
            }
        } else if (y < centerY - diameter * .35f) {
            mode = (mode + 1) % modes.size
            sendCommand("MODE:${modes[mode]}")
            startRound()
        } else {
            placeBomb()
        }
        touchFeedback()
    }

    private fun handleResultsTap(y: Float) {
        val diameter = min(width, height).toFloat()
        val cy = height / 2f
        when {
            y in (cy - diameter * .02f)..(cy + diameter * .1f) -> startRound()
            y in (cy + diameter * .11f)..(cy + diameter * .25f) -> {
                mode = modes.indexOf("SOLO")
                startRound()
            }
            y > cy + diameter * .37f -> {
                mode = 0
                startRound()
            }
        }
        invalidate()
    }

    private fun handleSettingsTap(y: Float) {
        val diameter = min(width, height).toFloat()
        val cy = height / 2f
        when {
            y in (cy - diameter * .19f)..(cy - diameter * .07f) -> effectsEnabled = !effectsEnabled
            y in (cy - diameter * .075f)..(cy + diameter * .045f) -> highContrast = !highContrast
            y in (cy + diameter * .04f)..(cy + diameter * .16f) -> showFullPowerUpNames = !showFullPowerUpNames
            y in (cy + diameter * .165f)..(cy + diameter * .285f) -> hapticsEnabled = !hapticsEnabled
            y > cy + diameter * .37f -> screen = WatchScreen.GAME
        }
        touchFeedback()
        invalidate()
    }

    private fun placeBomb() {
        if (screen != WatchScreen.GAME || bombLocked) return
        if (bombs.size >= bombCapacity) {
            lastPowerUpMessage = "BOMB LIMIT · $bombCapacity"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1400L
            invalidate()
            return
        }
        placeRemoteBomb(playerX to playerY)
        sendCommand("BOMB")
    }

    private fun placeRemoteBomb(position: Pair<Int, Int>, fromOtherDevice: Boolean = false) {
        if (screen != WatchScreen.GAME || bombs.any { it.x == position.first && it.y == position.second }) return
        if (fromOtherDevice || bombs.size < bombCapacity) {
            val bomb = WatchBomb(position.first, position.second, SystemClock.uptimeMillis())
            bombs.add(bomb)
            postDelayed({
                if (bombs.remove(bomb)) {
                    explodeBomb(bomb, SystemClock.uptimeMillis())
                    invalidate()
                }
            }, FUSE_MS)
        } else {
            lastPowerUpMessage = "BOMB LIMIT · $bombCapacity"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1400L
        }
        invalidate()
    }

    private fun addExplosion(
        x: Int,
        y: Int,
        startedAt: Long,
        range: Int = blastRange,
        piercing: Boolean = piercingFlame
    ) {
        if (effectsEnabled) explosions.add(WatchExplosion(x, y, startedAt, range, piercing))
    }

    private fun explodeBomb(bomb: WatchBomb, now: Long) {
        val affectedCells = mutableSetOf(bomb.x to bomb.y)
        val newlyRevealed = mutableSetOf<Pair<Int, Int>>()
        addExplosion(bomb.x, bomb.y, now)
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for ((dx, dy) in directions) {
            for (step in 1..blastRange) {
                val x = bomb.x + dx * step
                val y = bomb.y + dy * step
                if (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.STEEL) break
                affectedCells.add(x to y)
                if (board[y][x] == WatchTile.BRICK) {
                    board[y][x] = WatchTile.FLOOR
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.let {
                        it.hidden = false
                        newlyRevealed.add(x to y)
                    }
                    if (!connectedToPhone && modes[mode] != "SPLEEF" && Random.nextInt(100) < 55) {
                        droppedItems[x to y] = if (Random.nextInt(100) < 25) {
                            WatchDebuff.entries.random().code
                        } else {
                            powerUps.random().code
                        }
                    }
                    if (!piercingFlame) break
                }
            }
        }
        if (clusterBombs) {
            for ((dx, dy) in directions) {
                val x = bomb.x + dx * 2
                val y = bomb.y + dy * 2
                if (x in board.indices && y in board.indices && board[y][x] != WatchTile.STEEL) {
                    addExplosion(x, y, now, range = 1)
                    affectedCells.add(x to y)
                }
            }
        }
        if (modes[mode] == "SPLEEF") {
            affectedCells.forEach { (x, y) -> if (x in board.indices && y in board.indices) board[y][x] = WatchTile.HOLE }
        }
        if (modes[mode] == "SOLO") {
            monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
        }
        val hitPlayer = (playerX to playerY) in affectedCells
        val hitOpponent = modes[mode] != "SOLO" && (opponentX to opponentY) in affectedCells
        if (modes[mode] == "SPLEEF") {
            if (hitPlayer || hitOpponent) finishRound(when {
                hitPlayer && hitOpponent -> null
                hitPlayer -> 1
                else -> 0
            }, "The blast broke the floor under a player.")
        } else {
            if (hitPlayer) {
                if (shieldCharges > 0) shieldCharges-- else {
                    playerHealth = (playerHealth - if (vulnerableHits > 0) 100 else 50).coerceAtLeast(0)
                    if (vulnerableHits > 0) vulnerableHits--
                }
            }
            if (hitOpponent) {
                opponentHealth = (opponentHealth - 50).coerceAtLeast(0)
                if (vampireFlame) playerHealth = (playerHealth + 20).coerceAtMost(150)
            }
            resolveHealthResult()
        }
        if (modes[mode] == "SOLO" && screen == WatchScreen.GAME) updateMonsters(affectedCells - newlyRevealed)
        if (modes[mode] == "SOLO" && monsters.isEmpty() && screen == WatchScreen.GAME) {
            finishRound(0, "All monsters defeated.")
        }
    }

    private fun move(dx: Int, dy: Int) {
        if (screen != WatchScreen.GAME) return
        if (stunMoves > 0) {
            stunMoves--
            invalidate()
            return
        }
        val moveDx = if (reverseMoves > 0) -dx else dx
        val moveDy = if (reverseMoves > 0) -dy else dy
        val x = playerX + moveDx
        val y = playerY + moveDy
        if (modes[mode] == "SPLEEF" &&
            (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.HOLE)
        ) {
            finishRound(1, "You fell off the arena.")
            return
        }
        if (x !in board.indices || y !in board.indices) return
        if (SystemClock.uptimeMillis() - lastMoveAt < if (slowMoves > 0) 800L else 250L) return
        val blockingBomb = bombs.firstOrNull { it.x == x && it.y == y }
        var canMove = board[y][x] != WatchTile.STEEL && board[y][x] != WatchTile.BRICK
        if (blockingBomb != null) {
            val pushX = x + moveDx
            val pushY = y + moveDy
            if (canKickBombs && pushX in board.indices && pushY in board.indices &&
                board[pushY][pushX] != WatchTile.STEEL && board[pushY][pushX] != WatchTile.BRICK &&
                bombs.none { it !== blockingBomb && it.x == pushX && it.y == pushY } &&
                (modes[mode] == "SOLO" || pushX != opponentX || pushY != opponentY)
            ) {
                bombs[bombs.indexOf(blockingBomb)] = blockingBomb.copy(x = pushX, y = pushY)
            } else {
                canMove = false
            }
        }
        if (canMove) {
            playerX = x
            playerY = y
            if (board[y][x] == WatchTile.TELEPORT) {
                val destinationX = board.lastIndex - x
                val destinationY = board.lastIndex - y
                if (board[destinationY][destinationX] == WatchTile.FLOOR) {
                    playerX = destinationX
                    playerY = destinationY
                }
            }
            lastMoveAt = SystemClock.uptimeMillis()
            if (reverseMoves > 0) reverseMoves--
            if (slowMoves > 0) slowMoves--
            if (poisonMoves > 0) {
                poisonMoves--
                playerHealth = (playerHealth - 5).coerceAtLeast(0)
            }
            droppedItems.remove(x to y)?.let(::collectPowerUp)
            if (modes[mode] == "SOLO") updateMonsters()
            if (modes[mode] == "RACE" && board[y][x] == WatchTile.GOAL) finishRound(0, "You reached the center.")
            resolveHealthResult()
        }
        sendCommand(if (moveDx < 0) "LEFT" else if (moveDx > 0) "RIGHT" else if (moveDy < 0) "UP" else "DOWN")
        invalidate()
    }

    private fun collectPowerUp(code: String) {
        val debuff = WatchDebuff.fromCode(code)
        if (debuff != null) {
            collectedDebuffs.add(debuff.code)
            when (debuff) {
                WatchDebuff.SLOW -> slowMoves = 5
                WatchDebuff.REVERSE -> reverseMoves = 5
                WatchDebuff.WEAK_FLAME -> blastRange = (blastRange - 1).coerceAtLeast(1)
                WatchDebuff.BOMB_LOCK -> bombLocked = true
                WatchDebuff.STUN -> stunMoves = 1
                WatchDebuff.FRAGILE -> vulnerableHits = 2
                WatchDebuff.POISON -> poisonMoves = 5
                WatchDebuff.SHIELD_BREAK -> shieldCharges = 0
                WatchDebuff.DASH_DRAIN -> dashLevel = (dashLevel - 1).coerceAtLeast(0)
                WatchDebuff.CURSE -> playerHealth = (playerHealth - 25).coerceAtLeast(0)
            }
            lastPowerUpMessage = "DEBUFF · ${debuff.code}"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
            resolveHealthResult()
            return
        }
        val powerUp = WatchPowerUp.fromCode(code) ?: return
        collectedPowerUps.add(code)
        when (powerUp) {
            WatchPowerUp.FIRE -> blastRange = (blastRange + 1).coerceAtMost(8)
            WatchPowerUp.BOMB -> bombCapacity = (bombCapacity + 1).coerceAtMost(5)
            WatchPowerUp.DASH -> dashLevel = (dashLevel + 1).coerceAtMost(4)
            WatchPowerUp.SHIELD -> shieldCharges = (shieldCharges + 1).coerceAtMost(3)
            WatchPowerUp.KICK -> canKickBombs = true
            WatchPowerUp.BLINK -> {
                val destination = randomSafeFloor()
                playerX = destination.first
                playerY = destination.second
            }
            WatchPowerUp.VAMPIRE -> vampireFlame = true
            WatchPowerUp.CLUSTER -> clusterBombs = true
            WatchPowerUp.PIERCE -> piercingFlame = true
            WatchPowerUp.HEART -> playerHealth = (playerHealth + 50).coerceAtMost(150)
        }
        lastPowerUpMessage = if (showFullPowerUpNames) {
            "${powerUp.code}  ${powerUp.displayName}"
        } else {
            "${powerUp.code} PICKED UP"
        }
        powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
    }

    private fun randomSafeFloor(): Pair<Int, Int> {
        val floors = mutableListOf<Pair<Int, Int>>()
        for (y in 1 until board.lastIndex) for (x in 1 until board.lastIndex) {
            if (board[y][x] == WatchTile.FLOOR && bombs.none { it.x == x && it.y == y } &&
                (x != opponentX || y != opponentY)
            ) floors.add(x to y)
        }
        return floors.randomOrNull() ?: (playerX to playerY)
    }

    private fun resetRoundPowerUps() {
        bombs.clear()
        explosions.clear()
        droppedItems.clear()
        bombCapacity = 1
        blastRange = 2
        shieldCharges = 0
        dashLevel = 0
        canKickBombs = false
        vampireFlame = false
        clusterBombs = false
        piercingFlame = false
        playerHealth = 150
        opponentHealth = 150
        slowMoves = 0
        reverseMoves = 0
        stunMoves = 0
        poisonMoves = 0
        vulnerableHits = 0
        bombLocked = false
        collectedPowerUps.clear()
        collectedDebuffs.clear()
        powerUpMessageUntil = 0L
    }

    private fun startRound() {
        val size = when (modes[mode]) {
            "SPLEEF" -> 13
            "RACE" -> 15
            else -> mapSizes[selectedMap]
        }
        board = createBoard(size)
        if (modes[mode] == "RACE") {
            for (x in 1..7) board[1][x] = WatchTile.FLOOR
            for (y in 1..7) board[y][7] = WatchTile.FLOOR
            for (x in 7..13) board[13][x] = WatchTile.FLOOR
            for (y in 7..13) board[y][7] = WatchTile.FLOOR
            board[7][7] = WatchTile.GOAL
        } else if (modes[mode] != "SPLEEF" && selectedMap > 0) {
            board[2][1] = WatchTile.TELEPORT
            board[size - 3][size - 2] = WatchTile.TELEPORT
        }
        val remoteMatch = connectedToPhone && modes[mode] != "SOLO"
        playerX = if (remoteMatch) board.lastIndex - 1 else 1
        playerY = if (remoteMatch) board.lastIndex - 1 else 1
        opponentX = if (remoteMatch) 1 else board.lastIndex - 1
        opponentY = if (remoteMatch) 1 else board.lastIndex - 1
        monsters.clear()
        resetRoundPowerUps()
        lastMoveAt = SystemClock.uptimeMillis() - 1_000L
        screen = WatchScreen.GAME
        if (modes[mode] == "SOLO") setupMonsters()
        invalidate()
    }

    private fun setupMonsters() {
        val hidingPlaces = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == WatchTile.BRICK && x > 2 && y > 2 &&
                    x < board.lastIndex - 2 && y < board.lastIndex - 2
                ) x to y else null
            }
        }.shuffled().take(4)
        hidingPlaces.forEach { (x, y) -> monsters.add(WatchMonster(x, y)) }
    }

    private fun finishRound(winner: Int?, message: String) {
        if (screen != WatchScreen.GAME) return
        resultTitle = when {
            modes[mode] == "SOLO" && winner == 0 -> "YOU WIN!"
            modes[mode] == "SOLO" -> "GAME OVER"
            winner == 0 -> "PLAYER 1 WINS"
            winner == 1 -> "PLAYER 2 WINS"
            else -> "DRAW"
        }
        resultMessage = message
        bombs.clear()
        screen = WatchScreen.RESULTS
        invalidate()
    }

    private fun resolveHealthResult() {
        if (modes[mode] == "SOLO") {
            if (playerHealth <= 0) finishRound(null, "The monsters defeated you.")
            else if (monsters.isEmpty()) finishRound(0, "All monsters defeated.")
        } else if (playerHealth <= 0 || opponentHealth <= 0) {
            val winner = when {
                playerHealth <= 0 && opponentHealth <= 0 -> null
                playerHealth <= 0 -> 1
                else -> 0
            }
            finishRound(winner, "A player was eliminated.")
        }
    }

    private fun updateMonsters(affectedCells: Set<Pair<Int, Int>> = emptySet()) {
        if (screen != WatchScreen.GAME) return
        monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells }
        monsters.filterNot { it.hidden }.forEach { monster ->
            if (kotlin.math.abs(monster.x - playerX) + kotlin.math.abs(monster.y - playerY) > 1) {
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    .map { (dx, dy) -> monster.x + dx to monster.y + dy }
                    .filter { (x, y) ->
                        x in board.indices && y in board.indices &&
                            board[y][x] == WatchTile.FLOOR && bombs.none { it.x == x && it.y == y }
                    }
                    .minByOrNull { (x, y) -> kotlin.math.abs(x - playerX) + kotlin.math.abs(y - playerY) }
                    ?.let { (x, y) ->
                        monster.x = x
                        monster.y = y
                    }
            }
            if (kotlin.math.abs(monster.x - playerX) + kotlin.math.abs(monster.y - playerY) <= 1) {
                playerHealth = (playerHealth - 25).coerceAtLeast(0)
            }
        }
        resolveHealthResult()
        invalidate()
    }

    private fun cameraLeft(): Int = if (board.size > 9) (playerX - 4).coerceIn(0, board.size - 9) else 0
    private fun cameraTop(): Int = if (board.size > 9) (playerY - 4).coerceIn(0, board.size - 9) else 0

    private fun createBoard(size: Int): Array<Array<WatchTile>> = Array(size) { y -> Array(size) { x ->
        when {
            modes[mode] == "SPLEEF" -> WatchTile.FLOOR
            x == 0 || y == 0 || x == size - 1 || y == size - 1 -> WatchTile.STEEL
            modes[mode] == "RACE" && x == size / 2 && y == size / 2 -> WatchTile.GOAL
            x % 2 == 0 && y % 2 == 0 -> WatchTile.STEEL
            (x + y) % 4 == 0 -> WatchTile.BRICK
            else -> WatchTile.FLOOR
        }
    } }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        paint.style = Paint.Style.FILL
        canvas.drawText(value, x, y, paint)
    }

    private fun touchFeedback() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val FUSE_MS = 3_000L
        private const val EXPLOSION_MS = 520L
    }
}
