package com.wearbomber.phone

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
import kotlin.math.min
import kotlin.random.Random

private enum class GameMode(val title: String, val subtitle: String) {
    CARNAGE("Carnage", "85% drops · full power-up set"),
    CASUAL("Casual", "45% drops · classic power-ups"),
    HARDCORE("Hardcore", "1 hit · fast fuses · no shields"),
    JUGGERNAUT("Juggernaut", "2,250 HP · crushes soft bricks"),
    RACE("Race to Center", "Reach the gold tile at (7,7)"),
    SPLEEF("Spleef", "No walls · blast away the floor"),
    CAPTURE_THE_FLAG("Capture the Flag", "Steal the enemy flag and bring it home"),
    SOLO("Solo", "Collect keys, unlock doors, and clear 10 levels"),
    SANDBOX("Sandbox", "Build an arena, add monsters, and test your setup"),
    HELL("Hell", "1 life · 100 HP · 10× power-up drops")
}
private enum class Screen { LOBBY, MODE_SELECT, LEVELS, GAME, CUSTOMIZER, BUILDER, SETTINGS, HELP, RESULTS }
private enum class Tile { FLOOR, STEEL, BRICK, SAND, TELEPORT, GOAL, HOLE, KEY, DOOR }
private enum class BuilderTool { TILE, MONSTER, POWER_UP, PLAYER, ERASE }
private enum class SpeedTier(val cooldown: Long) { SLOW(680), NORMAL(425), FAST(270), TURBO_DASH(160) }
private const val MOVE_REPEAT_INTERVAL = 110L
private const val SCREEN_TRANSITION_DURATION = 180L
private const val BOMB_FUSE_MS = 3_000L
private const val TRICK_BOMB_FUSE_MS = 5_000L
private const val TRICK_BOMB_STEP_MS = 450L
private const val CTF_BRICK_INTERVAL_MS = 5_000L
private const val MIN_SPAWN_DISTANCE = 6
private const val FLAGS_TO_WIN = 3
private const val SOLO_LEVEL_COUNT = 10
private const val SOLO_DOOR_ANIMATION_MS = 850L
private const val SOLO_DEATH_ANIMATION_MS = 650L
private const val HELL_SAND_INTERVAL_MS = 5_000L
private const val HELL_MONSTER_INTERVAL_MS = 20_000L
private const val HELL_MONSTER_CHANCE = 10
private const val FIRE_TRAIL_BASE_MS = 2_000L
private const val FIRE_TRAIL_STACK_MS = 1_000L
private const val FIRE_TRAIL_DAMAGE_INTERVAL_MS = 1_000L
private const val FIRE_TRAIL_DAMAGE = 10
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
    HEART("EX", "Extra Heart", Color.rgb(79, 224, 142), "+"),
    TRICK("TK", "Trick Bomb", Color.rgb(246, 93, 197), "☠");

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
private data class FlameTrail(val x: Int, val y: Int, val expiresAt: Long)

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
    var fireTrailLevel: Int = 0,
    var kickLevel: Int = 0,
    var vampireLevel: Int = 0,
    var clusterLevel: Int = 0,
    var piercingLevel: Int = 0,
    val collectedPowerUps: MutableMap<String, Int> = mutableMapOf(),
    val collectedDebuffs: MutableSet<String> = mutableSetOf(),
    val activeDebuffs: MutableMap<Debuff, Long> = mutableMapOf(),
    val reversibleDebuffs: MutableSet<Debuff> = mutableSetOf(),
    var lastWalkAt: Long = 0L,
    var walkFromX: Int = x,
    var walkFromY: Int = y,
    var vulnerableHits: Int = 0,
    var bombLocked: Boolean = false,
    var spawnX: Int = x,
    var spawnY: Int = y,
    var lastFlameDamageAt: Long = 0L,
    var lastMonsterHitAt: Long = 0L
)

