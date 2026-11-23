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
import java.util.Random as JavaRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private enum class WatchTile { FLOOR, STEEL, BRICK, TELEPORT, GOAL, HOLE, KEY, DOOR }
private enum class WatchScreen { MENU, GAME, SETTINGS, RESULTS, CREATOR }
private enum class WatchOutfit(val color: Int) {
    RED(Color.rgb(235, 75, 75)),
    BLUE(Color.rgb(65, 145, 245)),
    GREEN(Color.rgb(65, 195, 115)),
    PURPLE(Color.rgb(180, 90, 225)),
    ORANGE(Color.rgb(245, 155, 55)),
    CYAN(Color.rgb(45, 205, 205))
}
private enum class WatchAccessory { NONE, CROWN, HORNS, HALO, VISOR, TOP_HAT }
private enum class WatchSpeed(val cooldown: Long) {
    SLOW(680L),
    NORMAL(425L),
    FAST(270L),
    TURBO_DASH(160L)
}
private enum class WatchPerk { NONE, SHIELD, KICK, PIERCE }
private enum class WatchDebuff(
    val code: String,
    val label: String,
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
private data class WatchParticle(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
    val color: Int,
    val size: Float,
    val bornAt: Long,
    val lifetime: Long
)
private data class WatchMonster(
    var x: Int,
    var y: Int,
    var hidden: Boolean = true,
    var lastMovedAt: Long = 0L
)

class WatchGameView(
    context: Context,
    private val sendCommand: (String) -> Unit,
    private val connect: () -> Unit
) : View(context) {
    private val watchSettings = context.applicationContext
        .getSharedPreferences("wearbomber_watch_settings", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cellRect = RectF()
    private val modes = listOf("CARNAGE", "CASUAL", "HARDCORE", "JUGGERNAUT", "RACE", "SPLEEF", "SOLO")
    private val mapSizes = listOf(9, 9, 9, 11, 11, 11, 15, 15)
    private var screen = WatchScreen.MENU
    private var mode = 0
    private var selectedMap = 7
    private var soloLevel = 1
    private var soloHasKey = false
    private var doorAnimationStartedAt = 0L
    private var lastMonsterHitAt = 0L
    private var deathAnimationStartedAt = 0L
    private var deathAnimationComplete = false
    private var deathPlayerIndex = -1
    private var board = createBoard(mapSizes[selectedMap])
    private val monsters = mutableListOf<WatchMonster>()
    private var playerX = 1
    private var playerY = 1
    private var playerWalkFromX = playerX
    private var playerWalkFromY = playerY
    private var opponentX = 13
    private var opponentY = 13
    private var opponentWalkFromX = opponentX
    private var opponentWalkFromY = opponentY
    private var opponentLastWalkAt = 0L
    private var connectionStatus = "Pair phone, then tap LINK"
    private var connectedToPhone = false
    private var highContrast = watchSettings.getBoolean("high_contrast", false)
    private var effectsEnabled = watchSettings.getBoolean("effects_enabled", true)
    private var hapticsEnabled = watchSettings.getBoolean("haptics_enabled", true)
    private var showFullPowerUpNames = watchSettings.getBoolean("full_power_up_names", false)
    private var jumpScaresEnabled = watchSettings.getBoolean("jump_scares_enabled", true)
    private var characterMaxHealth = watchSettings.getInt("character_max_health", 150).coerceIn(50, 1000)
    private var characterOutfit = WatchOutfit.entries[
        watchSettings.getInt("character_outfit", WatchOutfit.BLUE.ordinal).coerceIn(WatchOutfit.entries.indices)
    ]
    private var characterAccessory = WatchAccessory.entries[
        watchSettings.getInt("character_accessory", WatchAccessory.NONE.ordinal)
            .coerceIn(WatchAccessory.entries.indices)
    ]
    private var characterSpeed = WatchSpeed.entries[
        watchSettings.getInt("character_speed", WatchSpeed.NORMAL.ordinal).coerceIn(WatchSpeed.entries.indices)
    ]
    private var characterPerk = WatchPerk.entries[
        watchSettings.getInt("character_perk", WatchPerk.NONE.ordinal).coerceIn(WatchPerk.entries.indices)
    ]
    private var creatorPage = 0
    private val effectiveMaximumHealth: Int
        get() = if (modes[mode] == "HARDCORE") 50 else characterMaxHealth
    private var jumpScareUntil = 0L
    private var bombCapacity = 1
    private var blastRange = 2
    private var shieldCharges = 0
    private var dashLevel = 0
    private var canKickBombs = false
    private var vampireFlame = false
    private var clusterBombs = false
    private var piercingFlame = false
    private var playerHealth = characterMaxHealth
    private var opponentHealth = 150
    private var lastPowerUpMessage = ""
    private var powerUpMessageUntil = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastMoveAt = 0L
    private var vulnerableHits = 0
    private var bombLocked = false
    private val collectedPowerUps = linkedSetOf<String>()
    private val collectedDebuffs = linkedSetOf<String>()
    private val activeDebuffs = mutableMapOf<WatchDebuff, Long>()
    private val reversibleDebuffs = mutableSetOf<WatchDebuff>()
    private var resultTitle = ""
    private var resultMessage = ""
    private val bombs = mutableListOf<WatchBomb>()
    private val explosions = mutableListOf<WatchExplosion>()
    private val particles = mutableListOf<WatchParticle>()
    private val droppedItems = mutableMapOf<Pair<Int, Int>, String>()
    private val powerUps = WatchPowerUp.entries.toList()
    private val monsterTick = object : Runnable {
        override fun run() {
            if (screen == WatchScreen.GAME && modes[mode] == "SOLO" && !connectedToPhone) {
                updateMonsters()
                postDelayed(this, 500L)
            }
        }
    }

    fun setConnectionStatus(status: String) {
        connectionStatus = status
        connectedToPhone = status == "Connected"
        invalidate()
    }

    fun consumeBackGesture(): Boolean = when (screen) {
        WatchScreen.GAME -> {
            move(1, 0)
            touchFeedback()
            true
        }
        WatchScreen.SETTINGS, WatchScreen.RESULTS, WatchScreen.CREATOR -> {
            screen = WatchScreen.MENU
            invalidate()
            true
        }
        WatchScreen.MENU -> false
    }

    fun handleRemoteCommand(command: String) {
        when {
            command.startsWith("CHARACTER:") -> applyCharacterProfile(command)
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
                val seed = parts.getOrNull(3)?.toLongOrNull()
                parts.getOrNull(4)?.toIntOrNull()?.let { soloLevel = it.coerceIn(1, 10) }
                startRound(seed)
            }
            command.startsWith("MAPBOARD:") -> {
                val payload = command.substringAfter(':')
                val size = payload.substringBefore(':').toIntOrNull() ?: return
                val encoded = payload.substringAfter(':', "")
                if (size !in 3..15 || encoded.length != size * size) return
                board = Array(size) { y ->
                    Array(size) { x ->
                        when (encoded[y * size + x]) {
                            'S' -> WatchTile.STEEL
                            'B' -> WatchTile.BRICK
                            'T' -> WatchTile.TELEPORT
                            'G' -> WatchTile.GOAL
                            'H' -> WatchTile.HOLE
                            'K' -> WatchTile.KEY
                            'D' -> WatchTile.DOOR
                            else -> WatchTile.FLOOR
                        }
                    }
                }
                val remoteStart = board.lastIndex - if (modes[mode] == "SPLEEF") 2 else 1
                playerX = if (modes[mode] == "SOLO") 1 else remoteStart
                playerY = 1
                if (modes[mode] != "SOLO") playerY = remoteStart
                opponentX = 1
                opponentY = 1
                playerWalkFromX = playerX
                playerWalkFromY = playerY
                opponentWalkFromX = opponentX
                opponentWalkFromY = opponentY
                bombs.clear()
                explosions.clear()
                droppedItems.clear()
                monsters.clear()
                resetRoundPowerUps()
                soloHasKey = false
                doorAnimationStartedAt = 0L
                screen = WatchScreen.GAME
                invalidate()
            }
            command.startsWith("MONSTERS:") -> {
                val positions = command.substringAfter(':').split(';').mapNotNull { entry ->
                    val coordinates = entry.split(',')
                    val x = coordinates.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val y = coordinates.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    val hidden = coordinates.getOrNull(2)?.toIntOrNull() != 0
                    if (x in board.indices && y in board.indices) {
                        WatchMonster(x, y, hidden, SystemClock.uptimeMillis())
                    } else null
                }
                monsters.clear()
                monsters.addAll(positions)
                invalidate()
            }
            command.startsWith("KEY:") -> {
                soloHasKey = command.substringAfter(':').toIntOrNull() == 1
                if (soloHasKey) board.indices.forEach { y ->
                    board[y].indices.forEach { x ->
                        if (board[y][x] == WatchTile.KEY) board[y][x] = WatchTile.FLOOR
                    }
                }
                invalidate()
            }
            command.startsWith("LEVEL:") -> {
                command.substringAfter(':').toIntOrNull()?.let { soloLevel = it.coerceIn(1, 10) }
                invalidate()
            }
            command.startsWith("SOLO_HP:") -> {
                command.substringAfter(':').toIntOrNull()?.let {
                    playerHealth = it.coerceIn(0, effectiveMaximumHealth)
                }
                resolveHealthResult()
                invalidate()
            }
            command == "DOOR:1" -> {
                doorAnimationStartedAt = SystemClock.uptimeMillis()
                invalidate()
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
                particles.clear()
                removeCallbacks(monsterTick)
                deathAnimationStartedAt = 0L
                deathPlayerIndex = -1
                screen = WatchScreen.RESULTS
                invalidate()
            }
            command.startsWith("HOST_POS:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                if (modes[mode] == "SOLO") {
                    val nextX = x.coerceIn(board.indices)
                    val nextY = y.coerceIn(board.indices)
                    if (playerX != nextX || playerY != nextY) {
                        playerWalkFromX = if (abs(playerX - nextX) + abs(playerY - nextY) == 1) playerX else nextX
                        playerWalkFromY = if (abs(playerX - nextX) + abs(playerY - nextY) == 1) playerY else nextY
                        lastMoveAt = SystemClock.uptimeMillis()
                    }
                    playerX = nextX
                    playerY = nextY
                } else {
                    val nextX = x.coerceIn(board.indices)
                    val nextY = y.coerceIn(board.indices)
                    if (opponentX != nextX || opponentY != nextY) {
                        opponentWalkFromX = if (abs(opponentX - nextX) + abs(opponentY - nextY) == 1) opponentX else nextX
                        opponentWalkFromY = if (abs(opponentX - nextX) + abs(opponentY - nextY) == 1) opponentY else nextY
                        opponentLastWalkAt = SystemClock.uptimeMillis()
                    }
                    opponentX = nextX
                    opponentY = nextY
                }
                invalidate()
            }
            command.startsWith("WATCH_POS:") -> {
                val values = command.substringAfter(':').split(':')
                val nextX = (values.getOrNull(0)?.toIntOrNull() ?: return).coerceIn(board.indices)
                val nextY = (values.getOrNull(1)?.toIntOrNull() ?: return).coerceIn(board.indices)
                if (playerX != nextX || playerY != nextY) {
                    playerWalkFromX = if (abs(playerX - nextX) + abs(playerY - nextY) == 1) playerX else nextX
                    playerWalkFromY = if (abs(playerX - nextX) + abs(playerY - nextY) == 1) playerY else nextY
                    lastMoveAt = SystemClock.uptimeMillis()
                }
                playerX = nextX
                playerY = nextY
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
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.let {
                        it.hidden = false
                        it.lastMovedAt = SystemClock.uptimeMillis()
                    }
                }
                invalidate()
            }
            command.startsWith("CHAIN:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                val bomb = bombs.firstOrNull { it.x == x && it.y == y } ?: return
                if (bombs.remove(bomb)) {
                    explodeBomb(bomb, SystemClock.uptimeMillis(), fromChain = true)
                    invalidate()
                }
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
        val now = SystemClock.uptimeMillis()
        expireDebuffs(now)
        if (screen == WatchScreen.GAME) {
            canvas.drawColor(Color.rgb(4, 7, 13))
        } else {
            drawMenuBackground(canvas)
        }
        if (screen == WatchScreen.MENU) {
            drawWatchMenu(canvas)
            return
        }
        if (screen == WatchScreen.SETTINGS) {
            drawSettings(canvas)
            return
        }
        if (screen == WatchScreen.RESULTS) {
            drawResults(canvas)
            return
        }
        if (screen == WatchScreen.CREATOR) {
            drawCharacterCreator(canvas)
            return
        }

        val diameter = min(width, height).toFloat()
        if (diameter <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val ambient = ((SystemClock.uptimeMillis() / 900f).let { sin(it) + 1f } * 5f).toInt() + 8
        paint.color = Color.argb(ambient, 29, 151, 173)
        canvas.drawCircle(cx - diameter * .52f, cy - diameter * .3f, diameter * .42f, paint)
        paint.color = Color.argb(ambient, 125, 67, 175)
        canvas.drawCircle(cx + diameter * .55f, cy + diameter * .3f, diameter * .35f, paint)
        val boardSize = diameter * .68f
        val cell = boardSize / 9f
        val left = cx - boardSize / 2f
        val top = cy - boardSize / 2f
        val cameraX = cameraLeft()
        val cameraY = cameraTop()

        text(canvas, "WEAR BOMBER", cx, cy - diameter * .432f, diameter * .032f, Color.WHITE, true)
        val gameStatus = if (modes[mode] == "SOLO") {
            "LEVEL $soloLevel/10  ·  ${if (soloHasKey) "KEY" else "FIND KEY"}"
        } else {
            "${modes[mode]}  ·  BOMBS ${bombs.size}/$bombCapacity"
        }
        text(canvas, gameStatus,
            cx, cy - diameter * .365f, diameter * .021f, Color.rgb(118, 222, 255), true)
        val blastEnergy = explosions.maxOfOrNull {
            (1f - (now - it.startedAt) / EXPLOSION_MS.toFloat()).coerceIn(0f, 1f)
        } ?: 0f
        canvas.save()
        if (blastEnergy > 0f) {
            val shake = diameter * .0035f * blastEnergy
            canvas.translate(sin(now / 17f) * shake, cos(now / 21f) * shake)
        }
        drawBoard(canvas, left, top, cell, cameraX, cameraY)
        drawPowerUps(canvas, left, top, cell, cameraX, cameraY)
        drawBombsAndExplosions(canvas, left, top, cell, cameraX, cameraY)
        drawParticles(canvas, left, top, cell, cameraX, cameraY)
        val playerPosition = interpolatedPosition(playerX, playerY, playerWalkFromX, playerWalkFromY, lastMoveAt, now)
        val doorProgress = if (doorAnimationStartedAt != 0L) {
            ((now - doorAnimationStartedAt).toFloat() / 850L).coerceIn(0f, 1f)
        } else 0f
        val deathProgress = if (deathAnimationStartedAt != 0L) {
            ((now - deathAnimationStartedAt).toFloat() / 650L).coerceIn(0f, 1f)
        } else 0f
        val playerPixelX = left + (playerPosition.first - cameraX + .5f) * cell
        val playerPixelY = top + (playerPosition.second - cameraY + .5f) * cell
        val playerDeathProgress = if (deathPlayerIndex == 0) deathProgress else 0f
        canvas.save()
        if (doorProgress > 0f || playerDeathProgress > 0f) {
            canvas.rotate(if (playerDeathProgress > 0f) playerDeathProgress * 95f else doorProgress * 12f,
                playerPixelX, playerPixelY)
            canvas.translate(0f, -cell * maxOf(doorProgress * .22f, playerDeathProgress * .2f))
        }
        drawPlayer(
            canvas,
            playerPixelX,
            playerPixelY,
            cell * (1f - maxOf(doorProgress * .55f, playerDeathProgress * .72f)),
            Color.rgb(59, 174, 255),
            "1",
            shieldCharges > 0,
            walking = doorProgress > 0f || playerDeathProgress > 0f || now - lastMoveAt in 0L..420L,
            healthRatio = playerHealth.toFloat() / effectiveMaximumHealth
                .coerceAtLeast(1)
                .toFloat(),
            accessory = characterAccessory
        )
        canvas.restore()
        if (modes[mode] != "SOLO") {
            val opponentPosition = interpolatedPosition(
                opponentX, opponentY, opponentWalkFromX, opponentWalkFromY, opponentLastWalkAt, now
            )
            val opponentPixelX = left + (opponentPosition.first - cameraX + .5f) * cell
            val opponentPixelY = top + (opponentPosition.second - cameraY + .5f) * cell
            val opponentDeathProgress = if (deathPlayerIndex == 1) deathProgress else 0f
            canvas.save()
            if (opponentDeathProgress > 0f) {
                canvas.rotate(opponentDeathProgress * 95f, opponentPixelX, opponentPixelY)
                canvas.translate(0f, -cell * opponentDeathProgress * .2f)
            }
            drawPlayer(
                canvas,
                opponentPixelX,
                opponentPixelY,
                cell * (1f - opponentDeathProgress * .72f),
                Color.rgb(255, 91, 111),
                "2",
                walking = opponentDeathProgress > 0f || now - opponentLastWalkAt in 0L..420L,
                healthRatio = opponentHealth.toFloat() / 150f
            )
            canvas.restore()
        }
        monsters.filterNot { it.hidden }.forEach { monster ->
            drawPlayer(canvas, left + (monster.x - cameraX + .5f) * cell,
                top + (monster.y - cameraY + .5f) * cell, cell, Color.rgb(148, 202, 82), "M")
        }
        canvas.restore()

        val powerSummary = collectedPowerUps.toList().takeLast(3).joinToString(" ")
        val debuffSummary = activeDebuffSummary(now)
        text(canvas, "P:${powerSummary.ifEmpty { "-" }} D:${debuffSummary.ifEmpty { "-" }}",
            cx, cy + diameter * .325f, diameter * .016f, Color.rgb(178, 204, 218))
        val showingPowerUpMessage = now < powerUpMessageUntil
        text(
            canvas,
            if (showingPowerUpMessage) lastPowerUpMessage.take(25) else connectionStatus.take(27),
            cx,
            cy + diameter * .377f,
            diameter * .021f,
            if (showingPowerUpMessage) Color.rgb(255, 221, 130) else Color.rgb(195, 207, 220),
            showingPowerUpMessage
        )
        drawNavigation(canvas, cx, cy, diameter)
        if (SystemClock.uptimeMillis() < jumpScareUntil) {
            drawJumpScare(canvas)
            postInvalidateOnAnimation()
        }

        if (bombs.isNotEmpty() || explosions.isNotEmpty() || particles.isNotEmpty() ||
            doorAnimationStartedAt != 0L || deathAnimationStartedAt != 0L ||
            SystemClock.uptimeMillis() < powerUpMessageUntil ||
            now - lastMoveAt in 0L..420L || now - opponentLastWalkAt in 0L..420L
        ) postInvalidateOnAnimation()
        if (activeDebuffs.isNotEmpty()) postInvalidateDelayed(250L)
    }

    private fun drawMenuBackground(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        canvas.drawColor(Color.rgb(43, 56, 64))
        paint.color = Color.rgb(50, 67, 74)
        canvas.drawCircle(width * .08f, height * .12f, diameter * .42f, paint)
        paint.color = Color.rgb(47, 62, 72)
        canvas.drawCircle(width * .94f, height * .88f, diameter * .38f, paint)
        paint.color = Color.argb(18, 255, 232, 183)
        canvas.drawCircle(width * .84f, height * .2f, diameter * .17f, paint)
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
            WatchTile.KEY -> {
                paint.color = Color.rgb(37, 54, 57)
                canvas.drawRoundRect(cellRect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(255, 215, 83)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, cell * .09f)
                canvas.drawCircle(x + cell * .42f, y + cell * .46f, cell * .18f, paint)
                canvas.drawLine(x + cell * .53f, y + cell * .58f,
                    x + cell * .75f, y + cell * .8f, paint)
                canvas.drawLine(x + cell * .67f, y + cell * .72f,
                    x + cell * .75f, y + cell * .62f, paint)
                paint.style = Paint.Style.FILL
            }
            WatchTile.DOOR -> {
                paint.color = Color.rgb(64, 42, 33)
                canvas.drawRoundRect(cellRect, cell * .08f, cell * .08f, paint)
                rect.set(x + cell * .2f, y + cell * .1f, x + cell * .8f, y + cell * .92f)
                paint.color = Color.rgb(160, 101, 57)
                canvas.drawRoundRect(rect, cell * .06f, cell * .06f, paint)
                paint.color = Color.rgb(230, 188, 108)
                canvas.drawCircle(x + cell * .68f, y + cell * .53f, cell * .045f, paint)
                if (!soloHasKey) {
                    paint.color = Color.rgb(255, 205, 90)
                    canvas.drawRect(x + cell * .32f, y + cell * .48f, x + cell * .68f,
                        y + cell * .55f, paint)
                }
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

    private fun emitParticles(gridX: Int, gridY: Int, colors: IntArray, count: Int, speed: Float) {
        val now = SystemClock.uptimeMillis()
        repeat(count) {
            val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val velocity = speed * (.35f + Random.nextFloat())
            particles.add(
                WatchParticle(
                    gridX + .5f,
                    gridY + .5f,
                    cos(angle) * velocity,
                    sin(angle) * velocity,
                    colors.random(),
                    .045f + Random.nextFloat() * .075f,
                    now,
                    420L + Random.nextLong(0L, 381L)
                )
            )
        }
        while (particles.size > 240) particles.removeAt(0)
    }

    private fun drawParticles(
        canvas: Canvas,
        left: Float,
        top: Float,
        cell: Float,
        cameraX: Int,
        cameraY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.clipRect(left, top, left + cell * 9f, top + cell * 9f)
        paint.style = Paint.Style.FILL
        var index = particles.lastIndex
        while (index >= 0) {
            val particle = particles[index]
            val progress = (now - particle.bornAt).toFloat() / particle.lifetime
            if (progress >= 1f) {
                particles.removeAt(index)
            } else {
                val elapsed = (now - particle.bornAt) / 1_000f
                val x = left + (particle.x + particle.velocityX * elapsed - cameraX) * cell
                val y = top + (particle.y + particle.velocityY * elapsed +
                    .75f * elapsed * elapsed - cameraY) * cell
                val alpha = ((1f - progress) * 230f).toInt().coerceIn(0, 230)
                val radius = cell * particle.size * (1f - progress * .65f)
                paint.color = Color.argb(alpha, Color.red(particle.color),
                    Color.green(particle.color), Color.blue(particle.color))
                if (index % 4 == 0) {
                    canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint)
                } else {
                    canvas.drawCircle(x, y, radius, paint)
                }
            }
            index--
        }
        canvas.restore()
    }

    private fun drawPlayer(
        canvas: Canvas,
        x: Float,
        y: Float,
        cell: Float,
        color: Int,
        label: String,
        shielded: Boolean = false,
        walking: Boolean = false,
        healthRatio: Float? = null,
        accessory: WatchAccessory = WatchAccessory.NONE
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
        val walk = if (walking) sin(SystemClock.uptimeMillis() / 65f) else 0f
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawOval(x - cell * .22f, y + cell * .34f, x + cell * .22f, y + cell * .42f, paint)
        paint.color = Color.rgb(
            (Color.red(color) * .68f).toInt(),
            (Color.green(color) * .68f).toInt(),
            (Color.blue(color) * .68f).toInt()
        )
        paint.strokeWidth = maxOf(1f, cell * .055f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x - cell * .055f, y + cell * .17f,
            x - cell * (.06f + walk * .06f), y + cell * .35f, paint)
        canvas.drawLine(x + cell * .055f, y + cell * .17f,
            x + cell * (.06f - walk * .06f), y + cell * .35f, paint)
        canvas.drawLine(x - cell * .14f, y - cell * .06f,
            x - cell * (.22f - walk * .07f), y + cell * .09f, paint)
        canvas.drawLine(x + cell * .14f, y - cell * .06f,
            x + cell * (.22f + walk * .07f), y + cell * (.09f - walk * .025f), paint)
        paint.strokeCap = Paint.Cap.BUTT
        rect.set(x - cell * .15f, y - cell * .13f, x + cell * .15f, y + cell * .19f)
        paint.color = color
        canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, cell * .018f)
        paint.color = Color.argb(185, 9, 20, 31)
        canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 10, 22, 31)
        canvas.drawRect(x - cell * .14f, y + cell * .075f, x + cell * .14f, y + cell * .12f, paint)
        paint.color = Color.rgb(255, 202, 94)
        canvas.drawRoundRect(x - cell * .025f, y + cell * .077f, x + cell * .025f,
            y + cell * .118f, cell * .01f, cell * .01f, paint)
        paint.color = Color.rgb(13, 23, 33)
        canvas.drawCircle(x - cell * .2f, y + cell * .09f, cell * .035f, paint)
        canvas.drawCircle(x + cell * .2f, y + cell * .09f, cell * .035f, paint)
        canvas.drawOval(x - cell * .19f, y + cell * .34f, x - cell * .04f, y + cell * .42f, paint)
        canvas.drawOval(x + cell * .04f, y + cell * .34f, x + cell * .19f, y + cell * .42f, paint)
        rect.set(x - cell * .11f, y - cell * .38f, x + cell * .11f, y - cell * .16f)
        paint.color = color
        canvas.drawOval(rect, paint)
        paint.color = Color.argb(100, 255, 255, 255)
        rect.set(x - cell * .09f, y - cell * .34f, x + cell * .09f, y - cell * .30f)
        canvas.drawRoundRect(rect, cell * .025f, cell * .025f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x - cell * .04f, y - cell * .29f, cell * .025f, paint)
        canvas.drawCircle(x + cell * .04f, y - cell * .29f, cell * .025f, paint)
        paint.color = Color.rgb(15, 24, 34)
        canvas.drawCircle(x - cell * .035f, y - cell * .29f, cell * .012f, paint)
        canvas.drawCircle(x + cell * .045f, y - cell * .29f, cell * .012f, paint)
        drawWatchAccessory(canvas, x, y, cell, accessory)
        text(canvas, label, x, y + cell * .105f, cell * .065f, Color.WHITE, true)
        healthRatio?.let { ratio ->
            val barWidth = cell * .72f
            val barHeight = maxOf(1f, cell * .075f)
            val barTop = y - cell * .48f
            rect.set(x - barWidth / 2f, barTop, x + barWidth / 2f, barTop + barHeight)
            paint.color = Color.argb(210, 8, 14, 22)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
            val fillWidth = barWidth * ratio.coerceIn(0f, 1f)
            if (fillWidth > 0f) {
                rect.set(x - barWidth / 2f, barTop, x - barWidth / 2f + fillWidth, barTop + barHeight)
                paint.color = when {
                    ratio > .6f -> Color.rgb(85, 225, 145)
                    ratio > .3f -> Color.rgb(255, 190, 75)
                    else -> Color.rgb(255, 89, 92)
                }
                canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
            }
        }
    }

    private fun drawWatchAccessory(canvas: Canvas, x: Float, y: Float, cell: Float, accessory: WatchAccessory) {
        when (accessory) {
            WatchAccessory.NONE -> Unit
            WatchAccessory.CROWN -> {
                paint.color = Color.rgb(255, 205, 72)
                canvas.drawRect(x - cell * .13f, y - cell * .46f, x + cell * .13f, y - cell * .41f, paint)
                canvas.drawLine(x - cell * .12f, y - cell * .45f, x - cell * .1f, y - cell * .52f, paint)
                canvas.drawLine(x, y - cell * .45f, x, y - cell * .53f, paint)
                canvas.drawLine(x + cell * .12f, y - cell * .45f, x + cell * .1f, y - cell * .52f, paint)
            }
            WatchAccessory.HORNS -> {
                paint.color = Color.rgb(235, 83, 87)
                paint.strokeWidth = maxOf(1f, cell * .05f)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x - cell * .1f, y - cell * .34f, x - cell * .16f, y - cell * .5f, paint)
                canvas.drawLine(x + cell * .1f, y - cell * .34f, x + cell * .16f, y - cell * .5f, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            WatchAccessory.HALO -> {
                rect.set(x - cell * .15f, y - cell * .52f, x + cell * .15f, y - cell * .46f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, cell * .035f)
                paint.color = Color.rgb(255, 221, 100)
                canvas.drawOval(rect, paint)
                paint.style = Paint.Style.FILL
            }
            WatchAccessory.VISOR -> {
                paint.color = Color.rgb(83, 231, 236)
                canvas.drawRoundRect(x - cell * .13f, y - cell * .32f, x + cell * .13f,
                    y - cell * .26f, cell * .02f, cell * .02f, paint)
            }
            WatchAccessory.TOP_HAT -> {
                paint.color = Color.rgb(27, 35, 44)
                canvas.drawRect(x - cell * .15f, y - cell * .45f, x + cell * .15f, y - cell * .42f, paint)
                canvas.drawRoundRect(x - cell * .1f, y - cell * .57f, x + cell * .1f,
                    y - cell * .44f, cell * .025f, cell * .025f, paint)
            }
        }
    }

    private fun interpolatedPosition(
        x: Int,
        y: Int,
        fromX: Int,
        fromY: Int,
        startedAt: Long,
        now: Long
    ): Pair<Float, Float> {
        val progress = ((now - startedAt) / 170f).coerceIn(0f, 1f)
        return (fromX + (x - fromX) * progress) to (fromY + (y - fromY) * progress)
    }

    private fun drawWatchMenu(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        text(canvas, "WEAR BOMBER", cx, cy - diameter * .38f, diameter * .055f, Color.WHITE, true)
        text(canvas, "CHOOSE YOUR MATCH", cx, cy - diameter * .29f, diameter * .027f,
            Color.rgb(111, 226, 238), true)
        settingCard(canvas, cx, cy - diameter * .19f, diameter, "MODE",
            modes[mode], Color.rgb(77, 201, 220))
        settingCard(canvas, cx, cy - diameter * .07f, diameter, "MAP",
            "${selectedMap + 1}/${mapSizes.size}", Color.rgb(255, 198, 71))
        settingCard(canvas, cx, cy + diameter * .05f, diameter, "START",
            "TAP", Color.rgb(125, 239, 184))
        settingCard(canvas, cx, cy + diameter * .17f, diameter, "CREATE",
            "BOMBER", Color.rgb(255, 151, 68))
        settingCard(canvas, cx, cy + diameter * .29f, diameter, "SETTINGS",
            "TAP", Color.rgb(211, 183, 255))
        text(canvas, connectionStatus.take(24), cx, cy + diameter * .41f, diameter * .021f,
            Color.rgb(195, 207, 220), true)
    }

    private fun drawCharacterCreator(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        text(canvas, "BOMBER BUILDER", cx, cy - diameter * .41f, diameter * .04f, Color.WHITE, true)
        drawCreatorTab(canvas, cx - diameter * .18f, cy - diameter * .30f, diameter,
            "LOOK", creatorPage == 0)
        drawCreatorTab(canvas, cx + diameter * .18f, cy - diameter * .30f, diameter,
            "STATS", creatorPage == 1)

        if (creatorPage == 0) {
            drawPlayer(canvas, cx, cy - diameter * .14f, diameter * .2f,
                characterOutfit.color, "YOU", shielded = characterPerk == WatchPerk.SHIELD,
                accessory = characterAccessory)
            settingCard(canvas, cx, cy + diameter * .045f, diameter, "OUTFIT",
                characterOutfit.name, characterOutfit.color)
            settingCard(canvas, cx, cy + diameter * .17f, diameter, "ACCESSORY",
                characterAccessory.name.replace('_', ' '), Color.rgb(211, 183, 255))
            text(canvas, "TAP A ROW TO CYCLE", cx, cy + diameter * .31f, diameter * .018f,
                Color.rgb(242, 230, 204), true)
        } else {
            settingCard(canvas, cx, cy - diameter * .15f, diameter, "MAX HEALTH",
                "$characterMaxHealth HP", Color.rgb(125, 239, 184))
            settingCard(canvas, cx, cy - diameter * .02f, diameter, "SPEED",
                characterSpeed.name.replace('_', ' '), Color.rgb(77, 201, 220))
            settingCard(canvas, cx, cy + diameter * .11f, diameter, "STARTING PERK",
                characterPerk.name, Color.rgb(255, 151, 68))
            text(canvas, "TAP HP LEFT / RIGHT TO ADJUST", cx, cy + diameter * .27f,
                diameter * .017f, Color.rgb(242, 230, 204), true)
        }
        text(canvas, "BACK", cx, cy + diameter * .43f, diameter * .024f,
            Color.rgb(125, 239, 184), true)
    }

    private fun drawCreatorTab(canvas: Canvas, cx: Float, cy: Float, diameter: Float, label: String, selected: Boolean) {
        val color = if (selected) Color.rgb(255, 198, 71) else Color.rgb(173, 208, 228)
        text(canvas, label, cx, cy, diameter * .024f, color, true)
        if (selected) {
            paint.style = Paint.Style.STROKE
            paint.color = color
            paint.strokeWidth = maxOf(1f, diameter * .006f)
            canvas.drawLine(cx - diameter * .09f, cy + diameter * .025f,
                cx + diameter * .09f, cy + diameter * .025f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawJumpScare(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawColor(Color.rgb(225, 20, 38))
        paint.color = Color.rgb(255, 240, 220)
        canvas.drawCircle(cx - diameter * .16f, cy - diameter * .12f, diameter * .09f, paint)
        canvas.drawCircle(cx + diameter * .16f, cy - diameter * .12f, diameter * .09f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(cx - diameter * .16f, cy - diameter * .12f, diameter * .035f, paint)
        canvas.drawCircle(cx + diameter * .16f, cy - diameter * .12f, diameter * .035f, paint)
        text(canvas, "BOO!", cx, cy + diameter * .29f, diameter * .14f, Color.WHITE, true)
    }

    private fun drawNavigation(canvas: Canvas, cx: Float, cy: Float, diameter: Float) {
        val y = cy + diameter * .432f
        val radius = diameter * .047f
        val controls = listOf(
            Triple(cx - diameter * .36f, "MENU", Color.rgb(255, 198, 71)),
            Triple(cx - diameter * .12f, "MAP", Color.rgb(173, 208, 228)),
            Triple(cx + diameter * .12f, "LINK", Color.rgb(125, 239, 184)),
            Triple(cx + diameter * .36f, "SET", Color.rgb(211, 183, 255))
        )
        controls.forEach { (x, label, color) ->
            paint.color = Color.rgb(22, 34, 49)
            canvas.drawCircle(x, y - diameter * .008f, radius, paint)
            text(canvas, label, x, y + diameter * .005f, diameter * .017f, color, true)
        }
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
        settingCard(canvas, cx, cy + diameter * .215f, diameter, "JUMP-SCARE",
            if (jumpScaresEnabled) "ON" else "OFF", Color.rgb(255, 92, 111))
        settingCard(canvas, cx, cy + diameter * .33f, diameter, "TOUCH HAPTICS",
            if (hapticsEnabled) "ON" else "OFF", Color.rgb(174, 127, 255))
        text(canvas, "TAP A ROW TO CHANGE", cx, cy + diameter * .405f, diameter * .018f,
            Color.rgb(164, 180, 198), true)
        text(canvas, "BACK", cx, cy + diameter * .43f, diameter * .024f,
            Color.rgb(125, 239, 184), true)
    }

    private fun drawResults(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        text(canvas, resultTitle.take(20), cx, cy - diameter * .18f, diameter * .05f,
            Color.WHITE, true)
        text(canvas, resultMessage.take(28), cx, cy - diameter * .08f, diameter * .023f,
            Color.rgb(170, 197, 216))
        settingCard(canvas, cx, cy + diameter * .04f, diameter, "REPLAY", "TAP", Color.rgb(125, 239, 184))
        settingCard(canvas, cx, cy + diameter * .17f, diameter, "PLAY SOLO", "TAP", Color.rgb(77, 201, 220))
        settingCard(canvas, cx, cy + diameter * .30f, diameter, "MAIN MENU", "TAP",
            Color.rgb(125, 239, 184))
    }

    private fun settingCard(canvas: Canvas, cx: Float, cy: Float, diameter: Float, title: String, value: String, accent: Int) {
        val radius = diameter * .025f
        rect.set(cx - diameter * .37f, cy - diameter * .045f,
            cx + diameter * .37f, cy + diameter * .059f)
        paint.color = Color.argb(100, 8, 15, 20)
        canvas.drawRoundRect(rect, radius, radius, paint)
        rect.set(cx - diameter * .37f, cy - diameter * .052f,
            cx + diameter * .37f, cy + diameter * .052f)
        paint.color = Color.rgb(242, 230, 204)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, diameter * .005f)
        paint.color = Color.rgb(30, 42, 48)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        rect.set(cx - diameter * .37f, cy - diameter * .052f,
            cx - diameter * .35f, cy + diameter * .052f)
        paint.color = accent
        canvas.drawRoundRect(rect, radius * .4f, radius * .4f, paint)
        rect.set(cx + diameter * .115f, cy - diameter * .034f,
            cx + diameter * .33f, cy + diameter * .034f)
        canvas.drawRoundRect(rect, diameter * .018f, diameter * .018f, paint)
        text(canvas, title, cx - diameter * .3f, cy + diameter * .009f, diameter * .022f,
            Color.rgb(38, 49, 54), true, Paint.Align.LEFT)
        text(canvas, value, cx + diameter * .29f, cy + diameter * .009f, diameter * .021f,
            Color.rgb(31, 40, 45), true, Paint.Align.RIGHT)
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
                val swipeThreshold = min(width, height) * .09f
                if (screen == WatchScreen.GAME &&
                    (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold) &&
                    maxOf(abs(dx), abs(dy)) > minOf(abs(dx), abs(dy)) * 1.15f
                ) {
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
                if (screen == WatchScreen.MENU) {
                    handleMenuTap(event.y)
                    return true
                }
                if (screen == WatchScreen.SETTINGS) {
                    handleSettingsTap(event.y)
                    return true
                }
                if (screen == WatchScreen.CREATOR) {
                    handleCharacterCreatorTap(event.x, event.y)
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
                x < width * .26f -> {
                    removeCallbacks(monsterTick)
                    screen = WatchScreen.MENU
                }
                x < width * .5f -> {
                    selectedMap = (selectedMap + 1) % mapSizes.size
                    sendCommand("MAP:$selectedMap")
                    startRound()
                }
                x < width * .74f -> connect()
                else -> screen = WatchScreen.SETTINGS
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

    private fun handleMenuTap(y: Float) {
        val diameter = min(width, height).toFloat()
        val cy = height / 2f
        when {
            y in (cy - diameter * .24f)..(cy - diameter * .14f) ->
                mode = (mode + 1) % modes.size
            y in (cy - diameter * .12f)..(cy - diameter * .02f) -> {
                selectedMap = (selectedMap + 1) % mapSizes.size
                sendCommand("MAP:$selectedMap")
            }
            y in (cy + diameter * .0f)..(cy + diameter * .10f) -> {
                sendCommand("MODE:${modes[mode]}")
                startRound()
            }
            y in (cy + diameter * .11f)..(cy + diameter * .23f) -> screen = WatchScreen.CREATOR
            y in (cy + diameter * .24f)..(cy + diameter * .36f) -> screen = WatchScreen.SETTINGS
            y > cy + diameter * .39f -> connect()
        }
        touchFeedback()
        invalidate()
    }

    private fun handleCharacterCreatorTap(x: Float, y: Float) {
        val diameter = min(width, height).toFloat()
        val cy = height / 2f
        when {
            y in (cy - diameter * .35f)..(cy - diameter * .24f) -> {
                creatorPage = if (x < width / 2f) 0 else 1
            }
            creatorPage == 0 && y in (cy - diameter * .01f)..(cy + diameter * .10f) -> {
                characterOutfit = WatchOutfit.entries[(characterOutfit.ordinal + 1) % WatchOutfit.entries.size]
                saveCharacterProfile()
            }
            creatorPage == 0 && y in (cy + diameter * .115f)..(cy + diameter * .225f) -> {
                characterAccessory =
                    WatchAccessory.entries[(characterAccessory.ordinal + 1) % WatchAccessory.entries.size]
                saveCharacterProfile()
            }
            creatorPage == 1 && y in (cy - diameter * .205f)..(cy - diameter * .095f) -> {
                characterMaxHealth = (characterMaxHealth + if (x < width / 2f) -50 else 50)
                    .coerceIn(50, 1000)
                saveCharacterProfile()
            }
            creatorPage == 1 && y in (cy - diameter * .075f)..(cy + diameter * .035f) -> {
                characterSpeed = WatchSpeed.entries[(characterSpeed.ordinal + 1) % WatchSpeed.entries.size]
                saveCharacterProfile()
            }
            creatorPage == 1 && y in (cy + diameter * .055f)..(cy + diameter * .165f) -> {
                characterPerk = WatchPerk.entries[(characterPerk.ordinal + 1) % WatchPerk.entries.size]
                saveCharacterProfile()
            }
            y > cy + diameter * .37f -> screen = WatchScreen.MENU
        }
        touchFeedback()
        invalidate()
    }

    private fun saveCharacterProfile() {
        persistCharacterSettings()
        val playerIndex = if (modes[mode] == "SOLO") 0 else 1
        sendCommand(
            "CHARACTER:$playerIndex:$characterMaxHealth:${characterSpeed.name}:${characterOutfit.name}:" +
                "${characterAccessory.name}:${characterPerk.name}"
        )
    }

    private fun applyCharacterProfile(command: String) {
        val values = command.substringAfter(':').split(':')
        val playerIndex = values.getOrNull(0)?.toIntOrNull() ?: return
        if (playerIndex !in 0..1) return
        val maxHealth = values.getOrNull(1)?.toIntOrNull() ?: return
        if (maxHealth !in 50..1000 || maxHealth % 50 != 0) return
        val speed = WatchSpeed.entries.firstOrNull { it.name == values.getOrNull(2) } ?: return
        val outfit = WatchOutfit.entries.firstOrNull { it.name == values.getOrNull(3) } ?: return
        val accessory = WatchAccessory.entries.firstOrNull { it.name == values.getOrNull(4) } ?: return
        val perk = WatchPerk.entries.firstOrNull { it.name == values.getOrNull(5) } ?: return
        characterMaxHealth = maxHealth
        characterSpeed = speed
        characterOutfit = outfit
        characterAccessory = accessory
        characterPerk = perk
        playerHealth = playerHealth.coerceAtMost(effectiveMaximumHealth)
        persistCharacterSettings()
        invalidate()
    }

    private fun persistCharacterSettings() {
        watchSettings.edit()
            .putInt("character_max_health", characterMaxHealth)
            .putInt("character_outfit", characterOutfit.ordinal)
            .putInt("character_accessory", characterAccessory.ordinal)
            .putInt("character_speed", characterSpeed.ordinal)
            .putInt("character_perk", characterPerk.ordinal)
            .apply()
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
            y > cy + diameter * .25f -> {
                screen = WatchScreen.MENU
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
            y in (cy + diameter * .155f)..(cy + diameter * .275f) -> jumpScaresEnabled = !jumpScaresEnabled
            y in (cy + diameter * .27f)..(cy + diameter * .39f) -> hapticsEnabled = !hapticsEnabled
            y > cy + diameter * .37f -> screen = WatchScreen.MENU
        }
        watchSettings.edit()
            .putBoolean("effects_enabled", effectsEnabled)
            .putBoolean("high_contrast", highContrast)
            .putBoolean("full_power_up_names", showFullPowerUpNames)
            .putBoolean("jump_scares_enabled", jumpScaresEnabled)
            .putBoolean("haptics_enabled", hapticsEnabled)
            .apply()
        touchFeedback()
        invalidate()
    }

    private fun placeBomb() {
        expireDebuffs(SystemClock.uptimeMillis())
        if (screen != WatchScreen.GAME || deathAnimationStartedAt != 0L) return
        if (bombLocked) {
            lastPowerUpMessage = "BOMB LOCKED"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1400L
            invalidate()
            return
        }
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

    private fun explodeBomb(bomb: WatchBomb, now: Long, fromChain: Boolean = false) {
        val affectedCells = mutableSetOf(bomb.x to bomb.y)
        val newlyRevealed = mutableSetOf<Pair<Int, Int>>()
        addExplosion(bomb.x, bomb.y, now)
        emitParticles(bomb.x, bomb.y,
            intArrayOf(Color.rgb(255, 241, 161), Color.rgb(255, 164, 57), Color.rgb(255, 83, 39)),
            16, 2.6f)
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for ((dx, dy) in directions) {
            for (step in 1..blastRange) {
                val x = bomb.x + dx * step
                val y = bomb.y + dy * step
                if (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.STEEL) break
                affectedCells.add(x to y)
                if (board[y][x] == WatchTile.BRICK) {
                    board[y][x] = WatchTile.FLOOR
                    emitParticles(x, y,
                        intArrayOf(Color.rgb(218, 112, 78), Color.rgb(131, 61, 46), Color.rgb(240, 178, 126)),
                        7, 1.4f)
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.let {
                        it.hidden = false
                        it.lastMovedAt = now
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
        bombs.filter { (it.x to it.y) in affectedCells }.toList().forEach { chained ->
            bombs.remove(chained)
            if (!fromChain) sendCommand("CHAIN:${chained.x}:${chained.y}")
            post {
                if (screen == WatchScreen.GAME) {
                    explodeBomb(chained, SystemClock.uptimeMillis(), fromChain = true)
                    invalidate()
                }
            }
        }
        if (modes[mode] == "SPLEEF") {
            affectedCells.forEach { (x, y) -> if (x in board.indices && y in board.indices) board[y][x] = WatchTile.HOLE }
        }
        if (modes[mode] == "SOLO") {
            monsters.filter { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
                .forEach { emitParticles(it.x, it.y,
                    intArrayOf(Color.rgb(181, 231, 112), Color.rgb(108, 175, 86), Color.WHITE), 10, 1.6f) }
            monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
        }
        val hitPlayer = (playerX to playerY) in affectedCells
        val hitOpponent = modes[mode] != "SOLO" && (opponentX to opponentY) in affectedCells
        expireDebuffs(now)
        if (hitPlayer) touchFeedback()
        if (modes[mode] == "SPLEEF") {
            if (hitPlayer || hitOpponent) finishRound(when {
                hitPlayer && hitOpponent -> null
                hitPlayer -> 1
                else -> 0
            }, "The blast broke the floor under a player.")
        } else {
            if (hitPlayer) {
                if (shieldCharges > 0) shieldCharges-- else {
                    val fragile = WatchDebuff.FRAGILE in activeDebuffs && vulnerableHits > 0
                    playerHealth = (playerHealth - if (fragile) 100 else 50).coerceAtLeast(0)
                    if (vulnerableHits > 0) {
                        vulnerableHits--
                        if (vulnerableHits == 0) activeDebuffs.remove(WatchDebuff.FRAGILE)
                    }
                }
            }
            if (hitOpponent) {
                opponentHealth = (opponentHealth - 50).coerceAtLeast(0)
                if (vampireFlame) {
                    playerHealth = (playerHealth + 20).coerceAtMost(effectiveMaximumHealth)
                }
            }
            resolveHealthResult()
        }
        if (modes[mode] == "SOLO" && screen == WatchScreen.GAME) updateMonsters(affectedCells - newlyRevealed)
    }

    private fun move(dx: Int, dy: Int) {
        if (screen != WatchScreen.GAME || doorAnimationStartedAt != 0L || deathAnimationStartedAt != 0L) return
        expireDebuffs(SystemClock.uptimeMillis())
        if (WatchDebuff.STUN in activeDebuffs) {
            invalidate()
            return
        }
        val moveDx = if (WatchDebuff.REVERSE in activeDebuffs) -dx else dx
        val moveDy = if (WatchDebuff.REVERSE in activeDebuffs) -dy else dy
        val x = playerX + moveDx
        val y = playerY + moveDy
        if (modes[mode] == "SPLEEF" &&
            (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.HOLE)
        ) {
            finishRound(1, "You fell off the arena.")
            return
        }
        if (x !in board.indices || y !in board.indices) return
        if (modes[mode] == "SOLO" && board[y][x] == WatchTile.DOOR && !soloHasKey) {
            lastPowerUpMessage = "FIND THE KEY FIRST"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
            invalidate()
            return
        }
        val cooldown = characterSpeed.cooldown + if (WatchDebuff.SLOW in activeDebuffs) 220L else 0L
        if (SystemClock.uptimeMillis() - lastMoveAt < cooldown) return
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
            val previousX = playerX
            val previousY = playerY
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
            if (abs(playerX - previousX) + abs(playerY - previousY) == 1) {
                playerWalkFromX = previousX
                playerWalkFromY = previousY
            } else {
                playerWalkFromX = playerX
                playerWalkFromY = playerY
            }
            lastMoveAt = SystemClock.uptimeMillis()
            if (WatchDebuff.POISON in activeDebuffs) {
                playerHealth = (playerHealth - 5).coerceAtLeast(0)
            }
            if (modes[mode] == "SOLO" && board[playerY][playerX] == WatchTile.KEY) {
                board[playerY][playerX] = WatchTile.FLOOR
                soloHasKey = true
                emitParticles(playerX, playerY,
                    intArrayOf(Color.rgb(255, 224, 100), Color.rgb(255, 187, 61), Color.WHITE), 14, 1.7f)
                lastPowerUpMessage = "KEY FOUND · DOOR UNLOCKED"
                powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
            }
            droppedItems.remove(playerX to playerY)?.let(::collectPowerUp)
            if (modes[mode] == "SOLO") updateMonsters()
            if (modes[mode] == "SOLO" && board[playerY][playerX] == WatchTile.DOOR && soloHasKey) {
                enterSoloDoor()
            }
            if (modes[mode] == "RACE" && board[y][x] == WatchTile.GOAL) finishRound(0, "You reached the center.")
            resolveHealthResult()
        }
        sendCommand(if (dx < 0) "LEFT" else if (dx > 0) "RIGHT" else if (dy < 0) "UP" else "DOWN")
        invalidate()
    }

    private fun collectPowerUp(code: String) {
        val debuffEffect = WatchDebuff.fromCode(code)
        val particleColors = if (debuffEffect != null) {
            intArrayOf(debuffEffect.color, Color.WHITE)
        } else {
            intArrayOf(WatchPowerUp.fromCode(code)?.color ?: Color.WHITE, Color.WHITE)
        }
        emitParticles(playerX, playerY, particleColors, 8, 1.1f)
        val debuff = WatchDebuff.fromCode(code)
        if (debuff != null) {
            collectedDebuffs.add(debuff.code)
            val now = SystemClock.uptimeMillis()
            expireDebuffs(now)
            val alreadyActive = debuff in activeDebuffs
            if (debuff.durationMillis > 0L) activeDebuffs[debuff] = now + debuff.durationMillis
            when (debuff) {
                WatchDebuff.WEAK_FLAME -> if (!alreadyActive && blastRange > 1) {
                    blastRange--
                    reversibleDebuffs.add(debuff)
                }
                WatchDebuff.BOMB_LOCK -> bombLocked = true
                WatchDebuff.FRAGILE -> vulnerableHits = 2
                WatchDebuff.SHIELD_BREAK -> shieldCharges = 0
                WatchDebuff.DASH_DRAIN -> if (!alreadyActive && dashLevel > 0) {
                    dashLevel--
                    reversibleDebuffs.add(debuff)
                }
                WatchDebuff.CURSE -> playerHealth = (playerHealth - 25).coerceAtLeast(0)
                WatchDebuff.SLOW, WatchDebuff.REVERSE, WatchDebuff.STUN, WatchDebuff.POISON -> Unit
            }
            lastPowerUpMessage = "DEBUFF · ${if (showFullPowerUpNames) debuff.label else debuff.code}"
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
            WatchPowerUp.HEART ->
                playerHealth = (playerHealth + 50).coerceAtMost(effectiveMaximumHealth)
        }
        lastPowerUpMessage = if (showFullPowerUpNames) {
            "${powerUp.code}  ${powerUp.displayName}"
        } else {
            "${powerUp.code} PICKED UP"
        }
        powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
        if (jumpScaresEnabled && Random.nextInt(100) < 10) {
            jumpScareUntil = SystemClock.uptimeMillis() + 650L
            touchFeedback()
        }
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
        particles.clear()
        droppedItems.clear()
        bombCapacity = 1
        blastRange = 2
        shieldCharges = 0
        dashLevel = 0
        canKickBombs = false
        vampireFlame = false
        clusterBombs = false
        piercingFlame = false
        playerHealth = effectiveMaximumHealth
        opponentHealth = 150
        activeDebuffs.clear()
        reversibleDebuffs.clear()
        vulnerableHits = 0
        bombLocked = false
        collectedPowerUps.clear()
        collectedDebuffs.clear()
        powerUpMessageUntil = 0L
        when (characterPerk) {
            WatchPerk.NONE -> Unit
            WatchPerk.SHIELD -> shieldCharges = 1
            WatchPerk.KICK -> canKickBombs = true
            WatchPerk.PIERCE -> piercingFlame = true
        }
    }

    private fun startRound(seed: Long? = null) {
        val size = when (modes[mode]) {
            "SPLEEF" -> 13
            "RACE" -> 15
            "SOLO" -> soloBoardSize(soloLevel)
            else -> mapSizes[selectedMap]
        }
        board = createBoard(size, seed)
        if (modes[mode] == "RACE") {
            for (x in 1..7) board[1][x] = WatchTile.FLOOR
            for (y in 1..7) board[y][7] = WatchTile.FLOOR
            for (x in 7..13) board[13][x] = WatchTile.FLOOR
            for (y in 7..13) board[y][7] = WatchTile.FLOOR
            board[7][7] = WatchTile.GOAL
        } else if (modes[mode] != "SPLEEF" && modes[mode] != "SOLO" && selectedMap > 0) {
            board[2][1] = WatchTile.TELEPORT
            board[size - 3][size - 2] = WatchTile.TELEPORT
        }
        if (modes[mode] == "SOLO") setupSoloLevel()
        val remoteMatch = connectedToPhone && modes[mode] != "SOLO"
        playerX = if (remoteMatch) board.lastIndex - 1 else 1
        playerY = if (remoteMatch) board.lastIndex - 1 else 1
        opponentX = if (remoteMatch) 1 else board.lastIndex - 1
        opponentY = if (remoteMatch) 1 else board.lastIndex - 1
        playerWalkFromX = playerX
        playerWalkFromY = playerY
        opponentWalkFromX = opponentX
        opponentWalkFromY = opponentY
        opponentLastWalkAt = 0L
        resetRoundPowerUps()
        soloHasKey = false
        doorAnimationStartedAt = 0L
        deathAnimationStartedAt = 0L
        deathAnimationComplete = false
        deathPlayerIndex = -1
        lastMonsterHitAt = SystemClock.uptimeMillis()
        lastMoveAt = SystemClock.uptimeMillis() - 1_000L
        screen = WatchScreen.GAME
        removeCallbacks(monsterTick)
        if (modes[mode] == "SOLO" && !connectedToPhone) postDelayed(monsterTick, 500L)
        invalidate()
    }

    private fun soloBoardSize(level: Int): Int = when {
        level <= 3 -> 9
        level <= 6 -> 11
        level <= 8 -> 13
        else -> 15
    }

    private fun setupSoloLevel() {
        val last = board.lastIndex - 1
        val keyX = (last / 2).coerceIn(2, last - 1)
        for (x in 1..last) board[1][x] = WatchTile.FLOOR
        for (y in 1..last) board[y][last] = WatchTile.FLOOR
        board[1][keyX] = WatchTile.KEY
        board[last][last] = WatchTile.DOOR

        monsters.clear()
        val hiddenCount = (3 - soloLevel / 4).coerceAtLeast(1)
        val hidingPlaces = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == WatchTile.BRICK && x > 2 && y > 2 &&
                    x < last - 1 && y < last - 1
                ) x to y else null
            }
        }.shuffled().take(hiddenCount)
        hidingPlaces.forEach { (x, y) -> monsters.add(WatchMonster(x, y)) }
        if (soloLevel >= 4) {
            val visibleCount = (1 + (soloLevel - 4) / 2).coerceAtMost(4)
            val spawnSpots = board.indices.flatMap { y ->
                board[y].indices.mapNotNull { x ->
                    if (board[y][x] == WatchTile.FLOOR && x in 2 until last && y in 2 until last) x to y
                    else null
                }
            }.shuffled().take(visibleCount)
            val now = SystemClock.uptimeMillis()
            spawnSpots.forEach { (x, y) ->
                monsters.add(WatchMonster(x, y, hidden = false, lastMovedAt = now))
            }
        }
    }

    private fun finishRound(winner: Int?, message: String) {
        if (screen != WatchScreen.GAME) return
        val deadPlayer = when {
            playerHealth <= 0 -> 0
            modes[mode] != "SOLO" && opponentHealth <= 0 -> 1
            else -> -1
        }
        if (deadPlayer >= 0 && !deathAnimationComplete) {
            if (deathAnimationStartedAt == 0L) {
                deathPlayerIndex = deadPlayer
                deathAnimationStartedAt = SystemClock.uptimeMillis()
                val deathX = if (deadPlayer == 0) playerX else opponentX
                val deathY = if (deadPlayer == 0) playerY else opponentY
                emitParticles(deathX, deathY,
                    intArrayOf(Color.rgb(255, 105, 94), Color.rgb(255, 202, 117), Color.WHITE), 18, 1.8f)
                postDelayed({
                    if (screen == WatchScreen.GAME && deathAnimationStartedAt != 0L) {
                        deathAnimationComplete = true
                        finishRound(winner, message)
                    }
                }, 650L)
            }
            return
        }
        removeCallbacks(monsterTick)
        deathAnimationStartedAt = 0L
        deathPlayerIndex = -1
        resultTitle = when {
            modes[mode] == "SOLO" && winner == 0 -> "YOU WIN!"
            modes[mode] == "SOLO" -> "GAME OVER"
            winner == 0 -> "PLAYER 1 WINS"
            winner == 1 -> "PLAYER 2 WINS"
            else -> "DRAW"
        }
        resultMessage = message
        bombs.clear()
        particles.clear()
        screen = WatchScreen.RESULTS
        invalidate()
    }

    private fun resolveHealthResult() {
        if (modes[mode] == "SOLO") {
            if (playerHealth <= 0) finishRound(null, "The monsters defeated you.")
        } else if (playerHealth <= 0 || opponentHealth <= 0) {
            val winner = when {
                playerHealth <= 0 && opponentHealth <= 0 -> null
                playerHealth <= 0 -> 1
                else -> 0
            }
            finishRound(winner, "A player was eliminated.")
        }
    }

    private fun expireDebuffs(now: Long) {
        val expired = activeDebuffs.filterValues { it <= now }.keys
        expired.forEach { debuff ->
            activeDebuffs.remove(debuff)
            when (debuff) {
                WatchDebuff.BOMB_LOCK -> bombLocked = false
                WatchDebuff.FRAGILE -> vulnerableHits = 0
                else -> Unit
            }
            if (reversibleDebuffs.remove(debuff)) {
                when (debuff) {
                    WatchDebuff.WEAK_FLAME -> blastRange = (blastRange + 1).coerceAtMost(8)
                    WatchDebuff.DASH_DRAIN -> dashLevel = (dashLevel + 1).coerceAtMost(4)
                    else -> Unit
                }
            }
        }
    }

    private fun activeDebuffSummary(now: Long): String =
        activeDebuffs.entries.sortedBy { it.value }.take(3).joinToString(" ") { (debuff, expiresAt) ->
            val seconds = ((expiresAt - now).coerceAtLeast(0L) + 999L) / 1000L
            "${debuff.code} ${seconds}s"
        }

    private fun updateMonsters(affectedCells: Set<Pair<Int, Int>> = emptySet()) {
        if (screen != WatchScreen.GAME || connectedToPhone || doorAnimationStartedAt != 0L) return
        monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells }
        val now = SystemClock.uptimeMillis()
        monsters.filterNot { it.hidden }.forEach { monster ->
            val distance = kotlin.math.abs(monster.x - playerX) + kotlin.math.abs(monster.y - playerY)
            if (distance > 1 && now - monster.lastMovedAt >= 650L) {
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    .map { (dx, dy) -> monster.x + dx to monster.y + dy }
                    .filter { (x, y) ->
                        val tile = if (x in board.indices && y in board.indices) board[y][x] else WatchTile.STEEL
                        x in board.indices && y in board.indices &&
                            (tile == WatchTile.FLOOR || tile == WatchTile.KEY || tile == WatchTile.DOOR) &&
                            (x != playerX || y != playerY) &&
                            monsters.none { other -> other !== monster && other.x == x && other.y == y } &&
                            bombs.none { it.x == x && it.y == y }
                    }
                    .minByOrNull { (x, y) -> kotlin.math.abs(x - playerX) + kotlin.math.abs(y - playerY) }
                    ?.let { (x, y) ->
                        monster.x = x
                        monster.y = y
                        monster.lastMovedAt = now
                    }
            }
        }
        val nearestThreat = monsters.filterNot { it.hidden }
            .minByOrNull { kotlin.math.abs(it.x - playerX) + kotlin.math.abs(it.y - playerY) }
        if (nearestThreat != null &&
            kotlin.math.abs(nearestThreat.x - playerX) + kotlin.math.abs(nearestThreat.y - playerY) <= 1 &&
            now - lastMonsterHitAt >= 1_400L
        ) {
            lastMonsterHitAt = now
            playerHealth = (playerHealth - 6).coerceAtLeast(0)
            lastPowerUpMessage = "A MONSTER HIT YOU"
            powerUpMessageUntil = now + 900L
        }
        resolveHealthResult()
        invalidate()
    }

    private fun enterSoloDoor() {
        if (doorAnimationStartedAt != 0L) return
        doorAnimationStartedAt = SystemClock.uptimeMillis()
        emitParticles(playerX, playerY,
            intArrayOf(Color.rgb(255, 227, 119), Color.rgb(255, 182, 71), Color.WHITE), 18, 1.8f)
        lastPowerUpMessage = "DOOR UNLOCKED"
        powerUpMessageUntil = doorAnimationStartedAt + 850L
        if (!connectedToPhone) {
            postDelayed({
                if (screen != WatchScreen.GAME || doorAnimationStartedAt == 0L) return@postDelayed
                if (soloLevel >= 10) {
                    doorAnimationStartedAt = 0L
                    finishRound(0, "CAMPAIGN COMPLETE · ALL 10 LEVELS CLEARED")
                } else {
                    soloLevel++
                    doorAnimationStartedAt = 0L
                    startRound()
                }
            }, 850L)
        }
    }

    private fun cameraLeft(): Int = if (board.size > 9) (playerX - 4).coerceIn(0, board.size - 9) else 0
    private fun cameraTop(): Int = if (board.size > 9) (playerY - 4).coerceIn(0, board.size - 9) else 0

    private fun createBoard(size: Int, seed: Long? = null): Array<Array<WatchTile>> {
        val random = JavaRandom(seed ?: 0L)
        return Array(size) { y -> Array(size) { x ->
            when {
                modes[mode] == "SPLEEF" -> WatchTile.FLOOR
                x == 0 || y == 0 || x == size - 1 || y == size - 1 || (x % 2 == 0 && y % 2 == 0) -> WatchTile.STEEL
                random.nextInt(100) < (if (modes[mode] == "CARNAGE") 55 else 40) -> WatchTile.BRICK
                else -> WatchTile.FLOOR
            }
        } }.also { map ->
            map[1][1] = WatchTile.FLOOR
            map[1][2] = WatchTile.FLOOR
            map[2][1] = WatchTile.FLOOR
            map[size - 2][size - 2] = WatchTile.FLOOR
            map[size - 2][size - 3] = WatchTile.FLOOR
            map[size - 3][size - 2] = WatchTile.FLOOR
        }
    }

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
