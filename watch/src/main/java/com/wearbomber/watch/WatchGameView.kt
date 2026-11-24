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
import java.util.ArrayDeque
import java.util.Random as JavaRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private enum class WatchTile { FLOOR, STEEL, BRICK, SAND, TELEPORT, GOAL, HOLE, KEY, DOOR }
private enum class WatchScreen { MENU, GAME, SANDBOX, SETTINGS, RESULTS, CREATOR }
private enum class WatchSandboxTool { TILE, MONSTER, POWER_UP, PLAYER, ERASE }
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
    HEART("EX", "Extra Heart", Color.rgb(79, 224, 142), "+"),
    TRICK("TK", "Trick Bomb", Color.rgb(246, 93, 197), "☠");

    companion object {
        fun fromCode(code: String): WatchPowerUp? = entries.firstOrNull { it.code == code }
    }
}

private data class WatchBomb(
    var x: Int,
    var y: Int,
    val plantedAt: Long,
    val owner: Int = 1,
    val isTrick: Boolean = false,
    val id: Int = 0
)
private data class WatchFlag(
    val x: Int,
    val y: Int,
    val carrier: Int?,
    val baseX: Int = x,
    val baseY: Int = y
)
private data class WatchExplosion(
    val x: Int,
    val y: Int,
    val startedAt: Long,
    val range: Int,
    val piercing: Boolean,
    val powerUpEffects: Set<WatchPowerUp>
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
private data class WatchFlameTrail(val x: Int, val y: Int, val expiresAt: Long)

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
    private val modes = listOf(
        "CARNAGE", "CASUAL", "HARDCORE", "JUGGERNAUT", "RACE", "SPLEEF",
        "CAPTURE_THE_FLAG", "SOLO", "SANDBOX", "HELL"
    )
    private val mapSizes = listOf(9, 9, 9, 11, 11, 11, 15, 15, 13, 11, 15, 13)
    private var screen = WatchScreen.MENU
    private var mode = 0
    private var sandboxTool = WatchSandboxTool.TILE
    private var sandboxLocalMode = false
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
    private val isSinglePlayerMode: Boolean
        get() = modes[mode] == "SOLO" || modes[mode] == "SANDBOX"
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
        get() = when (modes[mode]) {
            "HARDCORE" -> 50
            "HELL" -> 100
            else -> characterMaxHealth
        }
    private var jumpScareUntil = 0L
    private var bombCapacity = 1
    private var blastRange = 2
    private var shieldCharges = 0
    private var dashLevel = 0
    private var kickLevel = 0
    private var fireTrailLevel = 0
    private var vampireLevel = 0
    private var clusterLevel = 0
    private var piercingLevel = 0
    private var playerHealth = characterMaxHealth
    private var opponentHealth = 150
    private var lastFlameDamageAt = 0L
    private var opponentLastFlameDamageAt = 0L
    private var opponentLastMonsterHitAt = 0L
    private var lastHellMonsterSpawnAt = 0L
    private var lastPowerUpMessage = ""
    private var powerUpMessageUntil = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastMoveAt = 0L
    private var vulnerableHits = 0
    private var bombLocked = false
    private val collectedPowerUps = linkedMapOf<String, Int>()
    private val collectedDebuffs = linkedSetOf<String>()
    private val activeDebuffs = mutableMapOf<WatchDebuff, Long>()
    private val reversibleDebuffs = mutableSetOf<WatchDebuff>()
    private var resultTitle = ""
    private var resultMessage = ""
    private val bombs = mutableListOf<WatchBomb>()
    private var nextTrickBombId = 1
    private val explosions = mutableListOf<WatchExplosion>()
    private val particles = mutableListOf<WatchParticle>()
    private val flameTrails = mutableMapOf<Pair<Int, Int>, WatchFlameTrail>()
    private val droppedItems = mutableMapOf<Pair<Int, Int>, String>()
    private var flags = mutableListOf<WatchFlag>()
    private val flagCaptures = intArrayOf(0, 0)
    private val powerUps = WatchPowerUp.entries.toList()
    private val monsterTick = object : Runnable {
        override fun run() {
            if (screen == WatchScreen.GAME &&
                (modes[mode] == "SOLO" || modes[mode] == "SANDBOX" || modes[mode] == "HELL") &&
                (!connectedToPhone || (modes[mode] == "SANDBOX" && sandboxLocalMode))
            ) {
                updateMonsters()
                if (screen != WatchScreen.GAME) return
                if (modes[mode] == "HELL" &&
                    SystemClock.uptimeMillis() - lastHellMonsterSpawnAt >= HELL_MONSTER_INTERVAL_MS
                ) {
                    lastHellMonsterSpawnAt = SystemClock.uptimeMillis()
                    spawnHellMonster()
                }
                postDelayed(this, 500L)
            }
        }
    }
    private val hellSandTick = object : Runnable {
        override fun run() {
            if (screen != WatchScreen.GAME || modes[mode] != "HELL" || connectedToPhone) return
            spawnHellSand()
            postDelayed(this, HELL_SAND_INTERVAL_MS)
        }
    }
    private val flameTick = object : Runnable {
        override fun run() {
            if (screen != WatchScreen.GAME) return
            updateFlameTrails()
            if (flameTrails.isNotEmpty()) postDelayed(this, 200L)
        }
    }
    private val trickBombTick = object : Runnable {
        override fun run() {
            if (screen != WatchScreen.GAME || connectedToPhone) return
            updateTrickBombs()
            if (bombs.any { it.isTrick }) postDelayed(this, TRICK_BOMB_STEP_MS)
        }
    }
    private val ctfBrickTick = object : Runnable {
        override fun run() {
            if (screen != WatchScreen.GAME || modes[mode] != "CAPTURE_THE_FLAG" || connectedToPhone) return
            spawnCaptureFlagBrick()
            postDelayed(this, CTF_BRICK_INTERVAL_MS)
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
        WatchScreen.SETTINGS, WatchScreen.RESULTS, WatchScreen.CREATOR, WatchScreen.SANDBOX -> {
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
                            'A' -> WatchTile.SAND
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
                playerX = if (isSinglePlayerMode) 1 else remoteStart
                playerY = 1
                if (!isSinglePlayerMode) playerY = remoteStart
                opponentX = 1
                opponentY = 1
                playerWalkFromX = playerX
                playerWalkFromY = playerY
                opponentWalkFromX = opponentX
                opponentWalkFromY = opponentY
                bombs.clear()
                explosions.clear()
                flameTrails.clear()
                droppedItems.clear()
                monsters.clear()
                resetRoundPowerUps()
                soloHasKey = false
                doorAnimationStartedAt = 0L
                screen = WatchScreen.GAME
                invalidate()
            }
            command.startsWith("SPAWNS:") -> {
                val positions = command.substringAfter(':').split(';').mapNotNull { entry ->
                    val coordinates = entry.split(',')
                    val x = coordinates.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val y = coordinates.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (x in board.indices && y in board.indices) x to y else null
                }
                if (positions.size == 2) {
                    opponentX = positions[0].first
                    opponentY = positions[0].second
                    if (isSinglePlayerMode) {
                        playerX = positions[0].first
                        playerY = positions[0].second
                    } else {
                        playerX = positions[1].first
                        playerY = positions[1].second
                    }
                    opponentWalkFromX = opponentX
                    opponentWalkFromY = opponentY
                    playerWalkFromX = playerX
                    playerWalkFromY = playerY
                    if (modes[mode] == "CAPTURE_THE_FLAG") {
                        flags = mutableListOf(
                            WatchFlag(opponentX, opponentY, null, opponentX, opponentY),
                            WatchFlag(playerX, playerY, null, playerX, playerY)
                        )
                    }
                    invalidate()
                }
            }
            command.startsWith("FLAGS:") -> {
                val values = command.substringAfter(':').split(':')
                flagCaptures[0] = values.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                flagCaptures[1] = values.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                flags = values.drop(2).take(2).mapIndexedNotNull { index, encoded ->
                    val parts = encoded.split(',')
                    val x = parts.getOrNull(0)?.toIntOrNull() ?: return@mapIndexedNotNull null
                    val y = parts.getOrNull(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
                    val carrier = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..1 }
                    val previous = flags.getOrNull(index)
                    if (x in board.indices && y in board.indices) {
                        WatchFlag(x, y, carrier, previous?.baseX ?: x, previous?.baseY ?: y)
                    } else null
                }.toMutableList()
                invalidate()
            }
            command.startsWith("BRICK:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                if (x in board.indices && y in board.indices && board[y][x] == WatchTile.FLOOR) {
                    board[y][x] = WatchTile.BRICK
                }
                invalidate()
            }
            command.startsWith("TRICK_START:") -> {
                val values = command.substringAfter(':').split(':')
                val id = values.getOrNull(0)?.toIntOrNull() ?: return
                val owner = values.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..1 } ?: return
                val x = values.getOrNull(2)?.toIntOrNull() ?: return
                val y = values.getOrNull(3)?.toIntOrNull() ?: return
                if (x in board.indices && y in board.indices && bombs.none { it.id == id }) {
                    bombs.add(WatchBomb(x, y, SystemClock.uptimeMillis(), owner, isTrick = true, id = id))
                    removeCallbacks(trickBombTick)
                    if (!connectedToPhone) postDelayed(trickBombTick, TRICK_BOMB_STEP_MS)
                }
                invalidate()
            }
            command.startsWith("TRICK_POS:") -> {
                val values = command.substringAfter(':').split(':')
                val id = values.getOrNull(0)?.toIntOrNull() ?: return
                val x = values.getOrNull(1)?.toIntOrNull() ?: return
                val y = values.getOrNull(2)?.toIntOrNull() ?: return
                bombs.firstOrNull { it.id == id && it.isTrick }?.let {
                    if (x in board.indices && y in board.indices) {
                        it.x = x
                        it.y = y
                    }
                }
                invalidate()
            }
            command.startsWith("TRICK_BOOM:") -> {
                val id = command.substringAfter(':').toIntOrNull() ?: return
                bombs.firstOrNull { it.id == id && it.isTrick }?.let {
                    bombs.remove(it)
                    explodeBomb(it, SystemClock.uptimeMillis())
                    invalidate()
                }
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
            command.startsWith("PLAYER_HP:") -> {
                val values = command.substringAfter(':').split(':')
                val playerIndex = values.getOrNull(0)?.toIntOrNull() ?: return
                val health = values.getOrNull(1)?.toIntOrNull() ?: return
                when (playerIndex) {
                    0 -> opponentHealth = health.coerceIn(0, 1000)
                    1 -> playerHealth = health.coerceIn(0, effectiveMaximumHealth)
                    else -> return
                }
                invalidate()
            }
            command.startsWith("SAND:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                if (x in board.indices && y in board.indices && board[y][x] == WatchTile.FLOOR) {
                    board[y][x] = WatchTile.SAND
                }
                invalidate()
            }
            command.startsWith("FLAME:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                val duration = values.getOrNull(2)?.toLongOrNull()?.coerceIn(1L, 15_000L) ?: return
                if (x in board.indices && y in board.indices) {
                    flameTrails[x to y] = WatchFlameTrail(x, y, SystemClock.uptimeMillis() + duration)
                    removeCallbacks(flameTick)
                    postDelayed(flameTick, 200L)
                }
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
                    isSinglePlayerMode && winner == 0 -> "YOU WIN!"
                    isSinglePlayerMode -> "GAME OVER"
                    winner == 1 -> "YOU WIN!"
                    winner == 0 -> "YOU LOSE"
                    else -> "DRAW"
                }
                resultMessage = parts.getOrNull(1).orEmpty().take(40)
                bombs.clear()
                particles.clear()
                removeCallbacks(monsterTick)
                removeCallbacks(ctfBrickTick)
                removeCallbacks(trickBombTick)
                removeCallbacks(hellSandTick)
                removeCallbacks(flameTick)
                deathAnimationStartedAt = 0L
                deathPlayerIndex = -1
                screen = WatchScreen.RESULTS
                invalidate()
            }
            command.startsWith("HOST_POS:") -> {
                val values = command.substringAfter(':').split(':')
                val x = values.getOrNull(0)?.toIntOrNull() ?: return
                val y = values.getOrNull(1)?.toIntOrNull() ?: return
                if (isSinglePlayerMode) {
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
                if (x in board.indices && y in board.indices &&
                    board[y][x] in setOf(WatchTile.BRICK, WatchTile.SAND)
                ) {
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
                if (isSinglePlayerMode) playerX to playerY else opponentX to opponentY,
                true, owner = 0
            )
            command == "WATCH_BOMB" -> placeRemoteBomb(playerX to playerY, true, owner = 1)
            command == "BOMB" -> placeRemoteBomb(
                if (isSinglePlayerMode) playerX to playerY else opponentX to opponentY,
                true, owner = if (isSinglePlayerMode) 0 else 0
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
        if (screen == WatchScreen.SANDBOX) {
            drawSandboxEditor(canvas)
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
        val gameStatus = when (modes[mode]) {
            "SOLO" -> "LEVEL $soloLevel/10  ·  ${if (soloHasKey) "KEY" else "FIND KEY"}"
            "SANDBOX" -> "WORKSHOP  ·  ${monsters.size} MONSTERS"
            "CAPTURE_THE_FLAG" -> "FLAGS  ${flagCaptures[0]} : ${flagCaptures[1]}"
            "HELL" -> "HELL · MONSTERS · 10× POWER-UPS"
            else -> "${modes[mode]}  ·  BOMBS ${bombs.size}/$bombCapacity"
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
        drawFlameTrails(canvas, left, top, cell, cameraX, cameraY)
        drawPowerUps(canvas, left, top, cell, cameraX, cameraY)
        if (modes[mode] == "CAPTURE_THE_FLAG") {
            flags.forEachIndexed { owner, flag ->
                if (flag.x - cameraX in 0..8 && flag.y - cameraY in 0..8) {
                    drawFlag(canvas, left + (flag.x - cameraX + .5f) * cell,
                        top + (flag.y - cameraY + .5f) * cell, cell, owner, flag.carrier)
                }
            }
        }
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
        if (!isSinglePlayerMode) {
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
                healthRatio = opponentHealth.toFloat() / (if (modes[mode] == "HELL") 100f else 150f)
            )
            canvas.restore()
        }
        monsters.filterNot { it.hidden }.forEach { monster ->
            drawPlayer(canvas, left + (monster.x - cameraX + .5f) * cell,
                top + (monster.y - cameraY + .5f) * cell, cell, Color.rgb(148, 202, 82), "M")
        }
        canvas.restore()

        val powerSummary = collectedPowerUps.entries.toList().takeLast(3).joinToString(" ") { (code, count) ->
            "$code$count"
        }
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
            flameTrails.isNotEmpty() || droppedItems.isNotEmpty() ||
            doorAnimationStartedAt != 0L || deathAnimationStartedAt != 0L ||
            SystemClock.uptimeMillis() < powerUpMessageUntil ||
            now - lastMoveAt in 0L..420L || now - opponentLastWalkAt in 0L..420L
        ) postInvalidateOnAnimation()
        if (activeDebuffs.isNotEmpty()) postInvalidateDelayed(250L)
    }

    private fun drawMenuBackground(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        canvas.drawColor(Color.rgb(5, 9, 17))
        paint.color = Color.argb(28, 23, 205, 190)
        canvas.drawCircle(width * .94f, height * .12f, diameter * .42f, paint)
        paint.color = Color.argb(25, 70, 101, 238)
        canvas.drawCircle(width * .04f, height * .88f, diameter * .46f, paint)
        paint.color = Color.argb(18, 207, 123, 80)
        canvas.drawCircle(width * .95f, height * .76f, diameter * .22f, paint)
        for (index in 0 until 24) {
            val x = ((index * 83 + 31) % 997) / 997f * width
            val y = ((index * 137 + 59) % 991) / 991f * height
            paint.color = Color.argb(28 + index % 4 * 8, 172, 210, 225)
            canvas.drawCircle(x, y, diameter * .002f, paint)
        }
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
        val pulse = 1f + .08f * sin(SystemClock.uptimeMillis() / 170f)
        canvas.save()
        canvas.scale(pulse, pulse, cx, cy)
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
        canvas.restore()
    }

    private fun drawFlameTrails(
        canvas: Canvas,
        left: Float,
        top: Float,
        cell: Float,
        cameraX: Int,
        cameraY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        flameTrails.values.forEach { flame ->
            val visibleX = flame.x - cameraX
            val visibleY = flame.y - cameraY
            if (visibleX !in 0..8 || visibleY !in 0..8 || flame.expiresAt <= now) return@forEach
            val remaining = ((flame.expiresAt - now) / 3_000f).coerceIn(0f, 1f)
            val flicker = sin(now / 75f + flame.x * 2f + flame.y)
            val cx = left + (visibleX + .5f) * cell
            val cy = top + (visibleY + .5f) * cell
            paint.color = Color.argb((110 * remaining).toInt(), 255, 54, 18)
            canvas.drawCircle(cx, cy, cell * (.42f + .025f * flicker), paint)
            paint.color = Color.argb((220 * remaining).toInt(), 255, 104, 24)
            rect.set(cx - cell * .25f, cy - cell * (.32f + .025f * flicker),
                cx + cell * .25f, cy + cell * .35f)
            canvas.drawOval(rect, paint)
            paint.color = Color.argb((220 * remaining).toInt(), 255, 219, 108)
            canvas.drawCircle(cx, cy + cell * .12f, cell * .1f, paint)
        }
    }

    private fun drawFlag(canvas: Canvas, cx: Float, cy: Float, cell: Float, owner: Int, carrier: Int?) {
        paint.color = Color.rgb(225, 228, 230)
        paint.strokeWidth = maxOf(1f, cell * .045f)
        canvas.drawLine(cx - cell * .18f, cy + cell * .3f, cx - cell * .18f, cy - cell * .3f, paint)
        paint.color = if (carrier != null) Color.rgb(255, 220, 100)
        else if (owner == 0) Color.rgb(70, 165, 255) else Color.rgb(255, 91, 111)
        canvas.drawRect(cx - cell * .16f, cy - cell * .28f, cx + cell * .25f,
            cy - cell * .05f, paint)
    }

    private fun drawTile(canvas: Canvas, tile: WatchTile, x: Float, y: Float, cell: Float, gridX: Int, gridY: Int) {
        paint.style = Paint.Style.FILL
        when (tile) {
            WatchTile.FLOOR -> {
                paint.color = if (highContrast) {
                    if ((gridX + gridY) % 2 == 0) Color.rgb(25, 45, 59) else Color.rgb(31, 54, 68)
                } else {
                    if ((gridX + gridY) % 2 == 0) Color.rgb(15, 30, 43) else Color.rgb(19, 37, 50)
                }
                canvas.drawRoundRect(cellRect, cell * .09f, cell * .09f, paint)
                paint.color = Color.argb(25, 130, 205, 204)
                canvas.drawRoundRect(x + cell * .1f, y + cell * .1f,
                    x + cell * .9f, y + cell * .9f, cell * .045f, cell * .045f, paint)
                paint.color = Color.argb(55, 124, 191, 194)
                canvas.drawCircle(x + cell * .18f, y + cell * .18f, cell * .035f, paint)
                canvas.drawCircle(x + cell * .82f, y + cell * .82f, cell * .035f, paint)
            }
            WatchTile.STEEL -> {
                paint.color = Color.rgb(36, 54, 70)
                canvas.drawRoundRect(cellRect, cell * .12f, cell * .12f, paint)
                rect.set(x + cell * .08f, y + cell * .07f, x + cell * .92f, y + cell * .92f)
                paint.color = Color.rgb(92, 123, 144)
                canvas.drawRoundRect(rect, cell * .1f, cell * .1f, paint)
                rect.set(x + cell * .15f, y + cell * .15f, x + cell * .85f, y + cell * .85f)
                paint.color = Color.rgb(53, 74, 91)
                canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
                paint.color = Color.argb(120, 191, 224, 235)
                paint.strokeWidth = maxOf(1f, cell * .025f)
                canvas.drawLine(x + cell * .2f, y + cell * .1f, x + cell * .8f,
                    y + cell * .1f, paint)
                paint.color = Color.rgb(174, 207, 219)
                canvas.drawCircle(x + cell * .2f, y + cell * .2f, cell * .035f, paint)
                canvas.drawCircle(x + cell * .8f, y + cell * .2f, cell * .035f, paint)
                canvas.drawCircle(x + cell * .2f, y + cell * .8f, cell * .035f, paint)
                canvas.drawCircle(x + cell * .8f, y + cell * .8f, cell * .035f, paint)
            }
            WatchTile.BRICK -> {
                paint.color = Color.rgb(91, 40, 39)
                canvas.drawRoundRect(cellRect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(173, 71, 54)
                canvas.drawRoundRect(x + cell * .06f, y + cell * .06f,
                    x + cell * .94f, y + cell * .92f, cell * .055f, cell * .055f, paint)
                paint.color = Color.rgb(107, 48, 43)
                canvas.drawRect(x + cell * .06f, y + cell * .48f, x + cell * .94f,
                    y + cell * .53f, paint)
                canvas.drawRect(x + cell * .47f, y + cell * .06f, x + cell * .52f,
                    y + cell * .48f, paint)
                canvas.drawRect(x + cell * .29f, y + cell * .53f, x + cell * .34f,
                    y + cell * .92f, paint)
                paint.color = Color.argb(90, 255, 177, 127)
                paint.strokeWidth = maxOf(1f, cell * .025f)
                canvas.drawLine(x + cell * .12f, y + cell * .12f, x + cell * .88f,
                    y + cell * .12f, paint)
            }
            WatchTile.SAND -> {
                paint.color = Color.rgb(112, 78, 40)
                canvas.drawRoundRect(cellRect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(204, 157, 83)
                canvas.drawRoundRect(x + cell * .06f, y + cell * .06f,
                    x + cell * .94f, y + cell * .92f, cell * .055f, cell * .055f, paint)
                paint.color = Color.argb(150, 247, 211, 139)
                canvas.drawLine(x + cell * .16f, y + cell * .28f,
                    x + cell * .84f, y + cell * .28f, paint)
                canvas.drawLine(x + cell * .16f, y + cell * .65f,
                    x + cell * .84f, y + cell * .65f, paint)
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
            paint.color = if (bomb.isTrick) Color.rgb(118, 43, 119) else Color.rgb(29, 34, 46)
            canvas.drawCircle(x, y, cell * .31f * pulse, paint)
            paint.color = Color.rgb(94, 105, 124)
            canvas.drawCircle(x - cell * .1f, y - cell * .1f, cell * .08f, paint)
            paint.color = if (age % 360L < 180L) Color.rgb(255, 92, 63) else Color.rgb(255, 203, 87)
            canvas.drawCircle(x + cell * .19f, y - cell * .25f, cell * .075f, paint)
            val fuse = if (bomb.isTrick) TRICK_BOMB_FUSE_MS else FUSE_MS
            text(canvas, "${((fuse - age).coerceAtLeast(0L) + 999L) / 1000L}",
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
        explosion.powerUpEffects.forEach { powerUp ->
            drawPowerUpExplosionEffect(canvas, powerUp, cx, cy, cell, progress, alpha)
        }
    }

    private fun drawPowerUpExplosionEffect(
        canvas: Canvas,
        powerUp: WatchPowerUp,
        cx: Float,
        cy: Float,
        cell: Float,
        progress: Float,
        alpha: Int
    ) {
        val phase = progress * (Math.PI.toFloat() * 2f)
        val pulse = .5f + .5f * sin(phase)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = maxOf(1f, cell * .045f)
        when (powerUp) {
            WatchPowerUp.FIRE -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 255, 91, 28)
                for (flame in -1..1) {
                    val x = cx + flame * cell * .13f
                    val height = cell * (.18f + .1f * sin(phase + flame))
                    rect.set(x - cell * .045f, cy - height, x + cell * .045f, cy + cell * .04f)
                    canvas.drawOval(rect, paint)
                }
            }
            WatchPowerUp.BOMB -> {
                paint.color = Color.argb(alpha, 255, 207, 72)
                canvas.drawCircle(cx, cy, cell * (.2f + progress * .3f), paint)
                canvas.drawCircle(cx, cy, cell * (.12f + (1f - progress) * .16f), paint)
            }
            WatchPowerUp.DASH -> {
                paint.color = Color.argb(alpha, 63, 227, 239)
                val offset = cell * (.08f + progress * .22f)
                for (side in -1..1) {
                    val y = cy + side * cell * .11f
                    canvas.drawLine(cx - offset - cell * .12f, y, cx - offset, y, paint)
                    canvas.drawLine(cx + offset, y, cx + offset + cell * .12f, y, paint)
                }
            }
            WatchPowerUp.SHIELD -> {
                paint.color = Color.argb(alpha, 92, 176, 255)
                canvas.drawCircle(cx, cy, cell * (.18f + progress * .34f), paint)
                paint.strokeWidth = maxOf(1f, cell * .025f)
                canvas.drawCircle(cx, cy, cell * (.12f + pulse * .08f), paint)
            }
            WatchPowerUp.KICK -> {
                paint.color = Color.argb(alpha, 197, 138, 255)
                val size = cell * (.16f + .04f * pulse)
                rect.set(cx - size, cy - size, cx + size, cy + size)
                canvas.save()
                canvas.rotate(45f + progress * 180f, cx, cy)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }
            WatchPowerUp.BLINK -> {
                paint.color = Color.argb(alpha, 225, 133, 255)
                val offset = cell * (.1f + progress * .3f)
                canvas.drawCircle(cx - offset, cy, cell * .11f * (1f - progress), paint)
                canvas.drawCircle(cx + offset, cy, cell * .11f * (1f - progress), paint)
                canvas.drawCircle(cx, cy, cell * (.12f + pulse * .04f), paint)
            }
            WatchPowerUp.VAMPIRE -> {
                paint.color = Color.argb(alpha, 255, 83, 132)
                for (drop in 0..2) {
                    val angle = phase + drop * (Math.PI.toFloat() * 2f / 3f)
                    val distance = cell * (.13f + progress * .23f)
                    val x = cx + cos(angle) * distance
                    val y = cy + sin(angle) * distance
                    canvas.drawCircle(x, y, cell * .045f, paint)
                    canvas.drawLine(x, y, x + cos(angle) * cell * .07f,
                        y + sin(angle) * cell * .07f, paint)
                }
            }
            WatchPowerUp.CLUSTER -> {
                paint.color = Color.argb(alpha, 255, 167, 66)
                val distance = cell * (.12f + progress * .27f)
                for (spark in 0..3) {
                    val angle = spark * (Math.PI.toFloat() / 2f) + phase * .35f
                    canvas.drawCircle(cx + cos(angle) * distance, cy + sin(angle) * distance,
                        cell * (.035f + .025f * (1f - progress)), paint)
                }
            }
            WatchPowerUp.PIERCE -> {
                paint.color = Color.argb(alpha, 255, 105, 83)
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    drawExplosionArrow(canvas, cx, cy, dx, dy, cell * (.2f + progress * .18f), cell * .08f)
                }
            }
            WatchPowerUp.HEART -> {
                paint.color = Color.argb(alpha, 111, 255, 166)
                val arm = cell * (.1f + pulse * .04f)
                canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
            }
            WatchPowerUp.TRICK -> {
                paint.color = Color.argb((alpha * (.55f + .45f * pulse)).toInt(), 242, 105, 215)
                canvas.drawCircle(cx, cy, cell * (.2f + progress * .24f), paint)
                canvas.drawLine(cx - cell * .1f, cy - cell * .1f,
                    cx + cell * .1f, cy + cell * .1f, paint)
                canvas.drawLine(cx + cell * .1f, cy - cell * .1f,
                    cx - cell * .1f, cy + cell * .1f, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawExplosionArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        dx: Int,
        dy: Int,
        distance: Float,
        headSize: Float
    ) {
        val tipX = cx + dx * distance
        val tipY = cy + dy * distance
        canvas.drawLine(cx, cy, tipX, tipY, paint)
        canvas.drawLine(tipX, tipY, tipX - dx * headSize - dy * headSize,
            tipY - dy * headSize + dx * headSize, paint)
        canvas.drawLine(tipX, tipY, tipX - dx * headSize + dy * headSize,
            tipY - dy * headSize - dx * headSize, paint)
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
            if (board[y][x] in setOf(WatchTile.BRICK, WatchTile.SAND) && !explosion.piercing) break
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
        paint.style = Paint.Style.FILL
        val walk = if (walking) sin(SystemClock.uptimeMillis() / 65f) else 0f
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawOval(x - cell * .22f, y + cell * .34f, x + cell * .22f, y + cell * .42f, paint)
        paint.color = Color.rgb(25, 34, 48)
        canvas.drawRoundRect(x + cell * .105f, y - cell * .08f,
            x + cell * .19f, y + cell * .17f, cell * .025f, cell * .025f, paint)
        paint.color = Color.argb(100, 255, 255, 255)
        canvas.drawRoundRect(x + cell * .12f, y - cell * .055f,
            x + cell * .14f, y + cell * .11f, cell * .01f, cell * .01f, paint)
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
        paint.color = Color.rgb(30, 42, 57)
        canvas.drawCircle(x - cell * (.22f - walk * .07f), y + cell * (.09f + walk * .025f),
            cell * .045f, paint)
        canvas.drawCircle(x + cell * (.22f + walk * .07f), y + cell * (.09f - walk * .025f),
            cell * .045f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        rect.set(x - cell * .15f, y - cell * .13f, x + cell * .15f, y + cell * .19f)
        paint.color = color
        canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, cell * .018f)
        paint.color = Color.argb(185, 9, 20, 31)
        canvas.drawRoundRect(rect, cell * .07f, cell * .07f, paint)
        paint.color = Color.argb(55, 255, 255, 255)
        canvas.drawRoundRect(x - cell * .11f, y - cell * .1f,
            x + cell * .1f, y + cell * .015f, cell * .035f, cell * .035f, paint)
        paint.color = Color.argb(155, 16, 29, 43)
        canvas.drawLine(x - cell * .075f, y - cell * .055f,
            x + cell * .075f, y + cell * .055f, paint)
        paint.color = Color.rgb(255, 211, 105)
        canvas.drawCircle(x + cell * .06f, y - cell * .045f, cell * .018f, paint)
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
        paint.color = Color.rgb(25, 34, 48)
        canvas.drawRoundRect(x - cell * .13f, y + cell * .31f,
            x - cell * .005f, y + cell * .405f, cell * .025f, cell * .025f, paint)
        canvas.drawRoundRect(x + cell * .005f, y + cell * .31f,
            x + cell * .13f, y + cell * .405f, cell * .025f, cell * .025f, paint)
        paint.color = Color.rgb(255, 198, 71)
        canvas.drawRect(x - cell * .105f, y + cell * .37f,
            x - cell * .025f, y + cell * .385f, paint)
        canvas.drawRect(x + cell * .025f, y + cell * .37f,
            x + cell * .105f, y + cell * .385f, paint)
        paint.color = Color.rgb(21, 31, 45)
        canvas.drawOval(x - cell * .13f, y - cell * .4f, x + cell * .13f, y - cell * .145f, paint)
        rect.set(x - cell * .11f, y - cell * .38f, x + cell * .11f, y - cell * .16f)
        paint.color = color
        canvas.drawOval(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, cell * .018f)
        paint.color = Color.argb(190, 9, 20, 31)
        canvas.drawOval(rect, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(215, 8, 24, 39)
        canvas.drawRoundRect(x - cell * .092f, y - cell * .327f,
            x + cell * .092f, y - cell * .235f, cell * .035f, cell * .035f, paint)
        paint.color = Color.argb(150, 125, 232, 255)
        rect.set(x - cell * .075f, y - cell * .315f, x + cell * .075f, y - cell * .295f)
        canvas.drawRoundRect(rect, cell * .025f, cell * .025f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x - cell * .04f, y - cell * .275f, cell * .026f, paint)
        canvas.drawCircle(x + cell * .04f, y - cell * .275f, cell * .026f, paint)
        paint.color = Color.rgb(15, 24, 34)
        canvas.drawCircle(x - cell * .035f, y - cell * .275f, cell * .012f, paint)
        canvas.drawCircle(x + cell * .045f, y - cell * .275f, cell * .012f, paint)
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
        text(canvas, "BOMBER ARENA", cx, cy - diameter * .39f, diameter * .052f, Color.WHITE, true)
        text(canvas, "PICK A MODE  ·  BUILD YOUR MAYHEM", cx, cy - diameter * .31f,
            diameter * .02f, Color.rgb(111, 226, 238), true)
        settingCard(canvas, cx, cy - diameter * .215f, diameter, "MODE",
            modes[mode], Color.rgb(77, 201, 220))
        settingCard(canvas, cx, cy - diameter * .10f, diameter, "ARENA",
            "${selectedMap + 1}/${mapSizes.size}", Color.rgb(255, 198, 71))
        settingCard(canvas, cx, cy + diameter * .02f, diameter, "START MATCH",
            "TAP", Color.rgb(125, 239, 184))
        settingCard(canvas, cx, cy + diameter * .13f, diameter, "SANDBOX",
            "BUILD ARENA", Color.rgb(255, 151, 68))
        settingCard(canvas, cx - diameter * .2f, cy + diameter * .265f, diameter * .55f,
            "BOMBER", "EDIT", Color.rgb(211, 183, 255))
        settingCard(canvas, cx + diameter * .2f, cy + diameter * .265f, diameter * .55f,
            "SETTINGS", "OPEN", Color.rgb(77, 201, 220))
        text(canvas, connectionStatus.take(26), cx, cy + diameter * .405f,
            diameter * .018f, Color.rgb(164, 182, 199), true)
    }

    private fun drawSandboxEditor(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        text(canvas, "SANDBOX WORKSHOP", cx, cy - diameter * .42f,
            diameter * .042f, Color.WHITE, true)
        text(canvas, "TAP THE ARENA TO PLACE THINGS", cx, cy - diameter * .355f,
            diameter * .019f, Color.rgb(164, 182, 199), true)

        val boardSize = diameter * .58f
        val cell = boardSize / 9f
        val left = cx - boardSize / 2f
        val top = cy - boardSize / 2f
        val cameraX = cameraLeft()
        val cameraY = cameraTop()
        drawBoard(canvas, left, top, cell, cameraX, cameraY)
        drawPowerUps(canvas, left, top, cell, cameraX, cameraY)
        monsters.filterNot { it.hidden }.forEach { monster ->
            val visibleX = monster.x - cameraX
            val visibleY = monster.y - cameraY
            if (visibleX in 0..8 && visibleY in 0..8) {
                drawPlayer(canvas, left + (visibleX + .5f) * cell,
                    top + (visibleY + .5f) * cell, cell * .78f,
                    Color.rgb(148, 202, 82), "M")
            }
        }
        val playerVisibleX = playerX - cameraX
        val playerVisibleY = playerY - cameraY
        if (playerVisibleX in 0..8 && playerVisibleY in 0..8) {
            drawPlayer(canvas, left + (playerVisibleX + .5f) * cell,
                top + (playerVisibleY + .5f) * cell, cell * .78f,
                characterOutfit.color, "P", accessory = characterAccessory)
        }

        settingCard(canvas, cx, cy + diameter * .345f, diameter, "TOOL · TAP TO CYCLE",
            sandboxTool.name.replace('_', ' '), Color.rgb(111, 226, 238))
        drawSandboxAction(canvas, cx - diameter * .20f, cy + diameter * .445f,
            diameter, "BACK", Color.rgb(52, 67, 87))
        drawSandboxAction(canvas, cx + diameter * .20f, cy + diameter * .445f,
            diameter, "PLAY", Color.rgb(24, 153, 117))
    }

    private fun drawSandboxAction(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        diameter: Float,
        label: String,
        color: Int
    ) {
        rect.set(cx - diameter * .17f, cy - diameter * .035f,
            cx + diameter * .17f, cy + diameter * .035f)
        paint.color = Color.rgb(15, 25, 38)
        canvas.drawRoundRect(rect, diameter * .025f, diameter * .025f, paint)
        paint.color = color
        canvas.drawRoundRect(cx - diameter * .16f, cy - diameter * .038f,
            cx + diameter * .16f, cy + diameter * .032f,
            diameter * .025f, diameter * .025f, paint)
        text(canvas, label, cx, cy + diameter * .008f,
            diameter * .019f, Color.WHITE, true)
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
        paint.color = Color.argb(115, 0, 0, 0)
        canvas.drawRoundRect(rect, radius, radius, paint)
        rect.set(cx - diameter * .37f, cy - diameter * .052f,
            cx + diameter * .37f, cy + diameter * .052f)
        paint.color = Color.rgb(15, 25, 38)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, diameter * .005f)
        paint.color = Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        rect.set(cx - diameter * .37f, cy - diameter * .052f,
            cx - diameter * .35f, cy + diameter * .052f)
        paint.color = accent
        canvas.drawRoundRect(rect, radius * .4f, radius * .4f, paint)
        rect.set(cx + diameter * .115f, cy - diameter * .034f,
            cx + diameter * .33f, cy + diameter * .034f)
        paint.color = Color.rgb(25, 39, 55)
        canvas.drawRoundRect(rect, diameter * .018f, diameter * .018f, paint)
        text(canvas, title, cx - diameter * .3f, cy + diameter * .009f, diameter * .022f,
            Color.rgb(223, 233, 242), true, Paint.Align.LEFT)
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
                    handleMenuTap(event.x, event.y)
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
                if (screen == WatchScreen.SANDBOX) {
                    handleSandboxEditorTap(event.x, event.y)
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
                    removeCallbacks(ctfBrickTick)
                    removeCallbacks(trickBombTick)
                    screen = if (modes[mode] == "SANDBOX") WatchScreen.SANDBOX else WatchScreen.MENU
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

    private fun handleMenuTap(x: Float, y: Float) {
        val diameter = min(width, height).toFloat()
        val cy = height / 2f
        when {
            y in (cy - diameter * .27f)..(cy - diameter * .16f) ->
                mode = (mode + 1) % modes.size
            y in (cy - diameter * .15f)..(cy - diameter * .05f) -> {
                selectedMap = (selectedMap + 1) % mapSizes.size
                sendCommand("MAP:$selectedMap")
            }
            y in (cy - diameter * .035f)..(cy + diameter * .075f) -> {
                sendCommand("MODE:${modes[mode]}")
                startRound()
            }
            y in (cy + diameter * .075f)..(cy + diameter * .19f) -> {
                mode = modes.indexOf("SANDBOX")
                sendCommand("MODE:SANDBOX")
                startRound()
            }
            y in (cy + diameter * .23f)..(cy + diameter * .30f) -> {
                if (x < width / 2f) screen = WatchScreen.CREATOR else screen = WatchScreen.SETTINGS
            }
            y > cy + diameter * .37f -> connect()
        }
        touchFeedback()
        invalidate()
    }

    private fun handleSandboxEditorTap(x: Float, y: Float) {
        val diameter = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val boardSize = diameter * .58f
        val left = cx - boardSize / 2f
        val top = cy - boardSize / 2f
        val cell = boardSize / 9f
        val cameraX = cameraLeft()
        val cameraY = cameraTop()
        when {
            y > cy + diameter * .405f -> {
                if (x < width / 2f) {
                    screen = WatchScreen.MENU
                } else {
                    startSandboxGame()
                }
            }
            y > cy + diameter * .30f -> {
                sandboxTool = WatchSandboxTool.entries[
                    (sandboxTool.ordinal + 1) % WatchSandboxTool.entries.size
                ]
            }
            x in left..(left + boardSize) && y in top..(top + boardSize) -> {
                val gridX = ((x - left) / cell).toInt() + cameraX
                val gridY = ((y - top) / cell).toInt() + cameraY
                if (gridX !in board.indices || gridY !in board.indices) return
                val position = gridX to gridY
                when (sandboxTool) {
                    WatchSandboxTool.TILE -> {
                        val tiles = listOf(
                            WatchTile.FLOOR, WatchTile.STEEL, WatchTile.BRICK, WatchTile.SAND,
                            WatchTile.TELEPORT, WatchTile.HOLE, WatchTile.GOAL, WatchTile.KEY, WatchTile.DOOR
                        )
                        val next = (tiles.indexOf(board[gridY][gridX]) + 1) % tiles.size
                        board[gridY][gridX] = tiles[next]
                    }
                    WatchSandboxTool.MONSTER -> {
                        if (!monsters.removeAll { it.x == gridX && it.y == gridY }) {
                            board[gridY][gridX] = WatchTile.FLOOR
                            monsters.add(WatchMonster(gridX, gridY, hidden = false))
                        }
                    }
                    WatchSandboxTool.POWER_UP -> {
                        val current = WatchPowerUp.fromCode(droppedItems[position] ?: "")
                        val next = current?.let { powerUps.indexOf(it) + 1 } ?: 0
                        droppedItems[position] = powerUps[next % powerUps.size].code
                    }
                    WatchSandboxTool.PLAYER -> {
                        board[gridY][gridX] = WatchTile.FLOOR
                        playerX = gridX
                        playerY = gridY
                        playerWalkFromX = gridX
                        playerWalkFromY = gridY
                    }
                    WatchSandboxTool.ERASE -> {
                        board[gridY][gridX] = WatchTile.FLOOR
                        monsters.removeAll { it.x == gridX && it.y == gridY }
                        droppedItems.remove(position)
                    }
                }
            }
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
        val playerIndex = if (isSinglePlayerMode) 0 else 1
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
        if (!sandboxLocalMode) sendCommand("BOMB")
    }

    private fun placeRemoteBomb(
        position: Pair<Int, Int>,
        fromOtherDevice: Boolean = false,
        owner: Int = if (isSinglePlayerMode) 0 else if (position == (playerX to playerY)) 1 else 0
    ) {
        if (screen != WatchScreen.GAME || bombs.any { it.x == position.first && it.y == position.second }) return
        if (fromOtherDevice || bombs.size < bombCapacity) {
            val bomb = WatchBomb(position.first, position.second, SystemClock.uptimeMillis(), owner)
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

    private fun deployTrickBomb() {
        val owner = if (isSinglePlayerMode) 0 else 1
        val bomb = WatchBomb(playerX, playerY, SystemClock.uptimeMillis(),
            owner, isTrick = true, id = nextTrickBombId++)
        bombs.add(bomb)
        removeCallbacks(trickBombTick)
        postDelayed(trickBombTick, TRICK_BOMB_STEP_MS)
        invalidate()
    }

    private fun updateTrickBombs() {
        if (connectedToPhone || screen != WatchScreen.GAME) return
        val now = SystemClock.uptimeMillis()
        bombs.filter { it.isTrick }.toList().forEach { bomb ->
            if (now - bomb.plantedAt >= TRICK_BOMB_FUSE_MS) {
                if (bombs.remove(bomb)) explodeBomb(bomb, now)
                return@forEach
            }
            val target = if (!isSinglePlayerMode && bomb.owner == 0) {
                opponentX to opponentY
            } else {
                playerX to playerY
            }
            if (bomb.x == target.first && bomb.y == target.second) return@forEach
            val next = nextTrickBombStep(bomb, target) ?: return@forEach
            bomb.x = next.first
            bomb.y = next.second
        }
        invalidate()
    }

    private fun nextTrickBombStep(bomb: WatchBomb, target: Pair<Int, Int>): Pair<Int, Int>? {
        val start = bomb.x to bomb.y
        val previous = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>?>()
        val pending = ArrayDeque<Pair<Int, Int>>()
        val directions = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        pending.addLast(start)
        previous[start] = null
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == target) break
            for ((dx, dy) in directions) {
                val next = current.first + dx to current.second + dy
                if (next.first !in board.indices || next.second !in board.indices) continue
                val tile = board[next.second][next.first]
                if (next in previous || tile == WatchTile.STEEL || tile == WatchTile.BRICK ||
                    tile == WatchTile.SAND ||
                    tile == WatchTile.HOLE ||
                    bombs.any { it !== bomb && it.x == next.first && it.y == next.second }
                ) continue
                previous[next] = current
                pending.addLast(next)
            }
        }
        if (target !in previous) return null
        var step = target
        while (previous[step] != start) step = previous[step] ?: return null
        return step
    }

    private fun spawnCaptureFlagBrick() {
        val candidates = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] != WatchTile.FLOOR ||
                    abs(playerX - x) + abs(playerY - y) <= 1 ||
                    abs(opponentX - x) + abs(opponentY - y) <= 1 ||
                    flags.any {
                        abs(it.x - x) + abs(it.y - y) <= 1 ||
                            abs(it.baseX - x) + abs(it.baseY - y) <= 1
                    } ||
                    bombs.any { it.x == x && it.y == y } ||
                    droppedItems.containsKey(x to y)
                ) null else x to y
            }
        }
        candidates.randomOrNull()?.let { (x, y) ->
            board[y][x] = WatchTile.BRICK
            invalidate()
        }
    }

    private fun addExplosion(
        x: Int,
        y: Int,
        startedAt: Long,
        range: Int = blastRange,
        piercing: Boolean = piercingLevel > 0,
        isTrick: Boolean = false
    ) {
        if (effectsEnabled) {
            val powerUpEffects = collectedPowerUps.keys.mapNotNull { WatchPowerUp.fromCode(it) }
                .filter { it != WatchPowerUp.TRICK }
                .toMutableSet()
            if (isTrick) powerUpEffects.add(WatchPowerUp.TRICK)
            explosions.add(WatchExplosion(x, y, startedAt, range, piercing, powerUpEffects))
        }
    }

    private fun explodeBomb(bomb: WatchBomb, now: Long, fromChain: Boolean = false) {
        val affectedCells = mutableSetOf(bomb.x to bomb.y)
        val newlyRevealed = mutableSetOf<Pair<Int, Int>>()
        addExplosion(bomb.x, bomb.y, now, isTrick = bomb.isTrick)
        emitParticles(bomb.x, bomb.y,
            intArrayOf(Color.rgb(255, 241, 161), Color.rgb(255, 164, 57), Color.rgb(255, 83, 39)),
            16, 2.6f)
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for ((dx, dy) in directions) {
            var bricksPierced = 0
            for (step in 1..blastRange) {
                val x = bomb.x + dx * step
                val y = bomb.y + dy * step
                if (x !in board.indices || y !in board.indices || board[y][x] == WatchTile.STEEL) break
                affectedCells.add(x to y)
                if (board[y][x] == WatchTile.BRICK || board[y][x] == WatchTile.SAND) {
                    board[y][x] = WatchTile.FLOOR
                    emitParticles(x, y,
                        intArrayOf(Color.rgb(218, 112, 78), Color.rgb(131, 61, 46), Color.rgb(240, 178, 126)),
                        7, 1.4f)
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.let {
                        it.hidden = false
                        it.lastMovedAt = now
                        newlyRevealed.add(x to y)
                    }
                    val dropChance = if (modes[mode] == "HELL") 100 else 55
                    if (!connectedToPhone && Random.nextInt(100) < dropChance) {
                        droppedItems[x to y] = if (modes[mode] != "HELL" && Random.nextInt(100) < 25) {
                            WatchDebuff.entries.random().code
                        } else {
                            powerUps.random().code
                        }
                    }
                    if (piercingLevel <= bricksPierced) break
                    bricksPierced++
                }
            }
        }
        if (clusterLevel > 0) {
            for ((dx, dy) in directions) {
                for (distance in 2..(clusterLevel + 1)) {
                    val x = bomb.x + dx * distance
                    val y = bomb.y + dy * distance
                    if (x in board.indices && y in board.indices && board[y][x] != WatchTile.STEEL) {
                        addExplosion(x, y, now, range = 1, isTrick = bomb.isTrick)
                        affectedCells.add(x to y)
                    }
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
            if (!connectedToPhone && Random.nextInt(100) < 35) {
                val candidates = board.indices.flatMap { y ->
                    board[y].indices.mapNotNull { x ->
                        if (board[y][x] == WatchTile.FLOOR &&
                            x != playerX && y != playerY &&
                            x != opponentX && y != opponentY &&
                            bombs.none { it.x == x && it.y == y } &&
                            !droppedItems.containsKey(x to y)
                        ) x to y else null
                    }
                }
                candidates.randomOrNull()?.let { (x, y) ->
                    droppedItems[x to y] = if (Random.nextInt(100) < 25) {
                        WatchDebuff.entries.random().code
                    } else {
                        powerUps.random().code
                    }
                }
            }
        }
        if (isSinglePlayerMode) {
            monsters.filter { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
                .forEach { emitParticles(it.x, it.y,
                    intArrayOf(Color.rgb(181, 231, 112), Color.rgb(108, 175, 86), Color.WHITE), 10, 1.6f) }
            monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
        }
        val hitPlayer = (playerX to playerY) in affectedCells
        val hitOpponent = !isSinglePlayerMode && (opponentX to opponentY) in affectedCells
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
                if (vampireLevel > 0) {
                    playerHealth = (playerHealth + 20 * vampireLevel).coerceAtMost(effectiveMaximumHealth)
                }
            }
            resolveHealthResult()
        }
        if ((modes[mode] == "SOLO" || modes[mode] == "SANDBOX" || modes[mode] == "HELL") &&
            screen == WatchScreen.GAME
        ) updateMonsters(affectedCells - newlyRevealed)
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
        if (modes[mode] != "SPLEEF" && board[y][x] == WatchTile.HOLE) {
            finishRound(0, "You fell through a trapdoor.")
            return
        }
        if (modes[mode] == "SOLO" && board[y][x] == WatchTile.DOOR && !soloHasKey) {
            lastPowerUpMessage = "FIND THE KEY FIRST"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
            invalidate()
            return
        }
        val cooldown = characterSpeed.cooldown + if (WatchDebuff.SLOW in activeDebuffs) 220L else 0L
        if (SystemClock.uptimeMillis() - lastMoveAt < cooldown) return
        val blockingBomb = bombs.firstOrNull { it.x == x && it.y == y }
        var canMove = board[y][x] != WatchTile.STEEL &&
            board[y][x] != WatchTile.BRICK && board[y][x] != WatchTile.SAND
        if (blockingBomb != null) {
            if (kickLevel == 0) {
                canMove = false
            } else {
                var pushX = x
                var pushY = y
                for (step in 1..kickLevel) {
                    pushX += moveDx
                    pushY += moveDy
                    if (pushX !in board.indices || pushY !in board.indices ||
                        board[pushY][pushX] in setOf(
                            WatchTile.STEEL, WatchTile.BRICK, WatchTile.SAND, WatchTile.HOLE
                        ) ||
                        bombs.any { it !== blockingBomb && it.x == pushX && it.y == pushY } ||
                        (!isSinglePlayerMode && pushX == opponentX && pushY == opponentY)
                    ) {
                        canMove = false
                        break
                    }
                }
                if (canMove) {
                    blockingBomb.x = pushX
                    blockingBomb.y = pushY
                }
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
            if ((playerX to playerY) in flameTrails) lastFlameDamageAt = SystemClock.uptimeMillis()
            if (!connectedToPhone && fireTrailLevel > 0) {
                createFlameTrail(previousX to previousY, fireTrailLevel)
            }
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
            if (isSinglePlayerMode || modes[mode] == "HELL") updateMonsters()
            if (modes[mode] == "SOLO" && board[playerY][playerX] == WatchTile.DOOR && soloHasKey) {
                enterSoloDoor()
            }
            if (modes[mode] == "RACE" && board[y][x] == WatchTile.GOAL) finishRound(0, "You reached the center.")
            resolveHealthResult()
        }
        if (!sandboxLocalMode) {
            sendCommand(if (dx < 0) "LEFT" else if (dx > 0) "RIGHT" else if (dy < 0) "UP" else "DOWN")
        }
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
        collectedPowerUps[code] = (collectedPowerUps[code] ?: 0) + 1
        when (powerUp) {
            WatchPowerUp.FIRE -> {
                blastRange = (blastRange + 1).coerceAtMost(8)
                fireTrailLevel = (fireTrailLevel + 1).coerceAtMost(5)
            }
            WatchPowerUp.BOMB -> bombCapacity = (bombCapacity + 1).coerceAtMost(5)
            WatchPowerUp.DASH -> dashLevel = (dashLevel + 1).coerceAtMost(4)
            WatchPowerUp.SHIELD -> shieldCharges = (shieldCharges + 1).coerceAtMost(3)
            WatchPowerUp.KICK -> kickLevel = (kickLevel + 1).coerceAtMost(5)
            WatchPowerUp.BLINK -> {
                val destination = randomSafeFloor()
                playerX = destination.first
                playerY = destination.second
            }
            WatchPowerUp.VAMPIRE -> vampireLevel = (vampireLevel + 1).coerceAtMost(5)
            WatchPowerUp.CLUSTER -> clusterLevel = (clusterLevel + 1).coerceAtMost(5)
            WatchPowerUp.PIERCE -> piercingLevel = (piercingLevel + 1).coerceAtMost(5)
            WatchPowerUp.HEART ->
                playerHealth = (playerHealth + 50).coerceAtMost(effectiveMaximumHealth)
            WatchPowerUp.TRICK -> if (!connectedToPhone) deployTrickBomb()
        }
        lastPowerUpMessage = if (showFullPowerUpNames) {
            "${powerUp.code}  ${powerUp.displayName}"
        } else {
            "${powerUp.code} PICKED UP"
        }
        powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
        if (jumpScaresEnabled && Random.nextInt(100) < 30) {
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
        kickLevel = 0
        fireTrailLevel = 0
        vampireLevel = 0
        clusterLevel = 0
        piercingLevel = 0
        playerHealth = effectiveMaximumHealth
        opponentHealth = if (modes[mode] == "HELL") 100 else 150
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
            WatchPerk.KICK -> kickLevel = 1
            WatchPerk.PIERCE -> piercingLevel = 1
        }
    }

    private fun startRound(seed: Long? = null) {
        sandboxLocalMode = false
        val size = when (modes[mode]) {
            "SPLEEF" -> 13
            "RACE" -> 15
            "SOLO" -> soloBoardSize(soloLevel)
            else -> mapSizes[selectedMap]
        }
        board = createBoard(size, seed)
        if (modes[mode] == "SANDBOX") {
            for (y in 1 until board.lastIndex) for (x in 1 until board.lastIndex) {
                if (x % 2 != 0 || y % 2 != 0) board[y][x] = WatchTile.FLOOR
            }
        }
        if (modes[mode] == "RACE") {
            for (x in 1..7) board[1][x] = WatchTile.FLOOR
            for (y in 1..7) board[y][7] = WatchTile.FLOOR
            for (x in 7..13) board[13][x] = WatchTile.FLOOR
            for (y in 7..13) board[y][7] = WatchTile.FLOOR
            board[7][7] = WatchTile.GOAL
        } else if (modes[mode] != "SPLEEF" && modes[mode] != "SOLO" && modes[mode] != "SANDBOX") {
            if (modes[mode] == "HELL") applyHellTwists(seed ?: 0L)
            else applyMapTwists(seed ?: 0L)
        }
        monsters.clear()
        if (modes[mode] == "SOLO") setupSoloLevel()
        val spawnPositions = chooseSpawnPositions()
        opponentX = spawnPositions.first.first
        opponentY = spawnPositions.first.second
        val playerSpawn = if (isSinglePlayerMode) spawnPositions.first else spawnPositions.second
        playerX = playerSpawn.first
        playerY = playerSpawn.second
        playerWalkFromX = playerX
        playerWalkFromY = playerY
        opponentWalkFromX = opponentX
        opponentWalkFromY = opponentY
        opponentLastWalkAt = 0L
        resetRoundPowerUps()
        flameTrails.clear()
        flagCaptures.fill(0)
        flags = if (modes[mode] == "CAPTURE_THE_FLAG") {
            mutableListOf(WatchFlag(opponentX, opponentY, null), WatchFlag(playerX, playerY, null))
        } else mutableListOf()
        nextTrickBombId = 1
        soloHasKey = false
        doorAnimationStartedAt = 0L
        deathAnimationStartedAt = 0L
        deathAnimationComplete = false
        deathPlayerIndex = -1
        lastMonsterHitAt = SystemClock.uptimeMillis()
        opponentLastMonsterHitAt = lastMonsterHitAt
        lastFlameDamageAt = 0L
        opponentLastFlameDamageAt = 0L
        lastHellMonsterSpawnAt = lastMonsterHitAt
        lastMoveAt = SystemClock.uptimeMillis() - 1_000L
        screen = if (modes[mode] == "SANDBOX") WatchScreen.SANDBOX else WatchScreen.GAME
        removeCallbacks(monsterTick)
        removeCallbacks(ctfBrickTick)
        removeCallbacks(trickBombTick)
        removeCallbacks(hellSandTick)
        removeCallbacks(flameTick)
        if (modes[mode] == "HELL" && !connectedToPhone) setupHellMonsters()
        if (modes[mode] == "SOLO" && !connectedToPhone) postDelayed(monsterTick, 500L)
        if (modes[mode] == "HELL" && !connectedToPhone) {
            postDelayed(monsterTick, 500L)
            postDelayed(hellSandTick, HELL_SAND_INTERVAL_MS)
        }
        if (modes[mode] == "CAPTURE_THE_FLAG" && !connectedToPhone) {
            postDelayed(ctfBrickTick, CTF_BRICK_INTERVAL_MS)
        }
        if (bombs.any { it.isTrick } && !connectedToPhone) {
            postDelayed(trickBombTick, TRICK_BOMB_STEP_MS)
        }
        invalidate()
    }

    private fun startSandboxGame() {
        sandboxLocalMode = true
        screen = WatchScreen.GAME
        lastMoveAt = SystemClock.uptimeMillis() - 1_000L
        removeCallbacks(monsterTick)
        if (monsters.isNotEmpty()) postDelayed(monsterTick, 500L)
        invalidate()
    }

    private fun chooseSpawnPositions(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val floors = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == WatchTile.FLOOR) x to y else null
            }
        }
        val first = floors.randomOrNull() ?: (1 to 1)
        val second = floors.filter { (x, y) ->
            abs(x - first.first) + abs(y - first.second) >= MIN_SPAWN_DISTANCE
        }.randomOrNull() ?: floors.maxByOrNull { (x, y) ->
            abs(x - first.first) + abs(y - first.second)
        } ?: first
        return first to second
    }

    private fun applyMapTwists(seed: Long) {
        val last = board.lastIndex
        if (selectedMap > 0) {
            board[2][1] = WatchTile.TELEPORT
            board[last - 2][last - 1] = WatchTile.TELEPORT
        }
        if (selectedMap == 9 || selectedMap == 11) {
            val middle = board.size / 2
            board[1][middle] = WatchTile.TELEPORT
            board[last - 1][middle] = WatchTile.TELEPORT
        }
        val trapdoorCount = when (selectedMap) {
            8 -> 4
            10 -> 7
            11 -> 5
            else -> 0
        }
        if (trapdoorCount == 0) return
        val random = JavaRandom(seed xor 0x5EEDL)
        board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (x in 2 until last - 1 && y in 2 until last - 1 &&
                    board[y][x] == WatchTile.FLOOR
                ) x to y else null
            }
        }.shuffled(Random(random.nextLong())).take(trapdoorCount).forEach { (x, y) ->
            board[y][x] = WatchTile.HOLE
        }
    }

    private fun applyHellTwists(seed: Long) {
        applyMapTwists(seed)
        val last = board.lastIndex
        val middle = board.size / 2
        for ((x, y) in listOf(
            1 to 2, (last - 1) to (last - 2),
            middle to 1, middle to (last - 1)
        )) {
            board[y][x] = WatchTile.TELEPORT
        }
        val random = JavaRandom(seed xor 0x48454C4CL)
        board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (x in 2 until last - 1 && y in 2 until last - 1 &&
                    board[y][x] == WatchTile.FLOOR
                ) x to y else null
            }
        }.shuffled(Random(random.nextLong())).take(12).forEach { (x, y) ->
            board[y][x] = WatchTile.HOLE
        }
    }

    private fun setupHellMonsters() {
        val protected = setOf(playerX to playerY, opponentX to opponentY)
        val hidingPlaces = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == WatchTile.BRICK &&
                    protected.none { abs(it.first - x) + abs(it.second - y) <= 2 }
                ) x to y else null
            }
        }.shuffled().take(2)
        hidingPlaces.forEach { (x, y) -> monsters.add(WatchMonster(x, y)) }
        repeat(2) { spawnHellMonster() }
    }

    private fun spawnHellMonster() {
        val positions = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == WatchTile.FLOOR &&
                    abs(playerX - x) + abs(playerY - y) > 2 &&
                    abs(opponentX - x) + abs(opponentY - y) > 2 &&
                    monsters.none { it.x == x && it.y == y } &&
                    bombs.none { it.x == x && it.y == y }
                ) x to y else null
            }
        }
        positions.randomOrNull()?.let { (x, y) ->
            monsters.add(WatchMonster(x, y, hidden = false, lastMovedAt = SystemClock.uptimeMillis()))
            invalidate()
        }
    }

    private fun spawnHellSand() {
        val positions = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == WatchTile.FLOOR &&
                    (x != playerX || y != playerY) &&
                    (x != opponentX || y != opponentY) &&
                    bombs.none { it.x == x && it.y == y } &&
                    monsters.none { it.x == x && it.y == y } &&
                    !droppedItems.containsKey(x to y) &&
                    !flameTrails.containsKey(x to y) &&
                    flags.none { it.x == x && it.y == y }
                ) x to y else null
            }
        }
        positions.randomOrNull()?.let { (x, y) ->
            board[y][x] = WatchTile.SAND
            if (Random.nextInt(100) < HELL_MONSTER_CHANCE) monsters.add(WatchMonster(x, y))
            invalidate()
        }
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
            !isSinglePlayerMode && opponentHealth <= 0 -> 1
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
            isSinglePlayerMode && winner == 0 -> "YOU WIN!"
            isSinglePlayerMode -> "GAME OVER"
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
        if (isSinglePlayerMode) {
            if (playerHealth <= 0) finishRound(null, "The monsters defeated you.")
        } else if (playerHealth <= 0 || opponentHealth <= 0) {
            val winner = when {
                playerHealth <= 0 && opponentHealth <= 0 -> null
                playerHealth <= 0 -> 0
                else -> 1
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
        val localSandbox = modes[mode] == "SANDBOX" && sandboxLocalMode
        if (screen != WatchScreen.GAME || (connectedToPhone && !localSandbox) ||
            doorAnimationStartedAt != 0L
        ) return
        monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells }
        val now = SystemClock.uptimeMillis()
        val targets = if (isSinglePlayerMode) {
            listOf(1 to (playerX to playerY))
        } else {
            listOf(1 to (playerX to playerY), 0 to (opponentX to opponentY)).filter {
                if (it.first == 1) playerHealth > 0 else opponentHealth > 0
            }
        }
        monsters.filterNot { it.hidden }.forEach { monster ->
            val target = targets.minByOrNull {
                abs(monster.x - it.second.first) + abs(monster.y - it.second.second)
            }?.second ?: return@forEach
            val distance = abs(monster.x - target.first) + abs(monster.y - target.second)
            if (distance > 1 && now - monster.lastMovedAt >= 650L) {
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    .map { (dx, dy) -> monster.x + dx to monster.y + dy }
                    .filter { (x, y) ->
                        val tile = if (x in board.indices && y in board.indices) board[y][x] else WatchTile.STEEL
                        x in board.indices && y in board.indices &&
                            (tile == WatchTile.FLOOR || tile == WatchTile.KEY || tile == WatchTile.DOOR) &&
                            (x != target.first || y != target.second) &&
                            monsters.none { other -> other !== monster && other.x == x && other.y == y } &&
                            bombs.none { it.x == x && it.y == y }
                    }
                    .minByOrNull { (x, y) -> abs(x - target.first) + abs(y - target.second) }
                    ?.let { (x, y) ->
                        monster.x = x
                        monster.y = y
                        monster.lastMovedAt = now
                    }
            }
        }
        for ((playerIndex, position) in targets) {
            val nearMonster = monsters.any {
                    !it.hidden && abs(it.x - position.first) + abs(it.y - position.second) <= 1
            }
            val lastHitAt = if (playerIndex == 1) lastMonsterHitAt else opponentLastMonsterHitAt
            if (nearMonster && now - lastHitAt >= 1_400L) {
                    if (playerIndex == 1) {
                        lastMonsterHitAt = now
                        playerHealth = (playerHealth - 6).coerceAtLeast(0)
                    } else {
                        opponentLastMonsterHitAt = now
                        opponentHealth = (opponentHealth - 6).coerceAtLeast(0)
                    }
                    lastPowerUpMessage = if (isSinglePlayerMode) "A MONSTER HIT YOU"
                        else "A MONSTER HIT PLAYER ${playerIndex + 1}"
                    powerUpMessageUntil = now + 900L
            }
        }
        resolveHealthResult()
        invalidate()
    }

    private fun createFlameTrail(position: Pair<Int, Int>, level: Int) {
        val (x, y) = position
        if (x !in board.indices || y !in board.indices ||
            board[y][x] !in setOf(WatchTile.FLOOR, WatchTile.TELEPORT, WatchTile.GOAL)
        ) return
        val duration = FIRE_TRAIL_BASE_MS + level * FIRE_TRAIL_STACK_MS
        flameTrails[position] = WatchFlameTrail(x, y, SystemClock.uptimeMillis() + duration)
        removeCallbacks(flameTick)
        postDelayed(flameTick, 200L)
        invalidate()
    }

    private fun updateFlameTrails() {
        val now = SystemClock.uptimeMillis()
        flameTrails.entries.removeAll { it.value.expiresAt <= now }
        if (!connectedToPhone) {
            val targets = if (isSinglePlayerMode) {
                listOf(Triple(1, playerX to playerY, playerHealth))
            } else {
                listOf(
                    Triple(1, playerX to playerY, playerHealth),
                    Triple(0, opponentX to opponentY, opponentHealth)
                )
            }
            for ((playerIndex, position, health) in targets) {
                    val flameDamageAt = if (playerIndex == 1) lastFlameDamageAt else opponentLastFlameDamageAt
                    if (position !in flameTrails) {
                        if (playerIndex == 1) lastFlameDamageAt = 0L else opponentLastFlameDamageAt = 0L
                        continue
                    }
                    if (flameDamageAt == 0L) {
                        if (playerIndex == 1) lastFlameDamageAt = now else opponentLastFlameDamageAt = now
                    } else if (now - flameDamageAt >= FIRE_TRAIL_DAMAGE_INTERVAL_MS) {
                        if (playerIndex == 1) {
                            lastFlameDamageAt = now
                            playerHealth = (health - FIRE_TRAIL_DAMAGE).coerceAtLeast(0)
                        } else {
                            opponentLastFlameDamageAt = now
                            opponentHealth = (health - FIRE_TRAIL_DAMAGE).coerceAtLeast(0)
                        }
                        resolveHealthResult()
                    }
            }
        }
        if (flameTrails.isNotEmpty()) invalidate()
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
                random.nextInt(100) < (
                    if (modes[mode] == "CARNAGE" || modes[mode] == "HELL") 55 else 40
                ) -> WatchTile.BRICK
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
        private const val TRICK_BOMB_FUSE_MS = 5_000L
        private const val TRICK_BOMB_STEP_MS = 450L
        private const val CTF_BRICK_INTERVAL_MS = 5_000L
        private const val HELL_SAND_INTERVAL_MS = 5_000L
        private const val HELL_MONSTER_INTERVAL_MS = 20_000L
        private const val HELL_MONSTER_CHANCE = 10
        private const val FIRE_TRAIL_BASE_MS = 2_000L
        private const val FIRE_TRAIL_STACK_MS = 1_000L
        private const val FIRE_TRAIL_DAMAGE_INTERVAL_MS = 1_000L
        private const val FIRE_TRAIL_DAMAGE = 10
        private const val MIN_SPAWN_DISTANCE = 6
        private const val EXPLOSION_MS = 520L
    }
}
