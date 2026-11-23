package com.wearbomber.watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

private enum class WatchTile { FLOOR, STEEL, BRICK, TELEPORT, GOAL }

class WatchGameView(
    context: Context,
    private val sendCommand: (String) -> Unit,
    private val connect: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = 0
    private val modes = listOf("CARNAGE", "CASUAL", "HARDCORE", "JUGGERNAUT", "RACE")
    private val mapSizes = listOf(9, 9, 9, 11, 11, 11, 15, 15)
    private var selectedMap = 7
    private var board = createBoard(mapSizes[selectedMap])
    private var playerX = 1
    private var playerY = 1
    private var opponentX = 13
    private var opponentY = 13
    private var connectionStatus = "Tap CONNECT after pairing"
    private var downX = 0f
    private var downY = 0f
    private var lastTapAt = 0L

    fun setConnectionStatus(status: String) {
        connectionStatus = status
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val diameter = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val safeDiameter = diameter * .70f
        text(canvas, "WEAR BOMBER", centerX, centerY - diameter * .405f, diameter * .047f, Color.WHITE, true)
        text(canvas, modes[mode], centerX, centerY - diameter * .355f, diameter * .031f, Color.rgb(152, 195, 255), true)
        drawBoard(canvas, centerX, centerY - diameter * .005f, safeDiameter)
        text(canvas, "P1", centerX + (playerX - cameraLeft() + .5f) * safeDiameter / 9f - safeDiameter / 2f,
            centerY - diameter * .005f + (playerY - cameraTop() + .5f) * safeDiameter / 9f - safeDiameter / 2f + safeDiameter / 9f * .13f,
            safeDiameter / 9f * .23f, Color.WHITE, true)
        val footerY = centerY + diameter * .395f
        text(canvas, connectionStatus.take(28), centerX, footerY - diameter * .04f, diameter * .025f, Color.LTGRAY)
        text(canvas, "SWIPE MOVE · TAP BOMB · DOUBLE-TAP DETONATE", centerX, footerY, diameter * .022f, Color.LTGRAY)
        text(canvas, "MAP ◀  ·  CONNECT  ·  MODE ▶", centerX, footerY + diameter * .045f,
            diameter * .023f, Color.rgb(123, 236, 184), true)
    }

    private fun drawBoard(canvas: Canvas, cx: Float, cy: Float, safeDiameter: Float) {
        val cell = safeDiameter / 9f
        val left = cx - safeDiameter / 2f
        val top = cy - safeDiameter / 2f
        val cameraX = cameraLeft()
        val cameraY = cameraTop()
        for (visibleY in 0 until 9) for (visibleX in 0 until 9) {
            val x = visibleX + cameraX
            val y = visibleY + cameraY
            val tile = if (x in board.indices && y in board.indices) board[y][x] else WatchTile.STEEL
            paint.color = when (tile) {
                WatchTile.FLOOR -> if ((x + y) % 2 == 0) Color.rgb(36, 54, 53) else Color.rgb(45, 65, 60)
                WatchTile.STEEL -> Color.rgb(99, 110, 125)
                WatchTile.BRICK -> Color.rgb(157, 83, 57)
                WatchTile.TELEPORT -> Color.rgb(163, 72, 222)
                WatchTile.GOAL -> Color.rgb(250, 198, 57)
            }
            canvas.drawRect(left + visibleX * cell, top + visibleY * cell,
                left + (visibleX + 1) * cell - 1, top + (visibleY + 1) * cell - 1, paint)
        }
        drawPlayer(canvas, left + (playerX - cameraX + .5f) * cell, top + (playerY - cameraY + .5f) * cell, cell, Color.rgb(74, 155, 250))
        drawPlayer(canvas, left + (opponentX - cameraX + .5f) * cell, top + (opponentY - cameraY + .5f) * cell, cell, Color.rgb(240, 79, 78))
    }

    private fun cameraLeft(): Int = if (board.size > 9) (playerX - 4).coerceIn(0, board.size - 9) else 0
    private fun cameraTop(): Int = if (board.size > 9) (playerY - 4).coerceIn(0, board.size - 9) else 0

    private fun drawPlayer(canvas: Canvas, x: Float, y: Float, cell: Float, color: Int) {
        if (x < width / 2f - cell * 4.5f || x > width / 2f + cell * 4.5f ||
            y < height / 2f - cell * 4.5f || y > height / 2f + cell * 4.5f
        ) return
        paint.color = color
        canvas.drawCircle(x, y, cell * .34f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y - cell * .06f, cell * .1f, paint)
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
                if (abs(dx) > 24f || abs(dy) > 24f) {
                    if (abs(dx) > abs(dy)) move(if (dx > 0) 1 else -1, 0)
                    else move(0, if (dy > 0) 1 else -1)
                } else {
                    val now = System.currentTimeMillis()
                    if (downY > height * .77f && downX > width * .27f && downX < width * .73f) {
                        lastTapAt = 0L
                        connect()
                    } else if (downY > height * .77f && downX <= width * .27f) {
                        lastTapAt = 0L
                        selectedMap = (selectedMap + 1) % mapSizes.size
                        board = createBoard(mapSizes[selectedMap])
                        playerX = 1
                        playerY = 1
                        opponentX = board.lastIndex - 1
                        opponentY = board.lastIndex - 1
                    } else if (downY > height * .77f && downX >= width * .73f) {
                        lastTapAt = 0L
                        mode = (mode + 1) % modes.size
                        board = createBoard(board.size)
                    } else if (now - lastTapAt < 350L) {
                        sendCommand("DETONATE")
                        lastTapAt = 0L
                    } else {
                        lastTapAt = now
                        val thisTap = now
                        postDelayed({
                            if (lastTapAt == thisTap) {
                                sendCommand("BOMB")
                                lastTapAt = 0L
                            }
                        }, 340L)
                    }
                }
                invalidate()
                return true
            }
        }
        return true
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

    private fun createBoard(size: Int): Array<Array<WatchTile>> = Array(size) { y -> Array(size) { x ->
        when {
            x == 0 || y == 0 || x == size - 1 || y == size - 1 -> WatchTile.STEEL
            modes[mode] == "RACE" && x == size / 2 && y == size / 2 -> WatchTile.GOAL
            x % 2 == 0 && y % 2 == 0 -> WatchTile.STEEL
            (x + y) % 4 == 0 -> WatchTile.BRICK
            else -> WatchTile.FLOOR
        }
    } }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
    }
}
