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

private enum class WatchTile { FLOOR, STEEL, BRICK, TELEPORT, GOAL }
private enum class WatchScreen { GAME, SETTINGS }

private data class WatchBomb(val x: Int, val y: Int, val plantedAt: Long)
private data class WatchExplosion(val x: Int, val y: Int, val startedAt: Long)

class WatchGameView(
    context: Context,
    private val sendCommand: (String) -> Unit,
    private val connect: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cellRect = RectF()
    private val modes = listOf("CARNAGE", "CASUAL", "HARDCORE", "JUGGERNAUT", "RACE")
    private val mapSizes = listOf(9, 9, 9, 11, 11, 11, 15, 15)
    private var screen = WatchScreen.GAME
    private var mode = 0
    private var selectedMap = 7
    private var board = createBoard(mapSizes[selectedMap])
    private var playerX = 1
    private var playerY = 1
    private var opponentX = 13
    private var opponentY = 13
    private var connectionStatus = "Pair phone, then tap LINK"
    private var highContrast = false
    private var effectsEnabled = true
    private var hapticsEnabled = true
    private var downX = 0f
    private var downY = 0f
    private var lastTapAt = 0L
    private val bombs = mutableListOf<WatchBomb>()
    private val explosions = mutableListOf<WatchExplosion>()

    fun setConnectionStatus(status: String) {
        connectionStatus = status
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(4, 7, 13))
        if (screen == WatchScreen.SETTINGS) {
            drawSettings(canvas)
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
        drawBombsAndExplosions(canvas, left, top, cell, cameraX, cameraY)
        drawPlayer(
            canvas,
            left + (playerX - cameraX + .5f) * cell,
            top + (playerY - cameraY + .5f) * cell,
            cell,
            Color.rgb(59, 174, 255),
            "1"
        )
        drawPlayer(
            canvas,
            left + (opponentX - cameraX + .5f) * cell,
            top + (opponentY - cameraY + .5f) * cell,
            cell,
            Color.rgb(255, 91, 111),
            "2"
        )

        text(canvas, connectionStatus.take(27), cx, cy + diameter * .377f,
            diameter * .022f, Color.rgb(195, 207, 220))
        drawNavigation(canvas, cx, cy, diameter)

        if (bombs.isNotEmpty() || explosions.isNotEmpty()) postInvalidateOnAnimation()
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
            if (age >= FUSE_MS) {
                bombs.removeAt(index)
                addExplosion(bomb.x, bomb.y, now)
            } else {
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
            }
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
        for (step in 1..2) {
            val x = explosion.x + dx * step
            val y = explosion.y + dy * step
            if (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.STEEL) break
            distance = step
        }
        val reach = cell * maxOf(.28f, distance * (1f - (1f - progress) * (1f - progress)))
        canvas.drawLine(cx, cy, cx + dx * reach, cy + dy * reach, paint)
    }

    private fun drawPlayer(canvas: Canvas, x: Float, y: Float, cell: Float, color: Int, label: String) {
        if (x < width / 2f - cell * 4.5f || x > width / 2f + cell * 4.5f ||
            y < height / 2f - cell * 4.5f || y > height / 2f + cell * 4.5f
        ) return
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
        settingCard(canvas, cx, cy - diameter * .1f, diameter, "EXPLOSION FX",
            if (effectsEnabled) "ON" else "OFF", Color.rgb(255, 151, 68))
        settingCard(canvas, cx, cy + diameter * .055f, diameter, "BOARD CONTRAST",
            if (highContrast) "HIGH" else "STANDARD", Color.rgb(77, 201, 220))
        settingCard(canvas, cx, cy + diameter * .21f, diameter, "TOUCH HAPTICS",
            if (hapticsEnabled) "ON" else "OFF", Color.rgb(174, 127, 255))
        text(canvas, "TAP A ROW TO CHANGE", cx, cy + diameter * .34f, diameter * .021f,
            Color.rgb(164, 180, 198), true)
        text(canvas, "BACK", cx, cy + diameter * .43f, diameter * .024f,
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
                if (screen == WatchScreen.GAME && (abs(dx) > 24f || abs(dy) > 24f)) {
                    if (abs(dx) > abs(dy)) move(if (dx > 0) 1 else -1, 0)
                    else move(0, if (dy > 0) 1 else -1)
                    touchFeedback()
                    invalidate()
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
                    board = createBoard(mapSizes[selectedMap])
                    playerX = 1
                    playerY = 1
                    opponentX = board.lastIndex - 1
                    opponentY = board.lastIndex - 1
                }
                x > width * .68f -> screen = WatchScreen.SETTINGS
                else -> connect()
            }
        } else if (y < centerY - diameter * .35f) {
            mode = (mode + 1) % modes.size
            board = createBoard(board.size)
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapAt < DOUBLE_TAP_MS) {
                lastTapAt = 0L
                detonate()
            } else {
                lastTapAt = now
                val thisTap = now
                postDelayed({
                    if (lastTapAt == thisTap) {
                        placeBomb()
                        lastTapAt = 0L
                    }
                }, DOUBLE_TAP_MS)
            }
        }
        touchFeedback()
    }

    private fun handleSettingsTap(y: Float) {
        val diameter = min(width, height).toFloat()
        val cy = height / 2f
        when {
            y in (cy - diameter * .16f)..(cy - diameter * .04f) -> effectsEnabled = !effectsEnabled
            y in (cy - diameter * .005f)..(cy + diameter * .115f) -> highContrast = !highContrast
            y in (cy + diameter * .15f)..(cy + diameter * .27f) -> hapticsEnabled = !hapticsEnabled
            y > cy + diameter * .37f -> screen = WatchScreen.GAME
        }
        touchFeedback()
        invalidate()
    }

    private fun placeBomb() {
        if (bombs.none { it.x == playerX && it.y == playerY }) {
            bombs.add(WatchBomb(playerX, playerY, SystemClock.uptimeMillis()))
            sendCommand("BOMB")
        }
        invalidate()
    }

    private fun detonate() {
        sendCommand("DETONATE")
        val now = SystemClock.uptimeMillis()
        if (bombs.isEmpty()) {
            addExplosion(playerX, playerY, now)
        } else {
            for (bomb in bombs) addExplosion(bomb.x, bomb.y, now)
            bombs.clear()
        }
        invalidate()
    }

    private fun addExplosion(x: Int, y: Int, startedAt: Long) {
        if (effectsEnabled) explosions.add(WatchExplosion(x, y, startedAt))
    }

    private fun move(dx: Int, dy: Int) {
        val x = (playerX + dx).coerceIn(1, board.lastIndex - 1)
        val y = (playerY + dy).coerceIn(1, board.lastIndex - 1)
        if (board[y][x] != WatchTile.STEEL && board[y][x] != WatchTile.BRICK) {
            playerX = x
            playerY = y
        }
        sendCommand(if (dx < 0) "LEFT" else if (dx > 0) "RIGHT" else if (dy < 0) "UP" else "DOWN")
    }

    private fun cameraLeft(): Int = if (board.size > 9) (playerX - 4).coerceIn(0, board.size - 9) else 0
    private fun cameraTop(): Int = if (board.size > 9) (playerY - 4).coerceIn(0, board.size - 9) else 0

    private fun createBoard(size: Int): Array<Array<WatchTile>> = Array(size) { y -> Array(size) { x ->
        when {
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
        private const val DOUBLE_TAP_MS = 350L
        private const val FUSE_MS = 1_800L
        private const val EXPLOSION_MS = 520L
    }
}