private data class Bomb(
    var x: Int,
    var y: Int,
    val owner: Int,
    val plantedAt: Long,
    val isTrick: Boolean = false,
    val id: Int = 0
)
private data class CaptureFlag(
    val owner: Int,
    val baseX: Int,
    val baseY: Int,
    var x: Int = baseX,
    var y: Int = baseY,
    var carrier: Int? = null
)
private data class BlastEffect(val x: Int, val y: Int, val range: Int, val startedAt: Long)
private data class GameParticle(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
    val color: Int,
    val size: Float,
    val bornAt: Long,
    val lifetime: Long
)
private data class Monster(
    var x: Int,
    var y: Int,
    var hidden: Boolean = true,
    var lastMovedAt: Long = 0L
)

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
    private var builderTool = BuilderTool.TILE
    private val builderMonsters = mutableListOf<Monster>()
    private val builderItems = mutableMapOf<Pair<Int, Int>, String>()
    private var builderSpawn = 1 to 1
    private var board: Array<Array<Tile>> = emptyBoard(9)
    private var players = mutableListOf(Player(1, 1, 150, specs[0]), Player(7, 7, 150, specs[1]))
    private var activePlayer = 0
    private var handlingRemoteCommand = false
    private var raceWon = false
    private var flags = mutableListOf<CaptureFlag>()
    private val flagCaptures = intArrayOf(0, 0)
    private var nextTrickBombId = 1
    private var resultTitle = ""
    private var resultSubtitle = ""
    private var bombs = mutableListOf<Bomb>()
    private val monsters = mutableListOf<Monster>()
    private val blastEffects = mutableListOf<BlastEffect>()
    private val particles = mutableListOf<GameParticle>()
    private val flameTrails = mutableMapOf<Pair<Int, Int>, FlameTrail>()
    private val droppedItems = mutableMapOf<Pair<Int, Int>, String>()
    private var showFullPowerUpNames = false
    private var jumpScaresEnabled = true
    private var jumpScareUntil = 0L
    private var lastPowerUpMessage = ""
    private var powerUpMessageUntil = 0L
    private var connectionStatus = "Bluetooth not connected"
    private var mapSeed = 0L
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
    private val monsterTick = object : Runnable {
        override fun run() {
            if (screen == Screen.GAME &&
                (selectedMode == GameMode.SOLO || selectedMode == GameMode.SANDBOX ||
                    selectedMode == GameMode.HELL)
            ) {
                updateMonsters()
                if (screen != Screen.GAME) return
                if (selectedMode == GameMode.HELL &&
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
            if (screen != Screen.GAME || selectedMode != GameMode.HELL) return
            spawnHellSand()
            postDelayed(this, HELL_SAND_INTERVAL_MS)
        }
    }
    private val flameTick = object : Runnable {
        override fun run() {
            if (screen != Screen.GAME) return
            updateFlameTrails()
            if (flameTrails.isNotEmpty()) postDelayed(this, 200L)
        }
    }
    private val trickBombTick = object : Runnable {
        override fun run() {
            if (screen != Screen.GAME) return
            updateTrickBombs()
            if (bombs.any { it.isTrick }) postDelayed(this, TRICK_BOMB_STEP_MS)
        }
    }
    private val ctfBrickTick = object : Runnable {
        override fun run() {
            if (screen != Screen.GAME || selectedMode != GameMode.CAPTURE_THE_FLAG) return
            spawnCaptureFlagBrick()
            postDelayed(this, CTF_BRICK_INTERVAL_MS)
        }
    }
    private val maps = listOf(
        "Classic Yard" to 9, "Twin Teleporters" to 9, "Crossfire" to 9, "Blue Maze" to 11,
        "Purple Circuit" to 11, "Labyrinth" to 11, "Long Road" to 15, "Twisted Center" to 15,
        "Trapdoor Garden" to 13, "Rift Run" to 11, "Cracked Crown" to 15, "Phantom Crossing" to 13
    )
    private var soloLevel = 1
    private var maxUnlockedSoloLevel = context.applicationContext
        .getSharedPreferences("wearbomber_progress", Context.MODE_PRIVATE)
        .getInt("max_solo_level", 1)
        .coerceIn(1, SOLO_LEVEL_COUNT)
    private var soloHasKey = false
    private var soloDoorTransitionStartedAt = 0L
    private var lastHellMonsterSpawnAt = 0L
    private var deathAnimationStartedAt = 0L
    private var deathAnimationComplete = false
    private var deathPlayerIndex = -1
    private val powerUps = PowerUp.entries.toList()
    fun setConnectionStatus(status: String) {
        connectionStatus = status
        invalidate()
    }

    private fun deployTrickBomb(owner: Int) {
        if (owner !in players.indices) return
        val player = players[owner]
        val bomb = Bomb(player.x, player.y, owner, SystemClock.uptimeMillis(),
            isTrick = true, id = nextTrickBombId++)
        bombs.add(bomb)
        sendToWatch("TRICK_START:${bomb.id}:$owner:${bomb.x}:${bomb.y}")
        removeCallbacks(trickBombTick)
        postDelayed(trickBombTick, TRICK_BOMB_STEP_MS)
        invalidate()
    }

    private fun updateTrickBombs() {
        val now = SystemClock.uptimeMillis()
        bombs.filter { it.isTrick }.toList().forEach { bomb ->
            if (now - bomb.plantedAt >= TRICK_BOMB_FUSE_MS) {
                if (bombs.contains(bomb)) {
                    sendToWatch("TRICK_BOOM:${bomb.id}")
                    detonate(bomb)
                }
                return@forEach
            }
            val target = players[bomb.owner]
            if (bomb.x == target.x && bomb.y == target.y) return@forEach
            val next = nextTrickBombStep(bomb, target.x to target.y) ?: return@forEach
            bomb.x = next.first
            bomb.y = next.second
            sendToWatch("TRICK_POS:${bomb.id}:${bomb.x}:${bomb.y}")
        }
        invalidate()
    }

    private fun nextTrickBombStep(bomb: Bomb, target: Pair<Int, Int>): Pair<Int, Int>? {
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
                if (next in previous || tile == Tile.STEEL || tile == Tile.BRICK || tile == Tile.SAND ||
                    tile == Tile.HOLE ||
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

    fun handleRemoteCommand(command: String) {
        if (command.startsWith("CHARACTER:")) {
            val values = command.substringAfter(':').split(':')
            val playerIndex = values.getOrNull(0)?.toIntOrNull() ?: return
            if (playerIndex !in specs.indices) return
            val maxHp = values.getOrNull(1)?.toIntOrNull() ?: return
            if (maxHp !in 50..1000 || maxHp % 50 != 0) return
            val speed = SpeedTier.entries.firstOrNull { it.name == values.getOrNull(2) } ?: return
            val outfit = Outfit.entries.firstOrNull { it.name == values.getOrNull(3) } ?: return
            val accessory = Accessory.entries.firstOrNull { it.name == values.getOrNull(4) } ?: return
            val perk = Perk.entries.firstOrNull { it.name == values.getOrNull(5) } ?: return
            specs[playerIndex].apply {
                this.maxHp = maxHp
                this.speed = speed
                this.outfit = outfit
                this.accessory = accessory
                this.perk = perk
            }
            players.getOrNull(playerIndex)?.let { it.hp = it.hp.coerceAtMost(maxHp) }
            invalidate()
            return
        }
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
            parts.getOrNull(3)?.toLongOrNull()?.let { mapSeed = it }
            parts.getOrNull(4)?.toIntOrNull()?.let { soloLevel = it.coerceIn(1, SOLO_LEVEL_COUNT) }
            startGame(preserveSeed = true)
            return
        }
        if (command.startsWith("CHAIN:")) {
            val values = command.substringAfter(':').split(':')
            val x = values.getOrNull(0)?.toIntOrNull() ?: return
            val y = values.getOrNull(1)?.toIntOrNull() ?: return
            bombs.firstOrNull { it.x == x && it.y == y }?.let { detonate(it, fromChain = true) }
            return
        }
        val remotePlayer = if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) 0 else 1
        val previousActivePlayer = activePlayer
        activePlayer = remotePlayer
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
                val watchPlayer = players[remotePlayer]
                val positionCommand = if (remotePlayer == 0) "HOST_POS" else "WATCH_POS"
                sendToWatch("$positionCommand:${watchPlayer.x}:${watchPlayer.y}")
            }
            activePlayer = previousActivePlayer
            invalidate()
        }
    }

    fun sendWatchSetup() {
        sendWatchCharacter()
        if (screen == Screen.GAME) {
            sendToWatch("START:${selectedMode.name}:$selectedMap:$mapSeed:$soloLevel")
            sendWatchBoard()
            invalidate()
        }
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
            Screen.MODE_SELECT -> drawModeSelect(canvas)
            Screen.LEVELS -> drawSoloLevels(canvas)
            Screen.GAME -> drawGame(canvas)
            Screen.CUSTOMIZER -> drawCustomizer(canvas)
            Screen.BUILDER -> drawBuilder(canvas)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.HELP -> drawHelp(canvas)
            Screen.RESULTS -> drawResults(canvas)
        }
        canvas.restore()
        if (layer >= 0) canvas.restoreToCount(layer)
        if (SystemClock.uptimeMillis() < jumpScareUntil) {
            drawJumpScare(canvas)
            postInvalidateOnAnimation()
        }
        if (transitionProgress < 1f) postInvalidateOnAnimation()
        if (screen == Screen.GAME && (bombs.isNotEmpty() || blastEffects.isNotEmpty() || particles.isNotEmpty() ||
                flameTrails.isNotEmpty() || droppedItems.isNotEmpty() ||
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
        if (screen == Screen.GAME &&
            (soloDoorTransitionStartedAt != 0L || deathAnimationStartedAt != 0L)
        ) {
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
        text(canvas, "Choose a mode and jump in", w * .075f, h * .125f, unit * .045f,
            Color.WHITE, true)
        drawPanel(canvas, RectF(w * .055f, h * .18f, w * .945f, h * .285f),
            Color.rgb(22, 53, 63), Color.rgb(68, 225, 209))
        text(canvas, "LEVELS & GAME MODES", w * .1f, h * .225f, unit * .032f,
            Color.WHITE, true)
        text(canvas, selectedMode.title.uppercase(), w * .9f, h * .26f, unit * .02f,
            Color.rgb(113, 237, 208), true, Paint.Align.RIGHT)
        button(canvas, "CHARACTERS", w * .055f, h * .33f, w * .49f, h * .405f,
            Color.rgb(43, 62, 85))
        button(canvas, "MAP BUILDER", w * .51f, h * .33f, w * .945f, h * .405f,
            Color.rgb(43, 62, 85))
        button(canvas, "${maps[selectedMap].first.uppercase()} · NEXT MAP",
            w * .055f, h * .445f, w * .49f, h * .52f, Color.rgb(23, 72, 81))
        button(canvas, "HOST PHONE", w * .51f, h * .445f, w * .945f, h * .52f,
            Color.rgb(23, 72, 81))
        button(canvas, "SETTINGS", w * .055f, h * .56f, w * .49f, h * .635f,
            Color.rgb(35, 54, 74))
        button(canvas, "HOW TO PLAY", w * .51f, h * .56f, w * .945f, h * .635f,
            Color.rgb(35, 54, 74))
        button(canvas, if (maxUnlockedSoloLevel > 1) {
            "CONTINUE SOLO  ·  LEVEL %02d".format(maxUnlockedSoloLevel)
        } else {
            "START SOLO CAMPAIGN"
        }, w * .055f, h * .68f, w * .945f, h * .75f, Color.rgb(35, 89, 104))
        text(canvas, connectionStatus, w * .075f, h * .79f, unit * .02f,
            Color.rgb(158, 177, 201))
        button(canvas, "START ${selectedMode.title.uppercase()}",
            w * .055f, h * .86f, w * .945f, h * .93f,
            Color.rgb(24, 153, 117))
    }

    private fun drawModeSelect(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        drawScreenTitle(canvas, "LEVELS & GAME MODES", "Solo opens the 10-level key-and-door campaign")
        val gap = w * .035f
        val cardWidth = (w * .9f - gap) / 2f
        val compactModes = GameMode.entries.size > 8
        val cardHeight = h * if (compactModes) .095f else .12f
        val rowGap = h * if (compactModes) .012f else .018f
        val top = h * .21f
        GameMode.entries.forEachIndexed { index, mode ->
            val column = index % 2
            val row = index / 2
            val left = w * .05f + column * (cardWidth + gap)
            val cardTop = top + row * (cardHeight + rowGap)
            val selected = mode == selectedMode
            drawPanel(canvas, RectF(left, cardTop, left + cardWidth, cardTop + cardHeight),
                if (selected) Color.rgb(29, 48, 69) else Color.rgb(16, 25, 39),
                if (selected) Color.rgb(68, 225, 209) else Color.rgb(40, 58, 76))
            text(canvas, mode.title.uppercase(), left + cardWidth * .08f,
                cardTop + cardHeight * .42f, unit * .025f, Color.WHITE, true)
            val subtitle = if (mode == GameMode.SOLO) {
                "$maxUnlockedSoloLevel / $SOLO_LEVEL_COUNT LEVELS"
            } else mode.subtitle
            text(canvas, subtitle, left + cardWidth * .08f, cardTop + cardHeight * .75f,
                unit * .015f, Color.rgb(164, 182, 199))
        }
        button(canvas, "BACK TO MENU", w * .1f, h * .86f, w * .9f, h * .93f,
            Color.rgb(35, 57, 76))
    }

    private fun drawSoloLevels(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        drawScreenTitle(canvas, "SOLO CAMPAIGN", "Find the key, unlock the door, and clear all 10 maps")
        val columns = 2
        val gapX = w * .035f
        val cardWidth = (w * .9f - gapX) / columns
        val cardHeight = h * .105f
        val top = h * .22f
        for (level in 1..SOLO_LEVEL_COUNT) {
            val index = level - 1
            val column = index % columns
            val row = index / columns
            val left = w * .05f + column * (cardWidth + gapX)
            val cardTop = top + row * (cardHeight + h * .012f)
            val unlocked = level <= maxUnlockedSoloLevel
            drawPanel(canvas, RectF(left, cardTop, left + cardWidth, cardTop + cardHeight),
                if (level == soloLevel && unlocked) Color.rgb(28, 61, 70) else Color.rgb(16, 25, 39),
                if (unlocked) Color.rgb(60, 184, 164) else Color.rgb(42, 51, 65))
            text(canvas, "LEVEL %02d".format(level), left + cardWidth * .1f,
                cardTop + cardHeight * .42f, unit * .029f,
                if (unlocked) Color.WHITE else Color.rgb(123, 137, 154), true)
            text(canvas, if (unlocked) {
                if (level == soloLevel) "CONTINUE" else "UNLOCKED"
            } else "LOCKED", left + cardWidth * .1f, cardTop + cardHeight * .76f,
                unit * .018f, if (unlocked) Color.rgb(120, 232, 199) else Color.rgb(133, 142, 155))
        }
        text(canvas, "PROGRESS  $maxUnlockedSoloLevel / $SOLO_LEVEL_COUNT",
            w * .5f, h * .825f, unit * .022f, Color.rgb(161, 181, 202), true, Paint.Align.CENTER)
        button(canvas, "BACK TO MODES", w * .1f, h * .88f, w * .9f, h * .95f,
            Color.rgb(35, 57, 76))
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
            GameMode.HELL -> "ALL TWISTS · MONSTERS EVERY 20 SEC"
            GameMode.CAPTURE_THE_FLAG ->
                "P1 ${flagCaptures[0]}  ·  FLAGS  ·  P2 ${flagCaptures[1]}"
            GameMode.SOLO -> "LEVEL $soloLevel/$SOLO_LEVEL_COUNT · ${if (soloHasKey) "KEY FOUND" else "FIND THE KEY"}"
            GameMode.SANDBOX -> "WORKSHOP · ${monsters.size} MONSTERS · ${board.size}×${board.size}"
            else -> "${maps[selectedMap].first} · ${board.size}×${board.size}"
        }
        text(canvas, mapLabel, w * .06f, h * .083f, unit * .022f, Color.rgb(161, 181, 202))
        val active = players[activePlayer]
        drawPanel(canvas, RectF(w * .69f, h * .025f, w * .94f, h * .09f),
            Color.rgb(18, 30, 45), Color.rgb(54, 91, 112))
        val maxHp = when {
            selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer -> 2250
            selectedMode == GameMode.HARDCORE -> 50
            selectedMode == GameMode.HELL -> 100
            else -> active.spec.maxHp
        }
        text(canvas, "PLAYER ${activePlayer + 1}", w * .715f, h * .052f,
            unit * .018f, Color.rgb(176, 197, 216), true)
        text(canvas, "${active.hp} HP", w * .915f, h * .052f,
            unit * .021f, Color.rgb(134, 241, 197), true, Paint.Align.RIGHT)
        drawHealthBar(canvas, w * .715f, h * .064f, w * .915f, h * .078f,
            active.hp.toFloat() / maxHp.coerceAtLeast(1))
        val now = SystemClock.uptimeMillis()
        val blastEnergy = blastEffects.maxOfOrNull {
            (1f - (now - it.startedAt) / 520f).coerceIn(0f, 1f)
        } ?: 0f
        canvas.save()
        if (blastEnergy > 0f) {
            val shake = unit * .004f * blastEnergy
            canvas.translate(kotlin.math.sin(now / 19f) * shake, kotlin.math.cos(now / 23f) * shake)
        }
        drawBoard(canvas, RectF(w * .045f, h * .115f, w * .955f, h * .555f))
        canvas.restore()
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
        drawFlameTrails(canvas, left, top, cell)
        if (selectedMode == GameMode.CAPTURE_THE_FLAG) {
            flags.forEach { drawFlag(canvas, left + (it.x + .5f) * cell, top + (it.y + .5f) * cell,
                cell, it.owner, it.carrier != null) }
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
        drawParticles(canvas, left, top, cell)
        val now = SystemClock.uptimeMillis()
        players.forEachIndexed { index, player ->
            if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX) && index == 1) return@forEachIndexed
            val walkProgress = ((now - player.lastWalkAt) / 170f).coerceIn(0f, 1f)
            val visualX = if (walkProgress < 1f) {
                player.walkFromX + (player.x - player.walkFromX) * walkProgress
            } else player.x.toFloat()
            val visualY = if (walkProgress < 1f) {
                player.walkFromY + (player.y - player.walkFromY) * walkProgress
            } else player.y.toFloat()
            val cx = left + (visualX + .5f) * cell
            val cy = top + (visualY + .5f) * cell
            val doorProgress = if (index == 0 && soloDoorTransitionStartedAt != 0L) {
                ((now - soloDoorTransitionStartedAt).toFloat() / SOLO_DOOR_ANIMATION_MS).coerceIn(0f, 1f)
            } else 0f
            val deathProgress = if (index == deathPlayerIndex && deathAnimationStartedAt != 0L) {
                ((now - deathAnimationStartedAt).toFloat() / SOLO_DEATH_ANIMATION_MS).coerceIn(0f, 1f)
            } else 0f
            val size = cell * (if (selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer) .86f else .7f) *
                (1f - maxOf(doorProgress * .55f, deathProgress * .72f))
            val maxHp = when {
                selectedMode == GameMode.JUGGERNAUT && index == juggernautPlayer -> 2250
                selectedMode == GameMode.HARDCORE -> 50
                selectedMode == GameMode.HELL -> 100
                else -> player.spec.maxHp
            }
            canvas.save()
            if (doorProgress > 0f || deathProgress > 0f) {
                canvas.rotate(if (deathProgress > 0f) deathProgress * 95f else doorProgress * 12f, cx, cy)
                canvas.translate(0f, -cell * maxOf(doorProgress * .22f, deathProgress * .2f))
            }
            drawCharacter(canvas, cx, cy, size, player.spec.outfit.color, player.spec.accessory,
                "${index + 1}", player.shieldCharges > 0,
                walking = doorProgress > 0f || deathProgress > 0f ||
                    SystemClock.uptimeMillis() - player.lastWalkAt in 0L..420L,
                healthRatio = player.hp.toFloat() / maxHp)
            canvas.restore()
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
        val powerUpCodes = active.collectedPowerUps.entries.toList().takeLast(6)
        text(canvas, if (powerUpCodes.isEmpty()) "Power-ups: none" else "Power-ups: ${powerUpCodes.joinToString(" ")}",
            w * .585f, top + h * .265f, unit * .015f, Color.rgb(135, 224, 185))
        text(canvas, activeDebuffSummary(active, SystemClock.uptimeMillis()),
            w * .585f, top + h * .30f, unit * .015f, Color.rgb(240, 151, 145))
        text(canvas, "P1  ${players[0].hp} HP", w * .27f, h * .94f, unit * .023f,
            if (activePlayer == 0) Color.rgb(125, 232, 203) else Color.rgb(165, 181, 197),
            true, Paint.Align.CENTER)
        text(canvas, if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
            "MONSTERS  ${monsters.size}"
        } else "P2  ${players[1].hp} HP",
            w * .73f, h * .94f, unit * .023f,
            if (activePlayer == 1) Color.rgb(255, 139, 151) else Color.rgb(165, 181, 197),
            true, Paint.Align.CENTER)
    }

    private fun drawPowerUp(canvas: Canvas, cx: Float, cy: Float, cell: Float, powerUp: PowerUp) {
        val pulse = 1f + .08f * kotlin.math.sin(SystemClock.uptimeMillis() / 170f)
        canvas.save()
        canvas.scale(pulse, pulse, cx, cy)
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
        canvas.restore()
    }

    private fun drawFlameTrails(canvas: Canvas, left: Float, top: Float, cell: Float) {
        val now = SystemClock.uptimeMillis()
        flameTrails.values.forEach { flame ->
            val remaining = (flame.expiresAt - now).coerceAtLeast(0L).toFloat() / 3_000f
            val flicker = kotlin.math.sin(now / 75f + flame.x * 2f + flame.y).toFloat()
            val cx = left + (flame.x + .5f) * cell
            val cy = top + (flame.y + .5f) * cell
            paint.color = Color.argb((110 * remaining.coerceIn(0f, 1f)).toInt(), 255, 54, 18)
            canvas.drawCircle(cx, cy, cell * (.42f + .025f * flicker), paint)
            paint.color = Color.argb((220 * remaining.coerceIn(0f, 1f)).toInt(), 255, 104, 24)
            tileBounds.set(cx - cell * .25f, cy - cell * (.32f + .025f * flicker),
                cx + cell * .25f, cy + cell * .35f)
            canvas.drawOval(tileBounds, paint)
            paint.color = Color.argb((220 * remaining.coerceIn(0f, 1f)).toInt(), 255, 219, 108)
            canvas.drawCircle(cx, cy + cell * .12f, cell * .1f, paint)
        }
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

    private fun drawJumpScare(canvas: Canvas) {
        val unit = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawColor(Color.rgb(225, 20, 38))
        paint.color = Color.rgb(255, 240, 220)
        canvas.drawCircle(cx - unit * .13f, cy - unit * .08f, unit * .055f, paint)
        canvas.drawCircle(cx + unit * .13f, cy - unit * .08f, unit * .055f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(cx - unit * .13f, cy - unit * .08f, unit * .024f, paint)
        canvas.drawCircle(cx + unit * .13f, cy - unit * .08f, unit * .024f, paint)
        paint.style = Paint.Style.FILL
        text(canvas, "BOO!", cx, cy + unit * .28f, unit * .12f, Color.WHITE, true)
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
        button(canvas, "JUMP-SCARE  ·  ${if (jumpScaresEnabled) "ON" else "OFF"}",
            w * .1f, h * .45f, w * .9f, h * .52f,
            if (jumpScaresEnabled) Color.rgb(132, 48, 70) else Color.rgb(35, 57, 76))
        drawPanel(canvas, RectF(w * .07f, h * .55f, w * .93f, h * .68f),
            Color.rgb(16, 26, 40), Color.rgb(38, 63, 81))
        text(canvas, "HOLD TO MOVE", w * .12f, h * .59f, unit * .025f,
            Color.WHITE, true)
        text(canvas, "Keep a direction pressed to move repeatedly.", w * .12f, h * .64f,
            unit * .019f, Color.rgb(158, 177, 201))
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
        button(canvas, "TOOL  ·  ${builderTool.name.replace('_', ' ')}  ·  TAP TO CHANGE",
            w * .08f, h * .785f, w * .92f, h * .845f,
            Color.rgb(30, 65, 78))
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
        builderItems.forEach { (position, code) ->
            PowerUp.fromCode(code)?.let {
                drawPowerUp(canvas, left + (position.first + .5f) * cell,
                    top + (position.second + .5f) * cell, cell, it)
            }
        }
        builderMonsters.forEach { monster ->
            drawCharacter(canvas, left + (monster.x + .5f) * cell,
                top + (monster.y + .5f) * cell, cell * .72f,
                Color.rgb(148, 202, 82), Accessory.HORNS, "M")
        }
        drawCharacter(canvas, left + (builderSpawn.first + .5f) * cell,
            top + (builderSpawn.second + .5f) * cell, cell * .72f,
            specs[0].outfit.color, specs[0].accessory, "P1")
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
                        Screen.MODE_SELECT -> handleModeSelectTap(event.x, event.y)
                        Screen.LEVELS -> handleSoloLevelTap(event.x, event.y)
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
        when {
            y in h * .18f..h * .285f -> changeScreen(Screen.MODE_SELECT)
            y in h * .33f..h * .405f -> {
                if (x < width * .5f) changeScreen(Screen.CUSTOMIZER) else changeScreen(Screen.BUILDER)
            }
            y in h * .445f..h * .52f -> {
                if (x < width * .5f) {
                    selectedMap = (selectedMap + 1) % maps.size
                    sendToWatch("MAP:$selectedMap")
                } else onHostRequested?.invoke()
            }
            y in h * .56f..h * .635f -> {
                changeScreen(if (x < width * .5f) Screen.SETTINGS else Screen.HELP)
            }
            y in h * .68f..h * .75f -> {
                selectedMode = GameMode.SOLO
                soloLevel = maxUnlockedSoloLevel
                sendToWatch("MODE:${GameMode.SOLO.name}")
                startGame()
            }
            y > h * .84f -> startGame()
        }
    }

    private fun handleModeSelectTap(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (y > h * .84f) {
            changeScreen(Screen.LOBBY)
            return
        }
        val gap = w * .035f
        val cardWidth = (w * .9f - gap) / 2f
        val compactModes = GameMode.entries.size > 8
        val cardHeight = h * if (compactModes) .095f else .12f
        val rowGap = h * if (compactModes) .012f else .018f
        val top = h * .21f
        if (x < w * .05f || x > w * .95f || y < top ||
            y >= top + ((GameMode.entries.size + 1) / 2) * (cardHeight + rowGap)
        ) return
        val column = ((x - w * .05f) / (cardWidth + gap)).toInt()
        val row = ((y - top) / (cardHeight + rowGap)).toInt()
        if (column !in 0..1 || row !in 0 until (GameMode.entries.size + 1) / 2) return
        val index = row * 2 + column
        val mode = GameMode.entries.getOrNull(index) ?: return
        selectedMode = mode
        if (mode == GameMode.JUGGERNAUT) juggernautIsPhone = !juggernautIsPhone
        sendToWatch("MODE:${mode.name}")
        changeScreen(when (mode) {
            GameMode.SOLO -> Screen.LEVELS
            GameMode.SANDBOX -> Screen.BUILDER
            else -> Screen.LOBBY
        })
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
            activePlayer = if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) 0 else 1
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
            y in height * .45f..height * .52f -> jumpScaresEnabled = !jumpScaresEnabled
            y > height * .84f -> changeScreen(Screen.LOBBY)
            y < height * .12f && x < width * .25f -> changeScreen(Screen.LOBBY)
        }
    }

    private fun handleResultsTap(y: Float) {
        when {
            y in height * .62f..height * .7f -> startGame()
            y in height * .73f..height * .81f -> {
                selectedMode = GameMode.SOLO
                changeScreen(Screen.LEVELS)
            }
            y > height * .84f -> changeScreen(Screen.LOBBY)
        }
    }

    private fun handleSoloLevelTap(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (y > h * .86f) {
            changeScreen(Screen.MODE_SELECT)
            return
        }
        val columns = 2
        val gapX = w * .035f
        val cardWidth = (w * .9f - gapX) / columns
        val cardHeight = h * .105f
        val top = h * .22f
        if (x < w * .05f || x > w * .95f || y < top ||
            y >= top + 5 * (cardHeight + h * .012f)
        ) return
        val column = ((x - w * .05f) / (cardWidth + gapX)).toInt()
        val row = ((y - top) / (cardHeight + h * .012f)).toInt()
        if (column !in 0 until columns || row !in 0..4) return
        val level = row * columns + column + 1
        if (level <= maxUnlockedSoloLevel) {
            soloLevel = level
            startGame()
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
            bombs.clear()
            nextTrickBombId = 1
            blastEffects.clear()
            particles.clear()
            droppedItems.clear()
            monsters.clear()
            raceWon = false
            powerUpMessageUntil = 0L
            removeCallbacks(monsterTick)
            removeCallbacks(hellSandTick)
            startPlayers()
            nextTrickBombId = 1
            removeCallbacks(ctfBrickTick)
            if (selectedMode == GameMode.CAPTURE_THE_FLAG) {
                postDelayed(ctfBrickTick, CTF_BRICK_INTERVAL_MS)
            }
            if (selectedMode == GameMode.SANDBOX) {
                monsters.addAll(builderMonsters.map { it.copy(lastMovedAt = SystemClock.uptimeMillis()) })
                droppedItems.putAll(builderItems)
                players[0].x = builderSpawn.first
                players[0].y = builderSpawn.second
                players[0].spawnX = builderSpawn.first
                players[0].spawnY = builderSpawn.second
                players[0].walkFromX = builderSpawn.first
                players[0].walkFromY = builderSpawn.second
                postDelayed(monsterTick, 500L)
            }
            sendWatchCharacter()
            sendToWatch("START:${selectedMode.name}:$selectedMap:$mapSeed")
            sendWatchBoard()
            return
        }
        if (y in height * .785f..height * .845f) {
            builderTool = BuilderTool.entries[(builderTool.ordinal + 1) % BuilderTool.entries.size]
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
            val position = col to row
            when (builderTool) {
                BuilderTool.TILE -> {
                    val tiles = listOf(
                        Tile.FLOOR, Tile.STEEL, Tile.BRICK, Tile.SAND, Tile.TELEPORT,
                        Tile.HOLE, Tile.GOAL, Tile.KEY, Tile.DOOR
                    )
                    val nextTile = (tiles.indexOf(builder[row][col]) + 1) % tiles.size
                    builder[row][col] = tiles[nextTile]
                }
                BuilderTool.MONSTER -> {
                    if (builderMonsters.removeAll { it.x == col && it.y == row }) return
                    builder[row][col] = Tile.FLOOR
                    builderMonsters.add(Monster(col, row, hidden = false))
                }
                BuilderTool.POWER_UP -> {
                    val current = builderItems[position]?.let { PowerUp.fromCode(it) }
                    builderItems[position] = powerUps[(current?.ordinal?.plus(1) ?: 0) % powerUps.size].code
                }
                BuilderTool.PLAYER -> {
                    builder[row][col] = Tile.FLOOR
                    builderSpawn = position
                }
                BuilderTool.ERASE -> {
                    builder[row][col] = Tile.FLOOR
                    builderMonsters.removeAll { it.x == col && it.y == row }
                    builderItems.remove(position)
                }
            }
        }
    }

    private fun startGame(preserveSeed: Boolean = false) {
        if (selectedMode == GameMode.SANDBOX && screen != Screen.BUILDER) {
            changeScreen(Screen.BUILDER)
            return
        }
        val size = when (selectedMode) {
            GameMode.RACE -> 15
            GameMode.SPLEEF -> 13
            GameMode.SOLO -> soloBoardSize(soloLevel)
            else -> maps[selectedMap].second
        }
        if (!preserveSeed) mapSeed = System.nanoTime()
        board = if (selectedMode == GameMode.SPLEEF) {
            Array(size) { Array(size) { Tile.FLOOR } }
        } else {
            emptyBoard(size, mapSeed)
        }
        if (selectedMode == GameMode.RACE) {
            for (x in 1..7) board[1][x] = Tile.FLOOR
            for (y in 1..7) board[y][7] = Tile.FLOOR
            for (x in 7..13) board[13][x] = Tile.FLOOR
            for (y in 7..13) board[y][7] = Tile.FLOOR
            board[7][7] = Tile.GOAL
        } else if (selectedMode != GameMode.SPLEEF && selectedMode != GameMode.SOLO) {
            if (selectedMode == GameMode.HELL) applyHellTwists() else applyMapTwists()
        }
        changeScreen(Screen.GAME)
        bombs.clear()
        nextTrickBombId = 1
        blastEffects.clear()
        particles.clear()
        flameTrails.clear()
        droppedItems.clear()
        monsters.clear()
        raceWon = false
        soloHasKey = false
        soloDoorTransitionStartedAt = 0L
        deathAnimationStartedAt = 0L
        deathAnimationComplete = false
        deathPlayerIndex = -1
        powerUpMessageUntil = 0L
        removeCallbacks(ctfBrickTick)
        removeCallbacks(trickBombTick)
        removeCallbacks(hellSandTick)
        removeCallbacks(flameTick)
        removeCallbacks(monsterTick)
        startPlayers()
        if (selectedMode == GameMode.SOLO) {
            configureSoloLevel()
            postDelayed(monsterTick, 500L)
        } else if (selectedMode == GameMode.HELL) {
            configureHellMonsters()
            lastHellMonsterSpawnAt = SystemClock.uptimeMillis()
            postDelayed(monsterTick, 500L)
            postDelayed(hellSandTick, HELL_SAND_INTERVAL_MS)
        } else {
            sendMonsterState()
        }
        if (selectedMode == GameMode.CAPTURE_THE_FLAG) {
            postDelayed(ctfBrickTick, CTF_BRICK_INTERVAL_MS)
        }
        sendWatchCharacter()
        sendToWatch("START:${selectedMode.name}:$selectedMap:$mapSeed:$soloLevel")
        sendWatchBoard()
    }

    private fun sendWatchCharacter() {
        val playerIndex = if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) 0 else 1
        val spec = specs[playerIndex]
        sendToWatch(
            "CHARACTER:$playerIndex:${spec.maxHp}:${spec.speed.name}:${spec.outfit.name}:" +
                "${spec.accessory.name}:${spec.perk.name}"
        )
    }

    private fun sendWatchBoard() {
        sendToWatch("MAPBOARD:${board.size}:${encodeBoard()}")
        sendToWatch("SPAWNS:${players[0].x},${players[0].y};${players[1].x},${players[1].y}")
        sendFlagState()
        val monsterPositions = monsters.joinToString(";") { "${it.x},${it.y},${if (it.hidden) 1 else 0}" }
        sendToWatch("MONSTERS:$monsterPositions")
        sendToWatch("KEY:${if (soloHasKey) 1 else 0}")
        sendToWatch("LEVEL:$soloLevel")
        droppedItems.forEach { (position, code) ->
            sendToWatch("DROP:${position.first}:${position.second}:$code")
        }
        if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
            sendToWatch("SOLO_HP:${players[0].hp}")
        } else {
            players.forEachIndexed { index, player -> sendToWatch("PLAYER_HP:$index:${player.hp}") }
        }
        val now = SystemClock.uptimeMillis()
        flameTrails.values.forEach { flame ->
            val remaining = flame.expiresAt - now
            if (remaining > 0L) sendToWatch("FLAME:${flame.x}:${flame.y}:$remaining")
        }
    }

    private fun soloBoardSize(level: Int): Int = when {
        level <= 3 -> 9
        level <= 6 -> 11
        level <= 8 -> 13
        else -> 15
    }

    private fun configureSoloLevel() {
        val last = board.lastIndex - 1
        val keyX = (last / 2).coerceIn(2, last - 1)
        for (x in 1..last) board[1][x] = Tile.FLOOR
        for (y in 1..last) board[y][last] = Tile.FLOOR
        board[1][keyX] = Tile.KEY
        board[last][last] = Tile.DOOR

        val hiddenCount = (3 - soloLevel / 4).coerceAtLeast(1)
        val hiddenSpots = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == Tile.BRICK && x > 2 && y > 2 &&
                    x < last - 1 && y < last - 1
                ) x to y else null
            }
        }.shuffled().take(hiddenCount)
        hiddenSpots.forEach { (x, y) -> monsters.add(Monster(x, y)) }

        if (soloLevel >= 4) {
            val visibleCount = (1 + (soloLevel - 4) / 2).coerceAtMost(4)
            val spawnSpots = board.indices.flatMap { y ->
                board[y].indices.mapNotNull { x ->
                    if (board[y][x] == Tile.FLOOR && x in 2 until last && y in 2 until last) x to y else null
                }
            }.shuffled().take(visibleCount)
            val now = SystemClock.uptimeMillis()
            spawnSpots.forEach { (x, y) ->
                monsters.add(Monster(x, y, hidden = false, lastMovedAt = now))
            }
        }
    }

    private fun saveUnlockedSoloLevel() {
        context.applicationContext.getSharedPreferences("wearbomber_progress", Context.MODE_PRIVATE)
            .edit()
            .putInt("max_solo_level", maxUnlockedSoloLevel)
            .apply()
    }

    private fun enterSoloDoor() {
        if (soloDoorTransitionStartedAt != 0L) return
        soloDoorTransitionStartedAt = SystemClock.uptimeMillis()
        emitParticles(players[0].x, players[0].y,
            intArrayOf(Color.rgb(255, 227, 119), Color.rgb(255, 182, 71), Color.WHITE), 24, 1.8f)
        lastPowerUpMessage = "DOOR UNLOCKED"
        powerUpMessageUntil = soloDoorTransitionStartedAt + SOLO_DOOR_ANIMATION_MS
        sendToWatch("DOOR:1")
        postDelayed({
            if (screen != Screen.GAME || soloDoorTransitionStartedAt == 0L) return@postDelayed
            if (soloLevel >= SOLO_LEVEL_COUNT) {
                soloDoorTransitionStartedAt = 0L
                finishMatch(0, "CAMPAIGN COMPLETE · ALL 10 LEVELS CLEARED")
            } else {
                maxUnlockedSoloLevel = maxOf(maxUnlockedSoloLevel, soloLevel + 1)
                saveUnlockedSoloLevel()
                soloLevel++
                soloDoorTransitionStartedAt = 0L
                startGame()
            }
        }, SOLO_DOOR_ANIMATION_MS)
    }

    private fun startPlayers() {
        val spawnPositions = chooseSpawnPositions()
        activePlayer = 0
        val now = SystemClock.uptimeMillis()
        players = mutableListOf(
            Player(spawnPositions.first.first, spawnPositions.first.second,
                when {
                    selectedMode == GameMode.JUGGERNAUT && juggernautPlayer == 0 -> 2250
                    selectedMode == GameMode.HELL -> 100
                    else -> specs[0].maxHp
                },
                specs[0], spawnX = spawnPositions.first.first, spawnY = spawnPositions.first.second,
                lastMonsterHitAt = now),
            Player(spawnPositions.second.first, spawnPositions.second.second,
                when {
                    selectedMode == GameMode.JUGGERNAUT && juggernautPlayer == 1 -> 2250
                    selectedMode == GameMode.HELL -> 100
                    else -> specs[1].maxHp
                },
                specs[1], spawnX = spawnPositions.second.first, spawnY = spawnPositions.second.second,
                lastMonsterHitAt = now)
        )
        if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) players[1].hp = 0
        if (selectedMode == GameMode.HARDCORE) players.forEach { it.hp = 50 }
        players.forEachIndexed { index, player ->
            if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX) && index == 1) return@forEachIndexed
            when (player.spec.perk) {
                Perk.NONE -> Unit
                Perk.SHIELD -> player.shieldCharges = 1
                Perk.KICK -> player.kickLevel = 1
                Perk.PIERCE -> player.piercingLevel = 1
            }
        }
        flagCaptures.fill(0)
        flags = if (selectedMode == GameMode.CAPTURE_THE_FLAG) {
            mutableListOf(
                CaptureFlag(0, players[0].x, players[0].y),
                CaptureFlag(1, players[1].x, players[1].y)
            )
        } else mutableListOf()
    }

    private fun chooseSpawnPositions(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val floors = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == Tile.FLOOR) x to y else null
            }
        }
        val first = floors.randomOrNull() ?: (1 to 1)
        val second = floors.filter { (x, y) ->
            kotlin.math.abs(x - first.first) + kotlin.math.abs(y - first.second) >= MIN_SPAWN_DISTANCE
        }.randomOrNull() ?: floors.maxByOrNull { (x, y) ->
            kotlin.math.abs(x - first.first) + kotlin.math.abs(y - first.second)
        } ?: first
        return first to second
    }

    private fun applyMapTwists() {
        val last = board.lastIndex
        if (selectedMap > 0) {
            board[2][1] = Tile.TELEPORT
            board[last - 2][last - 1] = Tile.TELEPORT
        }
        if (selectedMap == 9 || selectedMap == 11) {
            val middle = board.size / 2
            board[1][middle] = Tile.TELEPORT
            board[last - 1][middle] = Tile.TELEPORT
        }
        val trapdoorCount = when (selectedMap) {
            8 -> 4
            10 -> 7
            11 -> 5
            else -> 0
        }
        if (trapdoorCount == 0) return
        val random = JavaRandom(mapSeed xor 0x5EEDL)
        board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (x in 2 until last - 1 && y in 2 until last - 1 &&
                    board[y][x] == Tile.FLOOR
                ) x to y else null
            }
        }.shuffled(kotlin.random.Random(random.nextLong())).take(trapdoorCount).forEach { (x, y) ->
            board[y][x] = Tile.HOLE
        }
    }

    private fun applyHellTwists() {
        applyMapTwists()
        val last = board.lastIndex
        val middle = board.size / 2
        for ((x, y) in listOf(
            1 to 2, (last - 1) to (last - 2),
            middle to 1, middle to (last - 1)
        )) {
            board[y][x] = Tile.TELEPORT
        }
        val random = JavaRandom(mapSeed xor 0x48454C4CL)
        board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (x in 2 until last - 1 && y in 2 until last - 1 &&
                    board[y][x] == Tile.FLOOR
                ) x to y else null
            }
        }.shuffled(kotlin.random.Random(random.nextLong())).take(12).forEach { (x, y) ->
            board[y][x] = Tile.HOLE
        }
    }

    private fun configureHellMonsters() {
        val protected = players.map { it.x to it.y }.toSet()
        val hiddenSpots = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == Tile.BRICK &&
                    protected.none { kotlin.math.abs(it.first - x) + kotlin.math.abs(it.second - y) <= 2 }
                ) x to y else null
            }
        }.shuffled().take(2)
        hiddenSpots.forEach { (x, y) -> monsters.add(Monster(x, y)) }
        repeat(2) { spawnHellMonster() }
        sendMonsterState()
    }

    private fun spawnHellMonster() {
        val positions = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == Tile.FLOOR &&
                    players.none { it.hp > 0 && kotlin.math.abs(it.x - x) + kotlin.math.abs(it.y - y) <= 2 } &&
                    monsters.none { it.x == x && it.y == y } &&
                    bombs.none { it.x == x && it.y == y }
                ) x to y else null
            }
        }
        positions.randomOrNull()?.let { (x, y) ->
            monsters.add(Monster(x, y, hidden = false, lastMovedAt = SystemClock.uptimeMillis()))
            sendMonsterState()
            invalidate()
        }
    }

    private fun spawnHellSand() {
        val positions = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] == Tile.FLOOR &&
                    players.none { it.x == x && it.y == y } &&
                    bombs.none { it.x == x && it.y == y } &&
                    monsters.none { it.x == x && it.y == y } &&
                    !droppedItems.containsKey(x to y) &&
                    !flameTrails.containsKey(x to y) &&
                    flags.none { it.x == x && it.y == y }
                ) x to y else null
            }
        }
        positions.randomOrNull()?.let { (x, y) ->
            board[y][x] = Tile.SAND
            sendToWatch("SAND:$x:$y")
            if (Random.nextInt(100) < HELL_MONSTER_CHANCE) {
                monsters.add(Monster(x, y))
                sendMonsterState()
            }
            invalidate()
        }
    }

    private fun sendMonsterState() {
        val encoded = monsters.joinToString(";") { "${it.x},${it.y},${if (it.hidden) 1 else 0}" }
        sendToWatch("MONSTERS:$encoded")
    }

    private fun sendFlagState() {
        if (selectedMode != GameMode.CAPTURE_THE_FLAG || flags.size != 2) return
        val encodedFlags = flags.joinToString(":") {
            "${it.x},${it.y},${it.carrier ?: -1}"
        }
        sendToWatch("FLAGS:${flagCaptures[0]}:${flagCaptures[1]}:$encodedFlags")
    }

    private fun updateFlagCapture(playerIndex: Int) {
        if (selectedMode != GameMode.CAPTURE_THE_FLAG || flags.size != 2) return
        val player = players[playerIndex]
        val ownFlag = flags[playerIndex]
        val enemyFlag = flags[1 - playerIndex]
        flags.firstOrNull { it.carrier == playerIndex }?.let {
            it.x = player.x
            it.y = player.y
        }
        if (ownFlag.carrier == null && ownFlag.x == player.x && ownFlag.y == player.y &&
            (ownFlag.x != ownFlag.baseX || ownFlag.y != ownFlag.baseY)
        ) {
            ownFlag.x = ownFlag.baseX
            ownFlag.y = ownFlag.baseY
            lastPowerUpMessage = "FLAG RETURNED"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
        }
        if (enemyFlag.carrier == null && enemyFlag.x == player.x && enemyFlag.y == player.y) {
            enemyFlag.carrier = playerIndex
            lastPowerUpMessage = "ENEMY FLAG TAKEN"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
        }
        if (enemyFlag.carrier == playerIndex &&
            player.x == ownFlag.baseX && player.y == ownFlag.baseY &&
            ownFlag.carrier == null && ownFlag.x == ownFlag.baseX && ownFlag.y == ownFlag.baseY
        ) {
            flagCaptures[playerIndex]++
            enemyFlag.carrier = null
            enemyFlag.x = enemyFlag.baseX
            enemyFlag.y = enemyFlag.baseY
            lastPowerUpMessage = "FLAG CAPTURED · ${flagCaptures[playerIndex]}"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 2_000L
            if (flagCaptures[playerIndex] >= FLAGS_TO_WIN) {
                sendFlagState()
                finishMatch(playerIndex, "Captured the flag three times.")
                return
            }
        }
        sendFlagState()
    }

    private fun spawnCaptureFlagBrick() {
        val candidates = board.indices.flatMap { y ->
            board[y].indices.mapNotNull { x ->
                if (board[y][x] != Tile.FLOOR ||
                    players.any { kotlin.math.abs(it.x - x) + kotlin.math.abs(it.y - y) <= 1 } ||
                    flags.any {
                        kotlin.math.abs(it.x - x) + kotlin.math.abs(it.y - y) <= 1 ||
                            kotlin.math.abs(it.baseX - x) + kotlin.math.abs(it.baseY - y) <= 1
                    } ||
                    bombs.any { it.x == x && it.y == y } ||
                    droppedItems.containsKey(x to y)
                ) null else x to y
            }
        }
        val position = candidates.randomOrNull() ?: return
        board[position.second][position.first] = Tile.BRICK
        sendToWatch("BRICK:${position.first}:${position.second}")
        invalidate()
    }

    private fun drawFlag(canvas: Canvas, cx: Float, cy: Float, cell: Float, owner: Int, carried: Boolean) {
        val color = if (owner == 0) Color.rgb(70, 165, 255) else Color.rgb(255, 91, 111)
        paint.color = Color.rgb(225, 228, 230)
        paint.strokeWidth = maxOf(1f, cell * .045f)
        canvas.drawLine(cx - cell * .18f, cy + cell * .3f, cx - cell * .18f, cy - cell * .3f, paint)
        paint.color = if (carried) Color.rgb(255, 220, 100) else color
        canvas.drawRect(cx - cell * .16f, cy - cell * .28f, cx + cell * .25f,
            cy - cell * .05f, paint)
    }

    private fun changeScreen(next: Screen) {
        if (screen == next) return
        screen = next
        screenTransitionStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun finishMatch(winner: Int?, message: String) {
        if (screen != Screen.GAME) return
        val deadPlayer = players.indices.firstOrNull {
            players[it].hp <= 0 &&
                (selectedMode !in setOf(GameMode.SOLO, GameMode.SANDBOX) || it == 0)
        } ?: -1
        if (deadPlayer >= 0 && !deathAnimationComplete) {
            if (deathAnimationStartedAt == 0L) {
                deathPlayerIndex = deadPlayer
                deathAnimationStartedAt = SystemClock.uptimeMillis()
                emitParticles(players[deadPlayer].x, players[deadPlayer].y,
                    intArrayOf(Color.rgb(255, 105, 94), Color.rgb(255, 202, 117), Color.WHITE), 22, 1.8f)
                postDelayed({
                    if (screen == Screen.GAME && deathAnimationStartedAt != 0L) {
                        deathAnimationComplete = true
                        finishMatch(winner, message)
                    }
                }, SOLO_DEATH_ANIMATION_MS)
            }
            return
        }
        removeCallbacks(monsterTick)
        deathAnimationStartedAt = 0L
        deathPlayerIndex = -1
        stopHeldMovement()
        holdingMovement = false
        resultTitle = when {
            selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX) && winner == 0 -> "YOU WIN!"
            selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX) -> "GAME OVER"
            winner == 0 -> "PLAYER 1 WINS"
            winner == 1 -> "PLAYER 2 WINS"
            else -> "DRAW"
        }
        resultSubtitle = message
        bombs.clear()
        particles.clear()
        sendToWatch("RESULT:${winner ?: -1}|$message")
        changeScreen(Screen.RESULTS)
    }

    private fun updateMonsters(affectedCells: Set<Pair<Int, Int>> = emptySet()) {
        if (selectedMode !in setOf(GameMode.SOLO, GameMode.SANDBOX, GameMode.HELL) ||
            screen != Screen.GAME || soloDoorTransitionStartedAt != 0L
        ) return
        monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells }
        val now = SystemClock.uptimeMillis()
        val targets = players.withIndex().filter {
            it.value.hp > 0 &&
                (selectedMode !in setOf(GameMode.SOLO, GameMode.SANDBOX) || it.index == 0)
        }
        monsters.filterNot { it.hidden }.forEach { monster ->
            val target = targets.minByOrNull {
                kotlin.math.abs(monster.x - it.value.x) + kotlin.math.abs(monster.y - it.value.y)
            }?.value ?: return@forEach
            val distance = kotlin.math.abs(monster.x - target.x) + kotlin.math.abs(monster.y - target.y)
            if (distance > 1 && now - monster.lastMovedAt >= 650L) {
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    .map { (dx, dy) -> monster.x + dx to monster.y + dy }
                    .filter { (x, y) ->
                        val tile = if (x in board.indices && y in board.indices) board[y][x] else Tile.STEEL
                        x in board.indices && y in board.indices &&
                            (tile == Tile.FLOOR || tile == Tile.KEY || tile == Tile.DOOR) &&
                            (x != target.x || y != target.y) &&
                            monsters.none { other -> other !== monster && other.x == x && other.y == y } &&
                            bombs.none { it.x == x && it.y == y }
                    }
                    .minByOrNull { (x, y) ->
                        kotlin.math.abs(x - target.x) + kotlin.math.abs(y - target.y)
                    }
                    ?.let { (x, y) ->
                        monster.x = x
                        monster.y = y
                        monster.lastMovedAt = now
                    }
            }
        }
        for ((index, player) in targets) {
            val nearbyMonster = monsters.any {
                !it.hidden &&
                    kotlin.math.abs(it.x - player.x) + kotlin.math.abs(it.y - player.y) <= 1
            }
            if (nearbyMonster && now - player.lastMonsterHitAt >= 1_400L) {
                player.lastMonsterHitAt = now
                player.hp = (player.hp - 6).coerceAtLeast(0)
                lastPowerUpMessage = if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
                    "A MONSTER HIT YOU"
                } else {
                    "A MONSTER HIT PLAYER ${index + 1}"
                }
                powerUpMessageUntil = now + 900L
                sendToWatch(if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) "SOLO_HP:${player.hp}"
                    else "PLAYER_HP:$index:${player.hp}")
                if (player.hp == 0) {
                    if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
                        finishMatch(null, "The monsters defeated you.")
                    } else {
                        val winner = players.indices.firstOrNull { it != index && players[it].hp > 0 }
                        finishMatch(winner, "A monster eliminated Player ${index + 1}.")
                    }
                    return
                }
            }
        }
        sendMonsterState()
        invalidate()
    }

    private fun movePlayer(dx: Int, dy: Int) {
        if (screen != Screen.GAME || soloDoorTransitionStartedAt != 0L || deathAnimationStartedAt != 0L) return
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
        if (selectedMode != GameMode.SPLEEF && board[ny][nx] == Tile.HOLE) {
            finishMatch(1 - activePlayer, "Player ${activePlayer + 1} fell through a trapdoor.")
            return
        }
        if (selectedMode == GameMode.SOLO && board[ny][nx] == Tile.DOOR && !soloHasKey) {
            lastPowerUpMessage = "THE DOOR IS LOCKED · FIND THE KEY"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
            invalidate()
            return
        }
        val trailPosition = player.x to player.y
        if (board[ny][nx] == Tile.STEEL) return
        val blockingBomb = bombs.firstOrNull { it.x == nx && it.y == ny }
        if (blockingBomb != null) {
            if (player.kickLevel == 0) return
            var pushX = nx
            var pushY = ny
            for (step in 1..player.kickLevel) {
                pushX += moveDx
                pushY += moveDy
                if (pushX !in board.indices || pushY !in board.indices ||
                    board[pushY][pushX] in setOf(Tile.STEEL, Tile.BRICK, Tile.SAND, Tile.HOLE) ||
                    bombs.any { it !== blockingBomb && it.x == pushX && it.y == pushY } ||
                    (selectedMode !in setOf(GameMode.SOLO, GameMode.SANDBOX) &&
                        players.any { it !== player && it.hp > 0 && it.x == pushX && it.y == pushY })
                ) return
            }
            blockingBomb.x = pushX
            blockingBomb.y = pushY
        }
        if (selectedMode == GameMode.JUGGERNAUT && activePlayer == juggernautPlayer && board[ny][nx] == Tile.BRICK) {
            board[ny][nx] = Tile.FLOOR
            sendToWatch("BREAK:$nx:$ny")
        } else if (board[ny][nx] == Tile.BRICK || board[ny][nx] == Tile.SAND) return
        player.walkFromX = player.x
        player.walkFromY = player.y
        player.x = nx
        player.y = ny
        player.lastMoveAt = now
        player.lastWalkAt = SystemClock.uptimeMillis()
        if ((player.x to player.y) in flameTrails) player.lastFlameDamageAt = SystemClock.uptimeMillis()
        if (player.fireTrailLevel > 0) createFlameTrail(trailPosition, player.fireTrailLevel)
        if (Debuff.POISON in player.activeDebuffs) {
            player.hp = (player.hp - 5).coerceAtLeast(0)
            if (player.hp == 0) {
                finishMatch(if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) null else 1 - activePlayer,
                    "Poison defeated Player ${activePlayer + 1}.")
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
        if (selectedMode == GameMode.SOLO && board[ny][nx] == Tile.KEY) {
            board[ny][nx] = Tile.FLOOR
            soloHasKey = true
            emitParticles(nx, ny,
                intArrayOf(Color.rgb(255, 224, 100), Color.rgb(255, 187, 61), Color.WHITE), 18, 1.7f)
            lastPowerUpMessage = "KEY FOUND · THE DOOR IS OPEN"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 1_700L
            sendToWatch("KEY:1")
        }
        droppedItems.remove(nx to ny)?.let { collectPowerUp(player, it) }
        if (selectedMode == GameMode.SOLO && board[ny][nx] == Tile.DOOR && soloHasKey) {
            enterSoloDoor()
        }
        if (selectedMode == GameMode.RACE && board[ny][nx] == Tile.GOAL) {
            raceWon = true
            finishMatch(activePlayer, "Reached the center goal.")
        }
        updateFlagCapture(activePlayer)
        if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX, GameMode.HELL)) updateMonsters()
        val positionCommand = if (activePlayer == 0) "HOST_POS" else "WATCH_POS"
        if (!handlingRemoteCommand) sendToWatch("$positionCommand:${player.x}:${player.y}")
    }

    private fun placeBomb() {
        if (screen != Screen.GAME || deathAnimationStartedAt != 0L) return
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
        val collectedDebuff = Debuff.fromCode(code)
        val particleColors = if (collectedDebuff != null) {
            intArrayOf(collectedDebuff.color, Color.WHITE)
        } else {
            intArrayOf(PowerUp.fromCode(code)?.color ?: Color.WHITE, Color.WHITE)
        }
        emitParticles(player.x, player.y, particleColors, 10, 1.1f)
        val debuff = Debuff.fromCode(code)
        if (debuff != null) {
            applyDebuff(player, debuff)
            lastPowerUpMessage = if (showFullPowerUpNames) "DEBUFF · ${debuff.displayName}" else "DEBUFF · ${debuff.code}"
            powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
            return
        }
        val powerUp = PowerUp.fromCode(code) ?: return
        when (powerUp) {
            PowerUp.FIRE -> {
                player.blastRange = (player.blastRange + 1).coerceAtMost(8)
                player.fireTrailLevel = (player.fireTrailLevel + 1).coerceAtMost(5)
            }
            PowerUp.BOMB -> player.bombCapacity = (player.bombCapacity + 1).coerceAtMost(5)
            PowerUp.DASH -> player.dashLevel = (player.dashLevel + 1).coerceAtMost(4)
            PowerUp.SHIELD -> player.shieldCharges = (player.shieldCharges + 1).coerceAtMost(3)
            PowerUp.KICK -> player.kickLevel = (player.kickLevel + 1).coerceAtMost(5)
            PowerUp.BLINK -> {
                val destination = randomSafeFloor()
                player.x = destination.first
                player.y = destination.second
            }
            PowerUp.VAMPIRE -> player.vampireLevel = (player.vampireLevel + 1).coerceAtMost(5)
            PowerUp.CLUSTER -> player.clusterLevel = (player.clusterLevel + 1).coerceAtMost(5)
            PowerUp.PIERCE -> player.piercingLevel = (player.piercingLevel + 1).coerceAtMost(5)
            PowerUp.HEART -> {
                val maxHp = when {
                    selectedMode == GameMode.JUGGERNAUT && players.indexOf(player) == juggernautPlayer -> 2250
                    selectedMode == GameMode.HELL -> 100
                    else -> player.spec.maxHp
                }
                player.hp = (player.hp + 50).coerceAtMost(maxHp)
            }
            PowerUp.TRICK -> deployTrickBomb(players.indexOf(player))
        }
        player.collectedPowerUps[code] = (player.collectedPowerUps[code] ?: 0) + 1
        lastPowerUpMessage = if (showFullPowerUpNames) {
            "POWER UP  ·  ${powerUp.displayName}"
        } else {
            "POWER UP  ·  ${powerUp.code}"
        }
        powerUpMessageUntil = SystemClock.uptimeMillis() + 2200L
        if (jumpScaresEnabled && Random.nextInt(100) < 30) {
            jumpScareUntil = SystemClock.uptimeMillis() + 650L
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun createFlameTrail(position: Pair<Int, Int>, level: Int) {
        val (x, y) = position
        if (x !in board.indices || y !in board.indices ||
            board[y][x] !in setOf(Tile.FLOOR, Tile.TELEPORT, Tile.GOAL)
        ) return
        val duration = FIRE_TRAIL_BASE_MS + level * FIRE_TRAIL_STACK_MS
        flameTrails[position] = FlameTrail(x, y, SystemClock.uptimeMillis() + duration)
        sendToWatch("FLAME:$x:$y:$duration")
        removeCallbacks(flameTick)
        postDelayed(flameTick, 200L)
        invalidate()
    }

    private fun updateFlameTrails() {
        val now = SystemClock.uptimeMillis()
        flameTrails.entries.removeAll { it.value.expiresAt <= now }
        for ((index, player) in players.withIndex()) {
            if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX) && index != 0) continue
            if ((player.x to player.y) !in flameTrails) {
                player.lastFlameDamageAt = 0L
                continue
            }
            if (player.lastFlameDamageAt == 0L) {
                player.lastFlameDamageAt = now
            } else if (now - player.lastFlameDamageAt >= FIRE_TRAIL_DAMAGE_INTERVAL_MS) {
                player.lastFlameDamageAt = now
                player.hp = (player.hp - FIRE_TRAIL_DAMAGE).coerceAtLeast(0)
                sendToWatch(if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) "SOLO_HP:${player.hp}"
                    else "PLAYER_HP:$index:${player.hp}")
                if (player.hp == 0) {
                    if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
                        finishMatch(null, "The flames defeated you.")
                    } else {
                        val winner = players.indices.firstOrNull { it != index && players[it].hp > 0 }
                        finishMatch(winner, "Fire eliminated Player ${index + 1}.")
                    }
                    return
                }
            }
        }
        if (flameTrails.isNotEmpty()) invalidate()
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
            finishMatch(if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) null else 1 - activePlayer,
                "A curse defeated Player ${activePlayer + 1}.")
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
        emitParticles(bomb.x, bomb.y,
            intArrayOf(Color.rgb(255, 241, 161), Color.rgb(255, 164, 57), Color.rgb(255, 83, 39)),
            20, 2.8f)
        for ((dx, dy) in directions) {
            var bricksPierced = 0
            for (step in 1..range) {
                val x = bomb.x + dx * step
                val y = bomb.y + dy * step
                if (x !in board.indices || y !in board.indices || board[y][x] == Tile.STEEL) break
                affectedCells.add(x to y)
                if (board[y][x] == Tile.BRICK || board[y][x] == Tile.SAND) {
                    board[y][x] = Tile.FLOOR
                    emitParticles(x, y,
                        intArrayOf(Color.rgb(218, 112, 78), Color.rgb(131, 61, 46), Color.rgb(240, 178, 126)),
                        8, 1.4f)
                    sendToWatch("BREAK:$x:$y")
                    monsters.firstOrNull { it.x == x && it.y == y && it.hidden }?.let {
                        it.hidden = false
                        it.lastMovedAt = now
                        newlyRevealed.add(x to y)
                    }
                    val baseDropChance = when (selectedMode) {
                        GameMode.CARNAGE -> 85
                        GameMode.CASUAL, GameMode.JUGGERNAUT, GameMode.SOLO, GameMode.SANDBOX -> 45
                        GameMode.HARDCORE -> 35
                        GameMode.CAPTURE_THE_FLAG -> 55
                        GameMode.RACE, GameMode.SPLEEF -> 35
                        GameMode.HELL -> 85
                    }
                    val dropChance = if (selectedMode == GameMode.HELL) {
                        (baseDropChance * 10).coerceAtMost(100)
                    } else baseDropChance
                    if (Random.nextInt(100) < dropChance) {
                        val code = if (selectedMode != GameMode.HELL && Random.nextInt(100) < 25) {
                            Debuff.entries.random().code
                        } else {
                            powerUps.random().code
                        }
                        droppedItems[x to y] = code
                        sendToWatch("DROP:$x:$y:$code")
                    }
                    if (owner.piercingLevel <= bricksPierced) break
                    bricksPierced++
                }
            }
        }
        if (owner.clusterLevel > 0) {
            for ((dx, dy) in directions) {
                for (distance in 2..(owner.clusterLevel + 1)) {
                    val x = bomb.x + dx * distance
                    val y = bomb.y + dy * distance
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
        }
        bombs.filter { it != bomb && (it.x to it.y) in affectedCells }.toList().forEach { chained ->
            if (!fromChain) sendToWatch("CHAIN:${chained.x}:${chained.y}")
            post { detonate(chained, fromChain = true) }
        }
        if (selectedMode == GameMode.SPLEEF) {
            affectedCells.forEach { (x, y) -> if (x in board.indices && y in board.indices) board[y][x] = Tile.HOLE }
            if (Random.nextInt(100) < 35) {
                val candidates = board.indices.flatMap { y ->
                    board[y].indices.mapNotNull { x ->
                        if (board[y][x] == Tile.FLOOR &&
                            players.none { it.x == x && it.y == y } &&
                            bombs.none { it.x == x && it.y == y } &&
                            !droppedItems.containsKey(x to y)
                        ) x to y else null
                    }
                }
                candidates.randomOrNull()?.let { (x, y) ->
                    val code = if (Random.nextInt(100) < 25) Debuff.entries.random().code
                    else powerUps.random().code
                    droppedItems[x to y] = code
                    sendToWatch("DROP:$x:$y:$code")
                }
            }
        }
        if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
            monsters.filter { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
                .forEach { emitParticles(it.x, it.y,
                    intArrayOf(Color.rgb(181, 231, 112), Color.rgb(108, 175, 86), Color.WHITE), 12, 1.7f) }
            monsters.removeAll { !it.hidden && (it.x to it.y) in affectedCells && (it.x to it.y) !in newlyRevealed }
        }
        val hitPlayers = players.mapIndexed { index, player ->
            index to (player.hp > 0 && (selectedMode !in setOf(GameMode.SOLO, GameMode.SANDBOX) || index == 0) &&
                (player.x to player.y) in affectedCells)
        }.filter { it.second }.map { it.first }
        if (selectedMode == GameMode.SPLEEF && hitPlayers.isNotEmpty()) {
            val winner = if (hitPlayers.size == 2) null else 1 - hitPlayers.single()
            finishMatch(winner, "A blast broke the floor under a player.")
        } else {
            hitPlayers.forEach { index ->
                val player = players[index]
                flags.firstOrNull { it.carrier == index }?.let { carriedFlag ->
                    carriedFlag.carrier = null
                    carriedFlag.x = player.x
                    carriedFlag.y = player.y
                }
                if (player.shieldCharges > 0) {
                    player.shieldCharges--
                } else if (selectedMode == GameMode.RACE) {
                    player.x = player.spawnX
                    player.y = player.spawnY
                    sendToWatch("${if (index == 0) "HOST_POS" else "WATCH_POS"}:${player.x}:${player.y}")
                } else {
                    val damage = if (Debuff.FRAGILE in player.activeDebuffs && player.vulnerableHits > 0) 100 else 50
                    if (player.vulnerableHits > 0) {
                        player.vulnerableHits--
                        if (player.vulnerableHits == 0) player.activeDebuffs.remove(Debuff.FRAGILE)
                    }
                    player.hp = (player.hp - damage).coerceAtLeast(0)
                }
                if (index != bomb.owner && owner.vampireLevel > 0) {
                    val maxHp = when {
                        selectedMode == GameMode.JUGGERNAUT && bomb.owner == juggernautPlayer -> 2250
                        selectedMode == GameMode.HELL -> 100
                        else -> owner.spec.maxHp
                    }
                    owner.hp = (owner.hp + 20 * owner.vampireLevel).coerceAtMost(maxHp)
                }
            }
            sendFlagState()
            if (selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX) && players[0].hp <= 0) {
                finishMatch(null, "Player 1 was eliminated.")
            } else if (selectedMode !in setOf(GameMode.SOLO, GameMode.SANDBOX)) {
                val defeated = players.indices.filter { players[it].hp <= 0 }
                if (defeated.isNotEmpty()) {
                    val winner = if (defeated.size == 2) null else 1 - defeated.single()
                    finishMatch(winner, "A player was eliminated.")
                }
            }
        }
        bombs.remove(bomb)
        if ((selectedMode in setOf(GameMode.SOLO, GameMode.SANDBOX, GameMode.HELL)) &&
            screen == Screen.GAME
        ) updateMonsters(affectedCells - newlyRevealed)
        invalidate()
    }

    private fun emptyBoard(size: Int, seed: Long = mapSeed): Array<Array<Tile>> {
        val random = JavaRandom(seed)
        return Array(size) { y -> Array(size) { x ->
        when {
            x == 0 || y == 0 || x == size - 1 || y == size - 1 || (x % 2 == 0 && y % 2 == 0) -> Tile.STEEL
            random.nextInt(100) < (if (selectedMode == GameMode.CARNAGE || selectedMode == GameMode.HELL) 55 else 40) -> Tile.BRICK
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
    }

    private fun encodeBoard(): String = buildString {
        board.forEach { row ->
            row.forEach { tile ->
                append(
                    when (tile) {
                        Tile.FLOOR -> 'F'
                        Tile.STEEL -> 'S'
                        Tile.BRICK -> 'B'
                        Tile.SAND -> 'A'
                        Tile.TELEPORT -> 'T'
                        Tile.GOAL -> 'G'
                        Tile.HOLE -> 'H'
                        Tile.KEY -> 'K'
                        Tile.DOOR -> 'D'
                    }
                )
            }
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.rgb(5, 9, 17))
        val time = SystemClock.uptimeMillis() / 1_000f
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
            val baseY = ((index * 137 + 59) % 991) / 991f * height
            val drift = (time * (3f + index % 4) + index * 17f) % maxOf(1f, height.toFloat())
            val y = (baseY - drift + height) % height
            val alpha = (18 + index % 4 * 7 + 7 * kotlin.math.sin(time * 1.7f + index)).toInt()
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
            Tile.SAND -> {
                paint.color = Color.rgb(112, 78, 40)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(204, 157, 83)
                canvas.drawRoundRect(left + cell * .06f, top + cell * .06f,
                    right - cell * .06f, bottom - cell * .08f, cell * .055f, cell * .055f, paint)
                paint.color = Color.argb(150, 247, 211, 139)
                canvas.drawLine(left + cell * .16f, top + cell * .28f,
                    right - cell * .16f, top + cell * .28f, paint)
                canvas.drawLine(left + cell * .16f, top + cell * .65f,
                    right - cell * .16f, top + cell * .65f, paint)
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
            Tile.KEY -> {
                paint.color = Color.rgb(37, 54, 57)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(255, 215, 83)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, cell * .09f)
                canvas.drawCircle(rect.centerX() - cell * .08f, rect.centerY() - cell * .04f,
                    cell * .2f, paint)
                canvas.drawLine(rect.centerX() + cell * .1f, rect.centerY() + cell * .08f,
                    rect.centerX() + cell * .29f, rect.centerY() + cell * .27f, paint)
                canvas.drawLine(rect.centerX() + cell * .22f, rect.centerY() + cell * .2f,
                    rect.centerX() + cell * .29f, rect.centerY() + cell * .12f, paint)
                paint.style = Paint.Style.FILL
            }
            Tile.DOOR -> {
                paint.color = Color.rgb(64, 42, 33)
                canvas.drawRoundRect(rect, cell * .08f, cell * .08f, paint)
                paint.color = Color.rgb(160, 101, 57)
                bounds.set(left + cell * .2f, top + cell * .1f, right - cell * .2f, bottom - cell * .08f)
                canvas.drawRoundRect(bounds, cell * .06f, cell * .06f, paint)
                paint.color = Color.rgb(230, 188, 108)
                canvas.drawCircle(right - cell * .32f, rect.centerY(), cell * .045f, paint)
                if (!soloHasKey) {
                    paint.color = Color.rgb(255, 205, 90)
                    canvas.drawRect(rect.centerX() - cell * .18f, rect.centerY() - cell * .035f,
                        rect.centerX() + cell * .18f, rect.centerY() + cell * .035f, paint)
                }
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

    private fun emitParticles(
        gridX: Int,
        gridY: Int,
        colors: IntArray,
        count: Int,
        speed: Float
    ) {
        val now = SystemClock.uptimeMillis()
        repeat(count) {
            val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val velocity = speed * (.35f + Random.nextFloat())
            particles.add(
                GameParticle(
                    gridX + .5f,
                    gridY + .5f,
                    kotlin.math.cos(angle) * velocity,
                    kotlin.math.sin(angle) * velocity,
                    colors.random(),
                    .045f + Random.nextFloat() * .075f,
                    now,
                    420L + Random.nextLong(0L, 381L)
                )
            )
        }
        while (particles.size > 320) particles.removeAt(0)
    }

    private fun drawParticles(canvas: Canvas, left: Float, top: Float, cell: Float) {
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.clipRect(left, top, left + cell * board.size, top + cell * board.size)
        paint.style = Paint.Style.FILL
        var index = particles.lastIndex
        while (index >= 0) {
            val particle = particles[index]
            val progress = (now - particle.bornAt).toFloat() / particle.lifetime
            if (progress >= 1f) {
                particles.removeAt(index)
            } else {
                val elapsed = (now - particle.bornAt) / 1_000f
                val x = left + (particle.x + particle.velocityX * elapsed) * cell
                val y = top + (particle.y + particle.velocityY * elapsed + .75f * elapsed * elapsed) * cell
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
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawOval(cx - radius * .62f, cy + radius * .78f, cx + radius * .62f,
            cy + radius * .98f, paint)
        paint.color = Color.rgb(25, 34, 48)
        canvas.drawRoundRect(cx + radius * .25f, cy - radius * .15f,
            cx + radius * .52f, cy + radius * .42f, radius * .08f, radius * .08f, paint)
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRoundRect(cx + radius * .31f, cy - radius * .08f,
            cx + radius * .35f, cy + radius * .3f, radius * .025f, radius * .025f, paint)

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
        paint.color = Color.rgb(30, 42, 57)
        canvas.drawCircle(cx - radius * (.48f - walk * .2f),
            cy + radius * (.28f + walk * .08f), radius * .13f, paint)
        canvas.drawCircle(cx + radius * (.48f + walk * .2f),
            cy + radius * (.28f - walk * .08f), radius * .13f, paint)
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
        paint.color = Color.argb(55, 255, 255, 255)
        canvas.drawRoundRect(cx - radius * .23f, cy - radius * .12f,
            cx + radius * .23f, cy + radius * .12f, radius * .06f, radius * .06f, paint)
        paint.color = Color.argb(165, 16, 29, 43)
        canvas.drawLine(cx - radius * .15f, cy - radius * .1f,
            cx + radius * .15f, cy + radius * .11f, paint)
        paint.color = Color.rgb(255, 211, 105)
        canvas.drawCircle(cx + radius * .12f, cy - radius * .06f, radius * .045f, paint)
        paint.color = Color.argb(190, 10, 22, 31)
        canvas.drawRect(bounds.left + radius * .02f, cy + radius * .2f,
            bounds.right - radius * .02f, cy + radius * .31f, paint)
        paint.color = Color.rgb(255, 202, 94)
        canvas.drawRoundRect(cx - radius * .075f, cy + radius * .205f,
            cx + radius * .075f, cy + radius * .305f, radius * .025f, radius * .025f, paint)
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawLine(cx, cy - radius * .1f, cx, cy + radius * .14f,
            paint.apply { strokeWidth = maxOf(1f, radius * .025f) })
        paint.color = Color.rgb(13, 23, 33)
        canvas.drawCircle(cx - radius * .46f, cy + radius * .27f, radius * .105f, paint)
        canvas.drawCircle(cx + radius * .46f, cy + radius * .27f, radius * .105f, paint)
        paint.color = Color.rgb(27, 35, 44)
        canvas.drawOval(cx - radius * .31f, cy + radius * .75f, cx - radius * .05f,
            cy + radius * .9f, paint)
        canvas.drawOval(cx + radius * .05f, cy + radius * .75f, cx + radius * .31f,
            cy + radius * .9f, paint)
        paint.color = Color.rgb(25, 34, 48)
        canvas.drawRoundRect(cx - radius * .34f, cy + radius * .74f,
            cx - radius * .04f, cy + radius * .96f, radius * .07f, radius * .07f, paint)
        canvas.drawRoundRect(cx + radius * .04f, cy + radius * .74f,
            cx + radius * .34f, cy + radius * .96f, radius * .07f, radius * .07f, paint)
        paint.color = Color.rgb(255, 198, 71)
        canvas.drawRect(cx - radius * .28f, cy + radius * .82f,
            cx - radius * .1f, cy + radius * .86f, paint)
        canvas.drawRect(cx + radius * .1f, cy + radius * .82f,
            cx + radius * .28f, cy + radius * .86f, paint)
        paint.color = Color.rgb(21, 31, 45)
        canvas.drawCircle(cx, cy - radius * .48f, radius * .29f, paint)
        paint.color = color
        canvas.drawCircle(cx, cy - radius * .48f, radius * .24f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * .04f)
        paint.color = Color.argb(190, 9, 20, 31)
        canvas.drawCircle(cx, cy - radius * .48f, radius * .24f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(215, 8, 24, 39)
        canvas.drawRoundRect(cx - radius * .19f, cy - radius * .59f,
            cx + radius * .19f, cy - radius * .39f, radius * .06f, radius * .06f, paint)
        paint.color = Color.argb(150, 125, 232, 255)
        canvas.drawRoundRect(cx - radius * .15f, cy - radius * .57f,
            cx + radius * .15f, cy - radius * .53f, radius * .03f, radius * .03f, paint)
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
