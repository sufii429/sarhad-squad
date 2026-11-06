package app.sarhadsquad.android

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ============================================================================
// Sarhad Squad: Border War — Mission 1 prototype (v0.4.0)
// Pixel-art squad shooter. TITLE -> GAME -> RESULT state machine.
// v0.4.0 adds the Stage-1 Feel Pack: visible projectiles, explosion
// feedback + scorch marks, Canvas damage numbers, combo counter, range
// ring, floating joystick, crate pickups, hit flashes, dust puffs.
// All in-game text is English only.
// ============================================================================

enum class Faction { PAKISTAN, INDIA }

enum class Screen { TITLE, GAME, RESULT }

// Effect kinds (drawn in the Canvas, removed when ttl runs out).
// Tracers are gone in v0.4.0 — fire is carried by real Bullet entities.
const val FX_HIT = 0
const val FX_BLAST = 1

// Particle kinds for the feel-pack particle list.
const val PT_SPARK = 0
const val PT_FLAME = 1
const val PT_DUST = 2

// Pickup kinds dropped by destroyed crates.
const val PICKUP_MEDKIT = 0
const val PICKUP_RAPID = 1

fun distBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

fun formatTime(totalSeconds: Float): String {
    val s = totalSeconds.toInt()
    val m = s / 60
    val r = s % 60
    return m.toString().padStart(2, '0') + ":" + r.toString().padStart(2, '0')
}

fun uniformColor(faction: Faction): Color = when (faction) {
    Faction.PAKISTAN -> Color(0xFF2E5B27) // dark green
    Faction.INDIA -> Color(0xFF6B7040) // olive / khaki
}

// --- Game entities (plain classes; the frame tick drives recomposition) -----

class Member(val name: String, val fireInterval: Float, val damage: Float) {
    var x = 0f
    var y = 0f
    var offX = 0f
    var offY = 0f
    var hp = 100f
    val maxHp = 100f
    var cd = 0f
    var down = false
    var wasDowned = false
}

class Enemy(val ax: Float, val ay: Float, val phase: Float) {
    var x = ax
    var y = ay
    var hp = 48f
    val maxHp = 48f
    var cd = 0f
    var alive = true
    var flash = 0f // hit-flash timer (white tint while > 0)
}

class CrateEnt(var x: Float, var y: Float) {
    var hp = 45f
    val maxHp = 45f
    var destroyed = false
    var flash = 0f // hit-flash timer (white tint while > 0)
}

class Obstacle(var x: Float, var y: Float, val r: Float, val tree: Boolean)

class Effect(
    val kind: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    var ttl: Float,
    val maxTtl: Float
)

/** A travelling projectile. Damage lands on arrival / proximity, not on fire. */
class Bullet(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val dirX: Float,
    val dirY: Float,
    val endX: Float,
    val endY: Float,
    val fromEnemy: Boolean,
    val damage: Float,
    val targetEnemy: Enemy? = null,
    val targetCrate: CrateEnt? = null,
    val targetMember: Member? = null
)

/** One feel-pack particle square (explosion spark, lingering flame, dust). */
class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var ttl: Float,
    val maxTtl: Float,
    val size: Float,
    val color: Color,
    val kind: Int
)

/** World-anchored floating text, drawn inside the Canvas (damage numbers). */
class FloatText(
    var x: Float,
    var y: Float,
    val text: String,
    var ttl: Float,
    val maxTtl: Float,
    val size: Float,
    val argb: Int
)

/** Permanent dark scorch patch left on the ground by an explosion. */
class Scorch(val x: Float, val y: Float, val r: Float, val seed: Int)

/** A dead enemy's figure, fading out over 0.25 s instead of vanishing. */
class DeathFade(val x: Float, val y: Float, val facing: Offset, var ttl: Float)

/** A pickup dropped by a destroyed crate, waiting on the ground. */
class Pickup(val x: Float, val y: Float, val kind: Int, val phase: Float)

class GameWorld {
    var initialized = false
    var finished = false
    var w = 0f
    var h = 0f
    var anchorX = 0f
    var anchorY = 0f
    var elapsed = 0f
    var grenadeCd = 0f
    val members = mutableListOf<Member>()
    val enemies = mutableListOf<Enemy>()
    val crates = mutableListOf<CrateEnt>()
    val obstacles = mutableListOf<Obstacle>()
    val effects = mutableListOf<Effect>()
    // Feel-pack state (v0.4.0).
    val bullets = mutableListOf<Bullet>()
    val particles = mutableListOf<Particle>()
    val floaters = mutableListOf<FloatText>()
    val scorches = mutableListOf<Scorch>()
    val deaths = mutableListOf<DeathFade>()
    val pickups = mutableListOf<Pickup>()
    var combo = 0
    var comboTimer = 0f
    var rapidTimer = 0f
    var shake = 0f
    var dustTimer = 0f
    var pickupAlt = false
    var flashText = ""
    var flashTtl = 0f
}

/** Spawn everything as fractions of the canvas size, once the size is known. */
fun initWorld(world: GameWorld, w: Float, h: Float) {
    world.w = w
    world.h = h
    world.anchorX = w * 0.50f
    world.anchorY = h * 0.82f

    val hawk = Member("HAWK", 0.35f, 9f)
    hawk.offX = 0f; hawk.offY = 0f
    val bull = Member("BULL", 0.18f, 4f)
    bull.offX = -46f; bull.offY = 38f
    val doc = Member("DOC", 0.50f, 6f)
    doc.offX = 46f; doc.offY = 38f
    for (m in listOf(hawk, bull, doc)) {
        m.x = world.anchorX + m.offX
        m.y = world.anchorY + m.offY
        world.members.add(m)
    }

    val enemySpots = listOf(
        0.18f to 0.30f, 0.42f to 0.26f, 0.66f to 0.32f, 0.30f to 0.44f,
        0.55f to 0.47f, 0.78f to 0.42f, 0.24f to 0.62f, 0.62f to 0.64f
    )
    enemySpots.forEachIndexed { i, spot ->
        world.enemies.add(Enemy(w * spot.first, h * spot.second, i * 1.37f))
    }

    val crateSpots = listOf(0.35f to 0.34f, 0.58f to 0.55f, 0.72f to 0.66f)
    for (spot in crateSpots) {
        world.crates.add(CrateEnt(w * spot.first, h * spot.second))
    }

    // Trees (cover) and rocks (cover), as collision circles.
    val trees = listOf(
        Triple(0.12f, 0.40f, 26f), Triple(0.48f, 0.36f, 30f),
        Triple(0.80f, 0.58f, 26f), Triple(0.38f, 0.70f, 28f),
        Triple(0.66f, 0.22f, 24f)
    )
    for (t in trees) world.obstacles.add(Obstacle(w * t.first, h * t.second, t.third, true))
    val rocks = listOf(
        Triple(0.20f, 0.52f, 18f), Triple(0.52f, 0.62f, 20f), Triple(0.74f, 0.34f, 16f)
    )
    for (r in rocks) world.obstacles.add(Obstacle(w * r.first, h * r.second, r.third, false))

    world.initialized = true
}

/** Push the squad anchor out of an obstacle / live crate circle. */
fun pushAnchorOut(world: GameWorld) {
    for (o in world.obstacles) {
        val d = distBetween(world.anchorX, world.anchorY, o.x, o.y)
        val minD = o.r + 16f
        if (d < minD && d > 0.01f) {
            world.anchorX = o.x + (world.anchorX - o.x) / d * minD
            world.anchorY = o.y + (world.anchorY - o.y) / d * minD
        }
    }
    for (c in world.crates) {
        if (c.destroyed) continue
        val d = distBetween(world.anchorX, world.anchorY, c.x, c.y)
        val minD = 30f + 16f
        if (d < minD && d > 0.01f) {
            world.anchorX = c.x + (world.anchorX - c.x) / d * minD
            world.anchorY = c.y + (world.anchorY - c.y) / d * minD
        }
    }
}

/** Push one follower out of obstacle / live crate circles. */
fun pushMemberOut(world: GameWorld, m: Member) {
    for (o in world.obstacles) {
        val d = distBetween(m.x, m.y, o.x, o.y)
        val minD = o.r + 15f
        if (d < minD && d > 0.01f) {
            m.x = o.x + (m.x - o.x) / d * minD
            m.y = o.y + (m.y - o.y) / d * minD
        }
    }
    for (c in world.crates) {
        if (c.destroyed) continue
        val d = distBetween(m.x, m.y, c.x, c.y)
        val minD = 30f + 15f
        if (d < minD && d > 0.01f) {
            m.x = c.x + (m.x - c.x) / d * minD
            m.y = c.y + (m.y - c.y) / d * minD
        }
    }
}

fun moveEnemyToward(e: Enemy, tx: Float, ty: Float, speed: Float, dt: Float) {
    val d = distBetween(e.x, e.y, tx, ty)
    if (d < 2f) return
    val stepLen = min(d, speed * dt)
    e.x += (tx - e.x) / d * stepLen
    e.y += (ty - e.y) / d * stepLen
}

// --- Feel-pack helpers (v0.4.0): every damage path funnels through here ----

/** Rising, fading world-anchored text (drawn later inside the Canvas). */
fun addFloater(
    world: GameWorld, x: Float, y: Float, text: String,
    size: Float = 26f, argb: Long = 0xFFFFFFFF, ttl: Float = 0.7f
) {
    world.floaters.add(FloatText(x, y, text, ttl, ttl, size, argb.toInt()))
}

/** Expanding fire cluster + a few lingering flames at (x, y). */
fun spawnExplosion(world: GameWorld, x: Float, y: Float, big: Boolean) {
    val fireColors = listOf(
        Color(0xFFFF5722), Color(0xFFFF9800), Color(0xFFFFC107), Color(0xFFD32F2F)
    )
    val sparks = if (big) 16 else 10
    for (i in 0 until sparks) {
        val ang = Random.nextFloat() * (2f * Math.PI.toFloat())
        val spd = 40f + Random.nextFloat() * 140f
        val ttl = 0.18f + Random.nextFloat() * 0.17f
        world.particles.add(
            Particle(
                x, y - 20f, cos(ang.toDouble()).toFloat() * spd,
                sin(ang.toDouble()).toFloat() * spd,
                ttl, ttl, 5f + Random.nextFloat() * 6f,
                fireColors[i % fireColors.size], PT_SPARK
            )
        )
    }
    val flames = if (big) 6 else 4
    for (i in 0 until flames) {
        world.particles.add(
            Particle(
                x + (Random.nextFloat() - 0.5f) * 44f,
                y - 8f + (Random.nextFloat() - 0.5f) * 20f,
                0f, -12f, 0.8f, 0.8f,
                6f + Random.nextFloat() * 6f,
                if (i % 2 == 0) Color(0xFFFF9800) else Color(0xFFFFC107), PT_FLAME
            )
        )
    }
}

/** Smaller red/dark burst when an enemy dies. */
fun spawnDeathBurst(world: GameWorld, x: Float, y: Float) {
    val cols = listOf(Color(0xFFD32F2F), Color(0xFF7B1FA2), Color(0xFF37474F))
    for (i in 0 until 8) {
        val ang = Random.nextFloat() * (2f * Math.PI.toFloat())
        val spd = 30f + Random.nextFloat() * 90f
        val ttl = 0.15f + Random.nextFloat() * 0.1f
        world.particles.add(
            Particle(
                x, y - 40f, cos(ang.toDouble()).toFloat() * spd,
                sin(ang.toDouble()).toFloat() * spd,
                ttl, ttl, 4f + Random.nextFloat() * 4f,
                cols[i % cols.size], PT_SPARK
            )
        )
    }
}

/** Combo bookkeeping: kills inside a 2.5 s window chain the counter. */
fun registerKill(world: GameWorld) {
    world.combo = if (world.comboTimer > 0f) world.combo + 1 else 1
    world.comboTimer = 2.5f
}

fun killEnemy(world: GameWorld, e: Enemy) {
    e.hp = 0f
    e.alive = false
    val nearest = world.members
        .filter { !it.down }
        .minByOrNull { distBetween(e.x, e.y, it.x, it.y) }
    val facing = if (nearest != null) {
        facingToward(e.x, e.y, nearest.x, nearest.y, Offset(0f, 1f))
    } else Offset(0f, 1f)
    world.deaths.add(DeathFade(e.x, e.y, facing, 0.25f))
    spawnDeathBurst(world, e.x, e.y)
    registerKill(world)
}

/** Damage to an enemy from any source; shows the floating damage number. */
fun damageEnemy(world: GameWorld, e: Enemy, dmg: Float) {
    if (!e.alive) return
    e.hp -= dmg
    e.flash = 0.06f
    if (e.hp <= 0f) {
        addFloater(world, e.x, e.y - 78f, dmg.toInt().toString(), 36f, 0xFFFFEB3B)
        killEnemy(world, e)
    } else {
        addFloater(world, e.x, e.y - 78f, dmg.toInt().toString(), 26f, 0xFFFFFFFF)
    }
    world.effects.add(Effect(FX_HIT, e.x, e.y, e.x, e.y, 0.12f, 0.12f))
}

/** Full destruction feedback for a crate: explosion, scorch, shake, drop. */
fun onCrateDestroyed(world: GameWorld, c: CrateEnt) {
    spawnExplosion(world, c.x, c.y, big = true)
    world.scorches.add(Scorch(c.x, c.y + 8f, 44f, (c.x * 7f + c.y).toInt()))
    world.shake = max(world.shake, 8f)
    if (Random.nextFloat() < 0.5f) {
        world.pickupAlt = !world.pickupAlt
        val kind = if (world.pickupAlt) PICKUP_MEDKIT else PICKUP_RAPID
        world.pickups.add(Pickup(c.x, c.y, kind, Random.nextFloat() * 6.28f))
    }
}

/** Damage to a crate from any source; shows the floating damage number. */
fun damageCrate(world: GameWorld, c: CrateEnt, dmg: Float) {
    if (c.destroyed) return
    c.hp -= dmg
    c.flash = 0.06f
    if (c.hp <= 0f) {
        c.hp = 0f
        c.destroyed = true
        addFloater(world, c.x, c.y - 52f, dmg.toInt().toString(), 36f, 0xFFFFEB3B)
        onCrateDestroyed(world, c)
    } else {
        addFloater(world, c.x, c.y - 52f, dmg.toInt().toString(), 26f, 0xFFFFFFFF)
    }
}

/** Damage to a squad member from any source; downs them at zero HP. */
fun damageMember(world: GameWorld, m: Member, dmg: Float) {
    if (m.down) return
    m.hp -= dmg
    addFloater(world, m.x, m.y - 84f, dmg.toInt().toString(), 26f, 0xFFFF8A80)
    if (m.hp <= 0f) {
        m.hp = 0f
        m.down = true
        m.wasDowned = true
    }
}

// --- Activity + app root -----------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SarhadSquadApp()
            }
        }
    }
}

@Composable
fun SarhadSquadApp() {
    var screen by remember { mutableStateOf(Screen.TITLE) }
    var faction by remember { mutableStateOf(Faction.PAKISTAN) }
    var session by remember { mutableIntStateOf(0) }
    var victory by remember { mutableStateOf(false) }
    var stars by remember { mutableIntStateOf(0) }
    var commandPoints by remember { mutableIntStateOf(0) }
    var finalTime by remember { mutableFloatStateOf(0f) }

    when (screen) {
        Screen.TITLE -> TitleScreen(
            faction = faction,
            onFaction = { faction = it },
            onStart = { session++; screen = Screen.GAME }
        )
        Screen.GAME -> key(session) {
            GameScreen(faction = faction) { v, s, cp, t ->
                victory = v
                stars = s
                commandPoints = cp
                finalTime = t
                screen = Screen.RESULT
            }
        }
        Screen.RESULT -> ResultScreen(
            victory = victory,
            stars = stars,
            commandPoints = commandPoints,
            timeSec = finalTime,
            onRetry = { session++; screen = Screen.GAME },
            onTitle = { screen = Screen.TITLE }
        )
    }
}

// --- TITLE -------------------------------------------------------------------

@Composable
fun TitleScreen(faction: Faction, onFaction: (Faction) -> Unit, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF22331A), Color(0xFF101A0B))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "SARHAD SQUAD",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "BORDER WAR",
                color = Color(0xFFFFC107),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Hold the Line.",
                color = Color(0xFFCFD8C3),
                fontSize = 16.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))
            Text(
                "CHOOSE YOUR SIDE",
                color = Color(0xFF9DB38F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onFaction(Faction.PAKISTAN) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (faction == Faction.PAKISTAN)
                            Color(0xFF046A38) else Color(0xFF3A4A38)
                    )
                ) { Text("PAKISTAN") }
                Button(
                    onClick = { onFaction(Faction.INDIA) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (faction == Faction.INDIA)
                            Color(0xFFB26A1B) else Color(0xFF3A4A38)
                    )
                ) { Text("INDIA") }
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) { Text("START MISSION 1 — DAWN PATROL", fontSize = 15.sp) }
            Spacer(Modifier.height(16.dp))
            Text(
                "Mission: Dawn Patrol — clear 8 smuggler scouts and destroy 3 supply crates.",
                color = Color(0xFF9DB38F),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// --- RESULT ------------------------------------------------------------------

@Composable
fun ResultScreen(
    victory: Boolean,
    stars: Int,
    commandPoints: Int,
    timeSec: Float,
    onRetry: () -> Unit,
    onTitle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101A0B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (victory) "MISSION COMPLETE" else "MISSION FAILED",
                color = if (victory) Color(0xFF8BC34A) else Color(0xFFEF5350),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (victory) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "★".repeat(stars) + "☆".repeat(3 - stars),
                    color = Color(0xFFFFC107),
                    fontSize = 34.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Command Points earned: $commandPoints",
                    color = Color.White,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Time: ${formatTime(timeSec)}", color = Color(0xFFCFD8C3), fontSize = 15.sp)
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF046A38))
            ) { Text(if (victory) "PLAY AGAIN" else "TRY AGAIN") }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTitle,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A4A38))
            ) { Text("TITLE") }
        }
    }
}

// --- GAME --------------------------------------------------------------------

@Composable
fun GameScreen(faction: Faction, onFinish: (Boolean, Int, Int, Float) -> Unit) {
    val world = remember { GameWorld() }
    val art = remember { buildPixelArt() }
    var frameTick by remember { mutableIntStateOf(0) }
    var holdOn by remember { mutableStateOf(false) }
    var joyVec by remember { mutableStateOf(Offset.Zero) }
    var joyBase by remember { mutableStateOf<Offset?>(null) }

    fun throwGrenade() {
        if (!world.initialized || world.finished || world.grenadeCd > 0f) return
        // Target the densest cluster of live enemies within blast reach.
        var bestCenter: Offset? = null
        var bestCount = -1
        for (e in world.enemies) {
            if (!e.alive) continue
            val count = world.enemies.count {
                it.alive && distBetween(e.x, e.y, it.x, it.y) <= 115f
            }
            if (count > bestCount) {
                bestCount = count
                bestCenter = Offset(e.x, e.y)
            }
        }
        val center = bestCenter
            ?: world.crates.firstOrNull { !it.destroyed }?.let { Offset(it.x, it.y) }
            ?: return
        for (e in world.enemies) {
            if (e.alive && distBetween(center.x, center.y, e.x, e.y) <= 115f) {
                damageEnemy(world, e, 60f)
            }
        }
        for (c in world.crates) {
            if (!c.destroyed && distBetween(center.x, center.y, c.x, c.y) <= 115f) {
                damageCrate(world, c, 60f)
            }
        }
        world.effects.add(Effect(FX_BLAST, center.x, center.y, center.x, center.y, 0.35f, 0.35f))
        spawnExplosion(world, center.x, center.y, big = true)
        world.scorches.add(Scorch(center.x, center.y, 52f, (center.x * 3f + center.y).toInt()))
        world.shake = max(world.shake, 8f)
        world.grenadeCd = 6f
    }

    fun handleTap(p: Offset) {
        if (!world.initialized || world.finished) return
        val doc = world.members.getOrNull(2) ?: return
        if (doc.down) return // No medic alive — no revive possible.
        val downed = world.members.firstOrNull {
            it.down && distBetween(it.x, it.y, p.x, p.y) < 70f
        } ?: return
        // The medic must be close to the fallen member.
        if (distBetween(doc.x, doc.y, downed.x, downed.y) <= 120f) {
            downed.down = false
            downed.hp = downed.maxHp * 0.5f
        }
    }

    fun stepGame(dt: Float) {
        if (!world.initialized || world.finished) return
        world.elapsed += dt
        if (world.grenadeCd > 0f) world.grenadeCd = max(0f, world.grenadeCd - dt)

        // -- Feel-pack timers. --
        if (world.rapidTimer > 0f) world.rapidTimer = max(0f, world.rapidTimer - dt)
        if (world.comboTimer > 0f) {
            world.comboTimer = max(0f, world.comboTimer - dt)
            if (world.comboTimer <= 0f) world.combo = 0
        }
        if (world.flashTtl > 0f) world.flashTtl = max(0f, world.flashTtl - dt)
        if (world.shake > 0f) world.shake = max(0f, world.shake - dt * 30f)
        for (e in world.enemies) if (e.flash > 0f) e.flash = max(0f, e.flash - dt)
        for (c in world.crates) if (c.flash > 0f) c.flash = max(0f, c.flash - dt)

        // -- Squad movement: anchor moves, members follow with offsets. --
        var squadMovingNow = false
        if (!holdOn) {
            val len = joyVec.getDistance()
            if (len > 3f) {
                val mag = (len / 60f).coerceIn(0f, 1f)
                world.anchorX += joyVec.x / len * 180f * mag * dt
                world.anchorY += joyVec.y / len * 180f * mag * dt
                squadMovingNow = true
            }
        }
        world.anchorX = world.anchorX.coerceIn(28f, world.w - 28f)
        world.anchorY = world.anchorY.coerceIn(70f, world.h - 28f)
        pushAnchorOut(world)
        for (m in world.members) {
            if (m.down) continue
            val tx = world.anchorX + m.offX
            val ty = world.anchorY + m.offY
            if (m.name == "HAWK") {
                m.x = tx; m.y = ty
            } else {
                val stiff = (if (m.name == "BULL") 5.5f else 7.5f) * 1.25f
                val t = min(1f, dt * stiff)
                m.x += (tx - m.x) * t
                m.y += (ty - m.y) * t
            }
            pushMemberOut(world, m)
        }
        // Dust puffs at the squad's feet while on the move.
        if (squadMovingNow) {
            world.dustTimer -= dt
            if (world.dustTimer <= 0f) {
                world.dustTimer = 0.12f
                for (m in world.members) {
                    if (m.down) continue
                    world.particles.add(
                        Particle(
                            m.x + (Random.nextFloat() - 0.5f) * 18f, m.y + 4f,
                            (Random.nextFloat() - 0.5f) * 14f, -9f,
                            0.4f, 0.4f, 7f + Random.nextFloat() * 6f,
                            Color(0xFFD6C29A), PT_DUST
                        )
                    )
                }
            }
        }

        // -- Enemies: patrol, or approach and fire when the squad is near. --
        for (e in world.enemies) {
            if (!e.alive) continue
            e.cd -= dt
            val nearest = world.members
                .filter { !it.down }
                .minByOrNull { distBetween(e.x, e.y, it.x, it.y) }
                ?: continue
            val d = distBetween(e.x, e.y, nearest.x, nearest.y)
            if (d <= 300f) {
                if (d > 170f) moveEnemyToward(e, nearest.x, nearest.y, 58f, dt)
                if (e.cd <= 0f && d <= 320f) {
                    e.cd = 0.9f
                    // Visible projectile: flies at the member's position at
                    // fire time, so a moving member can dodge it.
                    val dir = facingToward(e.x, e.y, nearest.x, nearest.y, Offset(0f, 1f))
                    world.bullets.add(
                        Bullet(
                            e.x, e.y, dir.x * 540f, dir.y * 540f, dir.x, dir.y,
                            nearest.x, nearest.y, fromEnemy = true,
                            damage = 5f + Random.nextFloat() * 3f,
                            targetMember = nearest
                        )
                    )
                    world.effects.add(
                        Effect(FX_HIT, e.x, e.y, e.x, e.y, 0.08f, 0.08f)
                    )
                }
            } else {
                val wx = e.ax + (cos((world.elapsed * 0.35f + e.phase).toDouble()) * 46.0).toFloat()
                val wy = e.ay + (sin((world.elapsed * 0.27f + e.phase).toDouble()) * 30.0).toFloat()
                moveEnemyToward(e, wx, wy, 26f, dt)
            }
        }

        // -- Squad auto-fire: nearest enemy in range, else nearest crate.
        // Fire spawns a bullet; damage lands when the bullet arrives. --
        for (m in world.members) {
            if (m.down) continue
            m.cd -= dt
            if (m.cd > 0f) continue
            var fired = false
            val target = world.enemies
                .filter { it.alive }
                .minByOrNull { distBetween(m.x, m.y, it.x, it.y) }
            if (target != null && distBetween(m.x, m.y, target.x, target.y) <= 285f) {
                val dir = facingToward(m.x, m.y, target.x, target.y, Offset(0f, -1f))
                world.bullets.add(
                    Bullet(
                        m.x, m.y, dir.x * 950f, dir.y * 950f, dir.x, dir.y,
                        target.x, target.y, fromEnemy = false,
                        damage = m.damage, targetEnemy = target
                    )
                )
                world.effects.add(Effect(FX_HIT, m.x, m.y, m.x, m.y, 0.08f, 0.08f))
                fired = true
            } else {
                val crate = world.crates
                    .filter { !it.destroyed }
                    .minByOrNull { distBetween(m.x, m.y, it.x, it.y) }
                if (crate != null && distBetween(m.x, m.y, crate.x, crate.y) <= 285f) {
                    val dir = facingToward(m.x, m.y, crate.x, crate.y, Offset(0f, -1f))
                    world.bullets.add(
                        Bullet(
                            m.x, m.y, dir.x * 950f, dir.y * 950f, dir.x, dir.y,
                            crate.x, crate.y, fromEnemy = false,
                            damage = m.damage, targetCrate = crate
                        )
                    )
                    world.effects.add(Effect(FX_HIT, m.x, m.y, m.x, m.y, 0.08f, 0.08f))
                    fired = true
                }
            }
            if (fired) {
                m.cd = m.fireInterval * if (world.rapidTimer > 0f) 0.6f else 1f
            }
        }

        // -- Bullets in flight: damage applies on arrival / proximity. --
        val bit = world.bullets.iterator()
        while (bit.hasNext()) {
            val b = bit.next()
            b.x += b.vx * dt
            b.y += b.vy * dt
            val speed = sqrt(b.vx * b.vx + b.vy * b.vy)
            val remaining = distBetween(b.x, b.y, b.endX, b.endY)
            var spent = false
            if (!b.fromEnemy) {
                if (remaining <= speed * dt * 1.05f + 2f) {
                    val te = b.targetEnemy
                    val tc = b.targetCrate
                    if (te != null) {
                        if (te.alive &&
                            distBetween(te.x, te.y, b.endX, b.endY) <= 40f
                        ) {
                            damageEnemy(world, te, b.damage)
                        }
                    } else if (tc != null) {
                        if (!tc.destroyed &&
                            distBetween(tc.x, tc.y, b.endX, b.endY) <= 40f
                        ) {
                            damageCrate(world, tc, b.damage)
                        }
                    }
                    spent = true
                }
            } else {
                val tm = b.targetMember
                if (tm != null && !tm.down &&
                    distBetween(b.x, b.y, tm.x, tm.y) <= 30f
                ) {
                    damageMember(world, tm, b.damage)
                    spent = true
                } else if (remaining <= speed * dt * 1.05f + 2f) {
                    spent = true // flew past the aim point — a clean miss
                }
            }
            if (spent) bit.remove()
        }

        // -- Particles: sparks fly out, flames linger, dust drifts up. --
        val pit = world.particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.ttl -= dt
            if (p.ttl <= 0f) {
                pit.remove()
                continue
            }
            when (p.kind) {
                PT_SPARK -> {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.vx *= (1f - 2.2f * dt)
                    p.vy *= (1f - 2.2f * dt)
                }
                PT_DUST -> {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                }
            }
        }

        // -- Floating texts rise and fade. --
        val fit = world.floaters.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            f.ttl -= dt
            f.y -= 42f * dt
            if (f.ttl <= 0f) fit.remove()
        }

        // -- Death fades count down. --
        val dit = world.deaths.iterator()
        while (dit.hasNext()) {
            val d = dit.next()
            d.ttl -= dt
            if (d.ttl <= 0f) dit.remove()
        }

        // -- Pickups: collected when a standing member comes close. --
        val kit = world.pickups.iterator()
        while (kit.hasNext()) {
            val pk = kit.next()
            val touched = world.members.any {
                !it.down && distBetween(it.x, it.y, pk.x, pk.y) <= 45f
            }
            if (!touched) continue
            kit.remove()
            if (pk.kind == PICKUP_MEDKIT) {
                val hurt = world.members
                    .filter { !it.down }
                    .minByOrNull { it.hp }
                if (hurt != null) {
                    hurt.hp = min(hurt.maxHp, hurt.hp + 25f)
                }
                addFloater(world, pk.x, pk.y - 30f, "+25 HP", 32f, 0xFF69F0AE, 1.0f)
                world.flashText = "+25 HP"
            } else {
                world.rapidTimer = 8f
                addFloater(world, pk.x, pk.y - 30f, "RAPID FIRE!", 32f, 0xFFFFEB3B, 1.0f)
                world.flashText = "RAPID FIRE!"
            }
            world.flashTtl = 1.6f
        }

        // -- Effects expiry. --
        val it = world.effects.iterator()
        while (it.hasNext()) {
            val fx = it.next()
            fx.ttl -= dt
            if (fx.ttl <= 0f) it.remove()
        }

        // -- Win / lose. --
        if (world.enemies.all { !it.alive } && world.crates.all { it.destroyed }) {
            world.finished = true
            var stars = 1
            if (world.members.none { it.wasDowned }) stars++
            if (world.elapsed <= 150f) stars++
            onFinish(true, stars, 50 + 25 * stars, world.elapsed)
            return
        }
        if (world.members.all { it.down }) {
            world.finished = true
            onFinish(false, 0, 0, world.elapsed)
        }
    }

    LaunchedEffect(Unit) {
        var lastMs = -1L
        while (true) {
            withFrameMillis { now ->
                if (lastMs > 0L) {
                    val dt = ((now - lastMs) / 1000f).coerceIn(0f, 0.05f)
                    stepGame(dt)
                }
                lastMs = now
                frameTick++
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF59743B))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz ->
                    if (sz.width > 0 && sz.height > 0 && !world.initialized) {
                        initWorld(world, sz.width.toFloat(), sz.height.toFloat())
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var joyActive = false
                        var pressPos: Offset? = null
                        var tapStart: Offset? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            when {
                                change.pressed && !change.previousPressed -> {
                                    pressPos = change.position
                                    if (change.position.x < size.width * 0.65f) {
                                        // Floating joystick: the base is
                                        // wherever the thumb lands.
                                        joyActive = true
                                        joyBase = change.position
                                        joyVec = Offset.Zero
                                    } else {
                                        tapStart = change.position
                                    }
                                }
                                change.pressed && joyActive -> {
                                    val base = joyBase ?: change.position
                                    val v = change.position - base
                                    val l = v.getDistance()
                                    joyVec = if (l > 60f) v / l * 60f else v
                                }
                                !change.pressed && change.previousPressed -> {
                                    if (joyActive) {
                                        joyActive = false
                                        // A near-stationary press on the
                                        // joystick side still counts as a
                                        // tap (revive a downed member).
                                        val pp = pressPos
                                        if (pp != null &&
                                            distBetween(
                                                pp.x, pp.y,
                                                change.position.x, change.position.y
                                            ) < 24f
                                        ) {
                                            handleTap(pp)
                                        }
                                        joyVec = Offset.Zero
                                        joyBase = null
                                    } else {
                                        tapStart?.let { handleTap(it) }
                                        tapStart = null
                                    }
                                    pressPos = null
                                }
                            }
                        }
                    }
                }
        ) {
            // Reading frameTick here redraws the canvas every frame.
            val tick = frameTick
            if (tick >= 0 && world.initialized) {
                drawGame(world, faction, joyVec, joyBase, holdOn, art)
            }
        }

        // HUD (top bar).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC10200C))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val tick = frameTick
            val cleared = world.enemies.count { !it.alive }
            val cratesGone = world.crates.count { it.destroyed }
            val hpLine = world.members.joinToString("   ") { m ->
                m.name + " " + (if (m.down) "DOWN" else m.hp.toInt().toString())
            }
            Text(
                "MISSION 1 — DAWN PATROL",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Scouts cleared: $cleared/8   Crates destroyed: $cratesGone/3",
                color = Color(0xFFCFD8C3),
                fontSize = 13.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Time: ${formatTime(world.elapsed)}", color = Color(0xFFCFD8C3), fontSize = 13.sp)
                Text(hpLine, color = Color(0xFFCFD8C3), fontSize = 13.sp)
            }
            if (world.combo >= 2) {
                Text(
                    "COMBO ×${world.combo}",
                    color = Color(0xFFFF5DA2),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (world.flashTtl > 0f && world.flashText.isNotEmpty()) {
                Text(
                    world.flashText,
                    color = Color(0xFFFFEB3B),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Controls (bottom-right): HOLD toggle + GRENADE.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tick = frameTick
            Button(
                onClick = { holdOn = !holdOn },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (holdOn) Color(0xFFC62828) else Color(0xFF3A4A38)
                ),
                modifier = Modifier.height(60.dp)
            ) { Text(if (holdOn) "HOLDING" else "HOLD") }
            Button(
                onClick = { throwGrenade() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41)),
                modifier = Modifier.height(60.dp)
            ) {
                Text(
                    if (world.grenadeCd > 0f)
                        "GRENADE ${world.grenadeCd.toInt() + 1}" else "GRENADE"
                )
            }
        }
    }
}

// --- Canvas drawing (pre-rendered pixel-art bitmaps; gameplay unchanged) ---

/** Darken / lighten a colour by a factor (misc shades). */
fun shadeOf(c: Color, f: Float): Color = Color(
    (c.red * f).coerceIn(0f, 1f),
    (c.green * f).coerceIn(0f, 1f),
    (c.blue * f).coerceIn(0f, 1f),
    c.alpha
)

/** Stable position hash: same tile, same pattern, every single frame. */
fun pixelHash(ix: Int, iy: Int, seed: Int): Int {
    var n = ix * 374761393 + iy * 668265263 + seed * 362437
    n = (n xor (n shr 13)) * 1274126177
    return n xor (n shr 16)
}

fun DrawScope.drawHealthBar(cx: Float, topY: Float, frac: Float, width: Float = 40f) {
    val f = frac.coerceIn(0f, 1f)
    drawRect(
        color = Color(0xAA000000),
        topLeft = Offset(cx - width / 2f, topY),
        size = Size(width, 5f)
    )
    drawRect(
        color = if (f > 0.35f) Color(0xFF66BB6A) else Color(0xFFEF5350),
        topLeft = Offset(cx - width / 2f, topY),
        size = Size(width * f, 5f)
    )
}

/** Soft ground shadow ellipse centred at (cx, cy). */
fun DrawScope.drawShadow(cx: Float, cy: Float, rx: Float, ry: Float) {
    drawOval(
        color = Color.Black.copy(alpha = 0.20f),
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f)
    )
}

/** Unit facing vector from (x, y) toward (tx, ty), with a fallback. */
fun facingToward(x: Float, y: Float, tx: Float, ty: Float, fallback: Offset): Offset {
    val d = distBetween(x, y, tx, ty)
    if (d < 1f) return fallback
    return Offset((tx - x) / d, (ty - y) / d)
}

// --- Pre-rendered pixel art --------------------------------------------------
// Per the pixel-art-game-sprites skill: every tile and sprite is painted
// ONCE into a Bitmap at native pixel resolution (buildPixelArt, remembered
// in GameScreen) and blitted per frame with FilterQuality.None, so the
// texture can be fully dense without per-frame rect cost, and pixels stay
// chunky-crisp when scaled up.

enum class PixelFacing { DOWN, UP, LEFT, RIGHT }

private fun argb(hex: Long): Int = hex.toInt()

private class PxPainter(val canvas: NativeCanvas, val paint: Paint) {
    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        paint.color = color
        canvas.drawRect(
            x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint
        )
    }

    fun px(x: Int, y: Int, color: Int) = rect(x, y, 1, 1, color)
}

private fun pixelBitmap(w: Int, h: Int, block: (PxPainter) -> Unit): ImageBitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(bmp)
    val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    block(PxPainter(canvas, paint))
    return bmp.asImageBitmap()
}

private fun mirrorBitmap(src: ImageBitmap): ImageBitmap {
    val b = src.asAndroidBitmap()
    val m = Matrix()
    m.preScale(-1f, 1f)
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, false).asImageBitmap()
}

// Shared sprite colours.
private val PX_OUTLINE = argb(0xFF0B0F0A)
private val PX_SKIN = argb(0xFFE0A266)
private val PX_SKIN_SHADE = argb(0xFFB97F45)
private val PX_BOOT = argb(0xFF17130F)
private val PX_RIFLE = argb(0xFF2A2018)
private val PX_RIFLE_METAL = argb(0xFF555B60)
private val PX_BAND = argb(0xFFD43A2E)
private val PX_BAND_DARK = argb(0xFF9E241B)
private val PX_HAIR = argb(0xFF1C1C1E)
private val PX_BELT = argb(0xFF1E1A14)

/** Soldier palette: faction / role variants come from palette swap only. */
private class SoldierPal(
    val uniform: Int,
    val uniformLight: Int,
    val uniformDark: Int,
    val helmet: Int,
    val helmetLight: Int,
    val pants: Int,
    val pack: Int,
    val headband: Boolean
)

// PAKISTAN: bright-mid green uniform, highlight green, dark green helmet.
private val PAL_PAK = SoldierPal(
    argb(0xFF3E7A33), argb(0xFF5FA24A), argb(0xFF2A5A24),
    argb(0xFF24511F), argb(0xFF4C7A3F), argb(0xFF2F6129), argb(0xFF28511F), false
)

// INDIA: bright olive-khaki equivalents.
private val PAL_IND = SoldierPal(
    argb(0xFF97954F), argb(0xFFB5B06A), argb(0xFF6E6C36),
    argb(0xFF5E5C2C), argb(0xFF8A884E), argb(0xFF74723C), argb(0xFF605E30), false
)

// ENEMY: charcoal clothes, lighter grey highlights, red headband.
private val PAL_ENEMY = SoldierPal(
    argb(0xFF43484D), argb(0xFF62686E), argb(0xFF2C3034),
    argb(0xFF3A3F44), argb(0xFF62686E), argb(0xFF232629), argb(0xFF2C3034), true
)

/**
 * Soldier sprite, native 24x30, painted programmatically: shared body
 * parts with alternate leg/boot rows for the two walk frames. LEFT is
 * the RIGHT bitmap mirrored at build time.
 */
private fun paintSoldier(
    pal: SoldierPal,
    facing: PixelFacing,
    frame: Int,
    faction: Faction?
): ImageBitmap = pixelBitmap(24, 30) { p ->
    when (facing) {
        PixelFacing.DOWN, PixelFacing.UP -> {
            // Legs + boots: frame B strides (one foot forward, one lifted).
            if (frame == 0) {
                p.rect(7, 22, 4, 6, pal.pants); p.rect(13, 22, 4, 6, pal.pants)
                p.rect(6, 28, 5, 2, PX_BOOT); p.rect(13, 28, 5, 2, PX_BOOT)
            } else {
                p.rect(6, 22, 4, 6, pal.pants); p.rect(14, 22, 4, 5, pal.pants)
                p.rect(4, 28, 6, 2, PX_BOOT); p.rect(14, 26, 5, 2, PX_BOOT)
            }
            // Torso.
            p.rect(6, 12, 12, 9, pal.uniform)
            p.rect(6, 12, 12, 1, pal.uniformLight)
            // Arms + hands.
            p.rect(4, 12, 3, 8, pal.uniformDark); p.rect(17, 12, 3, 8, pal.uniformDark)
            p.rect(4, 20, 3, 2, PX_SKIN); p.rect(17, 20, 3, 2, PX_SKIN)
            p.rect(6, 21, 12, 1, PX_BELT)
            if (facing == PixelFacing.DOWN) {
                // Vest with pocket shading + flag patch on the chest.
                p.rect(8, 13, 8, 7, pal.uniformDark)
                p.rect(9, 17, 2, 2, pal.uniform); p.rect(13, 17, 2, 2, pal.uniform)
                when (faction) {
                    Faction.PAKISTAN -> {
                        p.rect(9, 13, 3, 2, argb(0xFF046A38))
                        p.rect(9, 13, 1, 2, argb(0xFFFFFFFF))
                    }
                    Faction.INDIA -> {
                        p.rect(9, 13, 3, 1, argb(0xFFFF7A1A))
                        p.rect(9, 14, 3, 1, argb(0xFFFFFFFF))
                        p.rect(9, 15, 3, 1, argb(0xFF138808))
                    }
                    else -> {}
                }
                if (pal.headband) p.rect(4, 13, 3, 2, PX_BAND) // enemy armband
                // Rifle angled down the right side.
                p.rect(18, 15, 2, 3, PX_RIFLE); p.rect(19, 18, 2, 3, PX_RIFLE)
                p.rect(20, 21, 2, 2, PX_RIFLE_METAL)
                // Face.
                p.rect(8, 7, 8, 4, PX_SKIN)
                p.px(10, 8, PX_OUTLINE); p.px(13, 8, PX_OUTLINE)
                p.rect(8, 10, 8, 1, PX_SKIN_SHADE)
            } else {
                // Back: backpack with flap + slung rifle.
                p.rect(7, 12, 10, 9, pal.pack)
                p.rect(7, 12, 10, 2, pal.uniformDark)
                p.rect(9, 16, 6, 4, pal.uniformDark)
                p.rect(17, 13, 2, 3, PX_RIFLE); p.rect(18, 16, 2, 3, PX_RIFLE)
                p.rect(19, 19, 2, 2, PX_RIFLE_METAL)
                // Back of the helmet, down to the neck.
                p.rect(7, 8, 10, 3, pal.helmet)
                p.rect(10, 11, 4, 1, PX_SKIN_SHADE)
            }
            // Head: helmet dome + shine + brim, or hair + red headband.
            if (pal.headband) {
                p.rect(6, 2, 12, 3, PX_HAIR)
                p.rect(5, 5, 14, 2, PX_BAND)
                p.rect(18, 6, 2, 2, PX_BAND_DARK)
            } else {
                p.rect(6, 2, 12, 3, pal.helmet)
                p.rect(7, 2, 4, 1, pal.helmetLight)
                p.rect(5, 5, 14, 1, pal.helmet)
                p.rect(4, 6, 16, 1, pal.uniformDark)
            }
        }
        PixelFacing.RIGHT -> {
            // Side legs: frame B strides forward / lifts the back foot.
            if (frame == 0) {
                p.rect(9, 22, 4, 6, pal.pants); p.rect(8, 28, 6, 2, PX_BOOT)
                p.rect(14, 22, 4, 6, pal.pants); p.rect(14, 28, 6, 2, PX_BOOT)
            } else {
                p.rect(13, 22, 4, 6, pal.pants); p.rect(14, 28, 6, 2, PX_BOOT)
                p.rect(6, 22, 4, 5, pal.pants); p.rect(4, 26, 6, 2, PX_BOOT)
            }
            // Torso in profile + backpack bump behind.
            p.rect(8, 12, 8, 9, pal.uniform)
            p.rect(8, 12, 8, 1, pal.uniformLight)
            p.rect(10, 13, 5, 7, pal.uniformDark)
            p.rect(5, 12, 3, 8, pal.pack)
            p.rect(8, 21, 8, 1, PX_BELT)
            // Arm reaching to the rifle + horizontal rifle.
            p.rect(14, 13, 3, 5, pal.uniformDark)
            p.rect(15, 18, 2, 2, PX_SKIN)
            p.rect(11, 15, 12, 2, PX_RIFLE)
            p.rect(21, 15, 3, 1, PX_RIFLE_METAL)
            p.rect(9, 14, 3, 3, PX_RIFLE)
            // Head in profile: face front, helmet / headband above.
            p.rect(12, 7, 5, 4, PX_SKIN)
            p.px(15, 8, PX_OUTLINE)
            p.rect(7, 5, 4, 3, if (pal.headband) PX_HAIR else pal.helmet)
            if (pal.headband) {
                p.rect(7, 2, 10, 3, PX_HAIR)
                p.rect(6, 5, 13, 2, PX_BAND)
            } else {
                p.rect(7, 2, 10, 3, pal.helmet)
                p.rect(8, 2, 3, 1, pal.helmetLight)
                p.rect(15, 5, 5, 1, pal.helmet)
            }
        }
        PixelFacing.LEFT -> {
            // Never painted directly: LEFT reuses the mirrored RIGHT bitmap.
            p.rect(0, 0, 1, 1, PX_OUTLINE)
        }
    }
}

/** Downed soldier: lying figure, grey palette, native 28x13. */
private fun paintDowned(): ImageBitmap = pixelBitmap(28, 13) { p ->
    p.rect(1, 3, 26, 8, PX_OUTLINE)
    p.rect(2, 4, 6, 6, argb(0xFF6E726E))
    p.rect(2, 4, 6, 1, argb(0xFFA9ADA9))
    p.rect(8, 5, 3, 4, PX_SKIN_SHADE)
    p.rect(11, 4, 10, 6, argb(0xFF8A8F8A))
    p.rect(11, 4, 10, 1, argb(0xFFA9ADA9))
    p.rect(14, 6, 4, 3, argb(0xFF6E726E))
    p.rect(12, 10, 7, 1, argb(0xFF6E726E))
    p.rect(21, 5, 5, 4, argb(0xFF747874))
    p.rect(26, 4, 2, 3, PX_BOOT); p.rect(26, 8, 2, 3, PX_BOOT)
}

/** Wooden crate, native 24x24: planks, X brace, top highlight. */
private fun paintCrate(): ImageBitmap = pixelBitmap(24, 24) { p ->
    val wood = argb(0xFFC08A4E)
    val light = argb(0xFFE0AA6A)
    val dark = argb(0xFF7C4A24)
    val brace = argb(0xFF5D3520)
    p.rect(0, 0, 24, 24, PX_OUTLINE)
    p.rect(1, 1, 22, 22, wood)
    p.rect(1, 1, 22, 2, light)
    p.rect(1, 19, 22, 4, dark)
    p.rect(1, 8, 22, 1, dark); p.rect(1, 15, 22, 1, dark)
    for (i in 0 until 18) {
        p.rect(3 + i, 3 + i, 2, 2, brace)
        p.rect(3 + i, 20 - i, 2, 2, brace)
    }
    p.px(2, 2, light); p.px(21, 2, light)
}

/** Tree, native 26x32: layered 3-shade canopy over a trunk. */
private fun paintTree(): ImageBitmap = pixelBitmap(26, 32) { p ->
    val dark = argb(0xFF1E5B24)
    val mid = argb(0xFF2E7D32)
    val light = argb(0xFF66BB6A)
    val hi = argb(0xFF8FD07A)
    p.rect(11, 23, 4, 8, argb(0xFF6D4C41))
    p.rect(11, 23, 1, 8, argb(0xFF4E342E))
    p.rect(9, 30, 8, 1, argb(0xFF4E342E))
    val rows = listOf(
        3 to 3, 4 to 5, 5 to 7, 6 to 9, 7 to 10, 8 to 11, 9 to 12, 10 to 12,
        11 to 12, 12 to 12, 13 to 11, 14 to 11, 15 to 10, 16 to 10, 17 to 9,
        18 to 8, 19 to 7, 20 to 6, 21 to 4
    )
    for ((y, hw) in rows) p.rect(13 - hw, y, hw * 2, 1, dark)
    for ((y, hw) in rows) {
        if (y in 5..19 && hw > 2) p.rect(13 - hw + 2, y, (hw - 2) * 2, 1, mid)
    }
    for (y in 4..20) {
        for (x in 3..22) {
            val hh = pixelHash(x, y, 77)
            if (Math.floorMod(hh, 7) == 0) p.px(x, y, light)
            if (Math.floorMod(hh, 23) == 0) p.px(x, y, hi)
        }
    }
}

/** Rock, native 24x14: light top face over shaded sides. */
private fun paintRock(): ImageBitmap = pixelBitmap(24, 14) { p ->
    val rows = listOf(
        2 to 5, 3 to 8, 4 to 10, 5 to 11, 6 to 11,
        7 to 11, 8 to 10, 9 to 9, 10 to 7, 11 to 5
    )
    for ((y, hw) in rows) {
        val col = when {
            y <= 4 -> argb(0xFFCFCFCF)
            y <= 7 -> argb(0xFF9E9E9E)
            else -> argb(0xFF616161)
        }
        p.rect(12 - hw, y, hw * 2, 1, col)
        p.px(12 - hw, y, PX_OUTLINE); p.px(12 + hw - 1, y, PX_OUTLINE)
    }
    p.rect(8, 3, 5, 1, argb(0xFFEDEDED))
}

/** Watchtower, native 40x56: legs + X braces, cabin, shaded roof. */
private fun paintTower(): ImageBitmap = pixelBitmap(40, 56) { p ->
    val woodL = argb(0xFFA9743F)
    val woodM = argb(0xFF7C4A24)
    val woodD = argb(0xFF4A2E1B)
    // Legs + feet.
    p.rect(9, 22, 3, 32, woodM); p.rect(28, 22, 3, 32, woodM)
    p.rect(9, 22, 1, 32, woodD); p.rect(30, 22, 1, 32, woodD)
    p.rect(7, 54, 8, 2, woodD); p.rect(25, 54, 8, 2, woodD)
    // X braces, stepped diagonals between the legs.
    for (i in 0 until 16) {
        val by = 24 + (i * 26) / 16
        p.rect(12 + i, by, 2, 2, woodD)
        p.rect(26 - i, by, 2, 2, woodD)
    }
    // Platform.
    p.rect(6, 20, 28, 3, woodD)
    p.rect(6, 20, 28, 1, woodL)
    // Cabin with a dark window.
    p.rect(8, 8, 24, 12, woodM)
    p.rect(8, 8, 24, 2, woodL)
    p.rect(8, 17, 24, 3, woodD)
    p.rect(8, 8, 1, 12, woodD); p.rect(31, 8, 1, 12, woodD)
    p.rect(15, 11, 10, 6, argb(0xFF14181D))
    p.px(17, 12, argb(0xFF90CAF9))
    // Roof: widening shingle rows + ridge highlight.
    val roofRows = listOf(1 to 5, 2 to 8, 3 to 11, 4 to 13, 5 to 15, 6 to 16, 7 to 17)
    for ((y, hw) in roofRows) p.rect(20 - hw, y, hw * 2, 1, woodD)
    p.rect(15, 1, 10, 1, woodL)
    p.rect(3, 7, 34, 1, PX_OUTLINE)
}

/** Dirt base tile, native 64x64: speckled brown with worn streaks. */
private fun paintDirt(): ImageBitmap = pixelBitmap(64, 64) { p ->
    val base = argb(0xFF9C7B4F)
    val dark = argb(0xFF8A6A42)
    val light = argb(0xFFB08D5E)
    val pebble = argb(0xFF7D7468)
    val grass = argb(0xFF6F8F3F)
    p.rect(0, 0, 64, 64, base)
    for (y in 0 until 64) {
        for (x in 0 until 64) {
            val hh = pixelHash(x, y, 99)
            when {
                Math.floorMod(hh, 29) == 0 -> p.px(x, y, pebble)
                Math.floorMod(hh, 11) == 0 -> p.px(x, y, dark)
                Math.floorMod(hh, 13) == 0 -> p.px(x, y, light)
                Math.floorMod(hh, 37) == 0 -> p.px(x, y, grass)
            }
        }
    }
    var sx = 4
    while (sx < 60) {
        p.rect(sx, 20, 5, 1, dark)
        p.rect(sx + 3, 44, 5, 1, dark)
        sx += 9
    }
}

/**
 * Crop tile, native 64x64, FULL coverage: soil gaps between vertical
 * crop bands, tuft clusters in the band, vertical shading gradient
 * (bands darker toward the bottom) and a light top edge.
 */
private fun paintCrop(
    dark: Int, mid: Int, light: Int, hi: Int, seed: Int, sparse: Boolean
): ImageBitmap = pixelBitmap(64, 64) { p ->
    p.rect(0, 0, 64, 64, argb(0xFF6E5133))
    var bx = 0
    while (bx < 64) {
        for (y in 0 until 64) {
            p.rect(bx, y, 8, 1, if (y < 26) mid else dark)
        }
        var ty = Math.floorMod(bx * 7 + seed, 5)
        while (ty < 61) {
            if (!sparse || Math.floorMod(pixelHash(bx, ty, seed), 3) != 0) {
                p.rect(bx + 1, ty, 6, 2, light)
                p.rect(bx + 2, ty, 2, 1, hi)
                p.rect(bx + 5, ty + 1, 2, 1, hi)
            }
            ty += 6
        }
        bx += 11
    }
    p.rect(0, 0, 64, 1, light)
}

/** Wheat tile, native 64x64: dense stalk columns + grain-head clusters. */
private fun paintWheat(seed: Int): ImageBitmap = pixelBitmap(64, 64) { p ->
    val base = argb(0xFFD9A83F)
    val dark = argb(0xFFB9852B)
    val stalk = argb(0xFFC9972F)
    val head = argb(0xFFF5D76A)
    val hi = argb(0xFFFFF0A0)
    p.rect(0, 0, 64, 64, base)
    p.rect(0, 56, 64, 8, dark)
    var sx = 1
    while (sx < 64) {
        p.rect(sx, 0, 1, 64, stalk)
        sx += 3
    }
    var gy = 2
    while (gy < 62) {
        var gx = 0
        while (gx < 63) {
            val hh = pixelHash(gx, gy, seed)
            if (Math.floorMod(hh, 5) != 0) {
                p.rect(gx, gy, 2, 3, head)
                p.px(gx, gy, hi)
            }
            gx += 4
        }
        gy += 8
    }
    p.rect(0, 0, 64, 1, hi)
}

/** Canal strip tile, native 27x64: mud banks, shaded water, sparkles. */
private fun paintCanal(variant: Int): ImageBitmap = pixelBitmap(27, 64) { p ->
    val bankD = argb(0xFF5D4630)
    val bankL = argb(0xFF7A5C3E)
    val water = argb(0xFF2E7CD6)
    val waterL = argb(0xFF4A97E8)
    val waterD = argb(0xFF1F66B4)
    p.rect(0, 0, 4, 64, bankD); p.rect(23, 0, 4, 64, bankD)
    p.rect(3, 0, 1, 64, bankL); p.rect(23, 0, 1, 64, bankL)
    p.rect(4, 0, 19, 64, water)
    for (y in 0 until 64) {
        for (x in 5 until 22) {
            val hh = pixelHash(x, y, 500 + variant)
            if (Math.floorMod(hh, 17) == 0) p.px(x, y, waterL)
            if (Math.floorMod(hh, 23) == 0) p.px(x, y, waterD)
        }
    }
    val sparkles = if (variant == 0) listOf(11 to 12, 16 to 40) else listOf(15 to 24, 10 to 52)
    val wht = argb(0xFFFFFFFF)
    for ((sx, sy) in sparkles) {
        p.px(sx, sy, wht)
        p.px(sx - 1, sy, wht); p.px(sx + 1, sy, wht)
        p.px(sx, sy - 1, wht); p.px(sx, sy + 1, wht)
    }
}

/** Road strip tile, native 64x16: speckles + two dashed track lines. */
private fun paintRoad(): ImageBitmap = pixelBitmap(64, 16) { p ->
    val base = argb(0xFFB08968)
    val dark = argb(0xFF8A6A42)
    val light = argb(0xFFC49B78)
    p.rect(0, 0, 64, 16, base)
    for (y in 0 until 16) {
        for (x in 0 until 64) {
            val hh = pixelHash(x, y, 41)
            if (Math.floorMod(hh, 9) == 0) p.px(x, y, dark)
            if (Math.floorMod(hh, 13) == 0) p.px(x, y, light)
        }
    }
    var x = 0
    while (x < 64) {
        p.rect(x, 4, 9, 2, dark)
        p.rect(x + 4, 11, 9, 2, dark)
        x += 16
    }
    p.rect(0, 15, 64, 1, dark)
}

/** Fence segment, native 16x12: one post + two dashed wire rows. */
private fun paintFence(): ImageBitmap = pixelBitmap(16, 12) { p ->
    val post = argb(0xFF3A3F44)
    val postL = argb(0xFF5A6167)
    val wire = argb(0xFF2E3338)
    p.rect(1, 0, 3, 12, post)
    p.rect(1, 0, 1, 12, postL)
    for (x in 0 until 16) {
        if (x % 6 != 5) {
            p.px(x, 3, wire)
            p.px(x, 7, wire)
        }
    }
    p.px(6, 2, wire); p.px(12, 6, wire)
}

/** Medkit pickup icon, native 14x14: white box, red cross. */
private fun paintMedkit(): ImageBitmap = pixelBitmap(14, 14) { p ->
    p.rect(0, 0, 14, 14, PX_OUTLINE)
    p.rect(1, 1, 12, 12, argb(0xFFF5F5F5))
    p.rect(1, 1, 12, 2, argb(0xFFBDBDBD))
    val red = argb(0xFFD32F2F)
    p.rect(6, 3, 2, 8, red)
    p.rect(3, 6, 8, 2, red)
}

/** Rapid-fire pickup icon, native 14x14: dark box, yellow bolt. */
private fun paintRapid(): ImageBitmap = pixelBitmap(14, 14) { p ->
    p.rect(0, 0, 14, 14, PX_OUTLINE)
    p.rect(1, 1, 12, 12, argb(0xFF263238))
    val bolt = argb(0xFFFFEB3B)
    p.rect(7, 2, 3, 5, bolt)
    p.rect(5, 6, 4, 2, bolt)
    p.rect(4, 8, 3, 4, bolt)
}

/** All pre-rendered art for the game screen. Built once, remembered. */
class PixelArt(
    val dirt: ImageBitmap,
    val cropA: ImageBitmap,
    val cropB: ImageBitmap,
    val lightA: ImageBitmap,
    val lightB: ImageBitmap,
    val wheatA: ImageBitmap,
    val wheatB: ImageBitmap,
    val canalA: ImageBitmap,
    val canalB: ImageBitmap,
    val road: ImageBitmap,
    val fence: ImageBitmap,
    val pak: Map<PixelFacing, Array<ImageBitmap>>,
    val ind: Map<PixelFacing, Array<ImageBitmap>>,
    val enemy: Map<PixelFacing, Array<ImageBitmap>>,
    val downed: ImageBitmap,
    val crate: ImageBitmap,
    val tree: ImageBitmap,
    val rock: ImageBitmap,
    val tower: ImageBitmap,
    val medkit: ImageBitmap,
    val rapid: ImageBitmap
)

fun buildPixelArt(): PixelArt {
    fun soldierSet(
        pal: SoldierPal,
        faction: Faction?
    ): Map<PixelFacing, Array<ImageBitmap>> {
        val down = arrayOf(
            paintSoldier(pal, PixelFacing.DOWN, 0, faction),
            paintSoldier(pal, PixelFacing.DOWN, 1, faction)
        )
        val up = arrayOf(
            paintSoldier(pal, PixelFacing.UP, 0, faction),
            paintSoldier(pal, PixelFacing.UP, 1, faction)
        )
        val right = arrayOf(
            paintSoldier(pal, PixelFacing.RIGHT, 0, faction),
            paintSoldier(pal, PixelFacing.RIGHT, 1, faction)
        )
        val left = arrayOf(mirrorBitmap(right[0]), mirrorBitmap(right[1]))
        return mapOf(
            PixelFacing.DOWN to down,
            PixelFacing.UP to up,
            PixelFacing.RIGHT to right,
            PixelFacing.LEFT to left
        )
    }
    return PixelArt(
        dirt = paintDirt(),
        cropA = paintCrop(
            argb(0xFF2F7A26), argb(0xFF43A832), argb(0xFF6FC84F), argb(0xFF9BE36D),
            seed = 11, sparse = false
        ),
        cropB = paintCrop(
            argb(0xFF2F7A26), argb(0xFF43A832), argb(0xFF6FC84F), argb(0xFF9BE36D),
            seed = 47, sparse = false
        ),
        lightA = paintCrop(
            argb(0xFF57A03A), argb(0xFF79BC52), argb(0xFFA5D96E), argb(0xFFC4EC8F),
            seed = 23, sparse = true
        ),
        lightB = paintCrop(
            argb(0xFF57A03A), argb(0xFF79BC52), argb(0xFFA5D96E), argb(0xFFC4EC8F),
            seed = 35, sparse = true
        ),
        wheatA = paintWheat(5),
        wheatB = paintWheat(19),
        canalA = paintCanal(0),
        canalB = paintCanal(1),
        road = paintRoad(),
        fence = paintFence(),
        pak = soldierSet(PAL_PAK, Faction.PAKISTAN),
        ind = soldierSet(PAL_IND, Faction.INDIA),
        enemy = soldierSet(PAL_ENEMY, null),
        downed = paintDowned(),
        crate = paintCrate(),
        tree = paintTree(),
        rock = paintRock(),
        tower = paintTower(),
        medkit = paintMedkit(),
        rapid = paintRapid()
    )
}

// --- Sprite blitting (FilterQuality.None keeps the pixels chunky) -----------

private fun DrawScope.blit(
    bmp: ImageBitmap,
    left: Float,
    top: Float,
    w: Int,
    h: Int,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null
) {
    drawImage(
        image = bmp,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(w, h),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = FilterQuality.None
    )
}

/** White silhouette flash used for the 60 ms hit flash. */
private val HIT_FLASH_FILTER: ColorFilter =
    ColorFilter.tint(Color(0xBFFFFFFF), BlendMode.SrcIn)

// Shared paint for all Canvas-drawn world text (labels + floating numbers).
private val canvasTextPaint = Paint().apply {
    isAntiAlias = true
    typeface = Typeface.DEFAULT_BOLD
    textAlign = Paint.Align.CENTER
}

/** World-anchored text drawn inside the Canvas so it tracks the world. */
private fun DrawScope.drawCanvasText(
    text: String,
    cx: Float,
    baselineY: Float,
    sizePx: Float,
    argbColor: Int,
    alphaFrac: Float = 1f
) {
    val paint = canvasTextPaint
    paint.textSize = sizePx
    paint.color = argbColor
    paint.alpha = (alphaFrac.coerceIn(0f, 1f) * 255f).toInt()
    drawContext.canvas.nativeCanvas.drawText(text, cx, baselineY, paint)
}

/** Tile [bmp] across a rect at uniform scale (192x192 world per tile). */
private fun DrawScope.tileRect(
    bmp: ImageBitmap,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    tileW: Int = 192,
    tileH: Int = 192
) {
    clipRect(left = x0, top = y0, right = x1, bottom = y1) {
        var py = y0
        while (py < y1) {
            var px = x0
            while (px < x1) {
                blit(bmp, px, py, tileW, tileH)
                px += tileW
            }
            py += tileH
        }
    }
}

/** Watchtower sprite (native 40x56 drawn 100x140), base at [baseY]. */
fun DrawScope.drawWatchtower(art: PixelArt, cx: Float, baseY: Float) {
    drawShadow(cx, baseY + 4f, 44f, 7f)
    blit(art.tower, cx - 50f, baseY - 140f, 100, 140)
}

/** Tree sprite (native 26x32 drawn 78x96). */
fun DrawScope.drawTree(art: PixelArt, o: Obstacle) {
    val bottom = o.y + o.r * 0.62f
    drawShadow(o.x, bottom - 2f, o.r * 0.95f, o.r * 0.30f)
    blit(art.tree, o.x - 39f, bottom - 96f, 78, 96)
}

/** Rock sprite (native 24x14 drawn 72x42). */
fun DrawScope.drawRock(art: PixelArt, o: Obstacle) {
    val bottom = o.y + o.r * 0.72f
    drawShadow(o.x, bottom - 1f, o.r * 1.0f, o.r * 0.30f)
    blit(art.rock, o.x - 36f, bottom - 42f, 72, 42)
}

/** Crate sprite (native 24x24 drawn 72x72); destroyed = faded. */
fun DrawScope.drawCrate(art: PixelArt, c: CrateEnt) {
    val alpha = if (c.destroyed) 0.45f else 1f
    drawShadow(c.x + 3f, c.y + 30f, 30f, 7f)
    blit(
        art.crate, c.x - 36f, c.y - 36f, 72, 72, alpha,
        colorFilter = if (c.flash > 0f) HIT_FLASH_FILTER else null
    )
    if (!c.destroyed && c.hp < c.maxHp) {
        drawHealthBar(c.x, c.y - 48f, c.hp / c.maxHp)
    }
}

/**
 * Soldier sprite (native 24x30 drawn 76x95), feet at (x, groundY). The
 * facing is the dominant axis of [facing] — the vector toward the same
 * target auto-fire uses — and the walk frame flips with [stepPhase]
 * while [moving], exactly the moving conditions of the old leg animation.
 */
fun DrawScope.drawStandingFigure(
    x: Float,
    groundY: Float,
    facing: Offset,
    stepPhase: Float,
    moving: Boolean,
    set: Map<PixelFacing, Array<ImageBitmap>>,
    alpha: Float = 1f,
    flash: Boolean = false
) {
    val pixelFacing = if (kotlin.math.abs(facing.y) >= kotlin.math.abs(facing.x)) {
        if (facing.y >= 0f) PixelFacing.DOWN else PixelFacing.UP
    } else {
        if (facing.x >= 0f) PixelFacing.RIGHT else PixelFacing.LEFT
    }
    val frame = if (moving) {
        (kotlin.math.floor(stepPhase / Math.PI).toInt() and 1)
    } else 0
    drawShadow(x, groundY + 4f, 23f, 5f)
    blit(
        set.getValue(pixelFacing)[frame], x - 38f, groundY - 95f, 76, 95,
        alpha = alpha,
        colorFilter = if (flash) HIT_FLASH_FILTER else null
    )
}

/** Downed member: lying-flat sprite (native 28x13 drawn 88x41). */
fun DrawScope.drawDownFigure(art: PixelArt, x: Float, y: Float) {
    drawShadow(x, y + 6f, 26f, 5f)
    blit(art.downed, x - 44f, y - 18f, 88, 41)
}

/** One bullet in flight: a bright square at chest height + 2 trail squares. */
fun DrawScope.drawBullet(b: Bullet) {
    val cy = b.y - 55f
    val head = if (b.fromEnemy) Color(0xFFFF7043) else Color(0xFFFFF59D)
    val trail = if (b.fromEnemy) Color(0xFFFFAB91) else Color(0xFFFFFFFF)
    for (i in 2 downTo 1) {
        val tx = b.x - b.dirX * 10f * i
        val ty = cy - b.dirY * 10f * i
        val s = 6f - 1.6f * i
        drawRect(
            color = trail.copy(alpha = 0.55f - 0.18f * i),
            topLeft = Offset(tx - s / 2f, ty - s / 2f),
            size = Size(s, s)
        )
    }
    drawRect(
        color = head,
        topLeft = Offset(b.x - 3.5f, cy - 3.5f),
        size = Size(7f, 7f)
    )
}

/** A pickup on the ground: shadow + bobbing pixel icon. */
fun DrawScope.drawPickup(art: PixelArt, pk: Pickup, elapsed: Float) {
    val bob = sin((elapsed * 3f + pk.phase).toDouble()).toFloat() * 3f
    drawShadow(pk.x, pk.y + 12f, 15f, 4f)
    val bmp = if (pk.kind == PICKUP_MEDKIT) art.medkit else art.rapid
    blit(bmp, pk.x - 16f, pk.y - 16f + bob, 32, 32)
}

/** Hit flash as a plus-shaped pixel burst at (cx, cy). */
fun DrawScope.drawPixelBurst(cx: Float, cy: Float, color: Color, alpha: Float) {
    val c = color.copy(alpha = alpha)
    val s = 4f
    drawRect(color = c, topLeft = Offset(cx - s / 2f, cy - s / 2f), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx - s / 2f - s, cy - s / 2f), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx + s / 2f, cy - s / 2f), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx - s / 2f, cy - s / 2f - s), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx - s / 2f, cy + s / 2f), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx - s / 2f - 2 * s, cy - s / 2f), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx + s / 2f + s, cy - s / 2f), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx - s / 2f, cy - s / 2f - 2 * s), size = Size(s, s))
    drawRect(color = c, topLeft = Offset(cx - s / 2f, cy + s / 2f + s), size = Size(s, s))
}

/** Grenade blast as a blocky ring of squares on the ground plane. */
fun DrawScope.drawPixelBlast(cx: Float, cy: Float, radius: Float, alpha: Float) {
    val ring = Color(0xFFFF5722).copy(alpha = alpha)
    val inner = Color(0xFFFFC107).copy(alpha = alpha * 0.9f)
    val n = 28
    for (i in 0 until n) {
        val ang = (i.toDouble() / n) * 2.0 * Math.PI
        val rx = cx + (cos(ang) * radius).toFloat()
        val ry = cy + (sin(ang) * radius * 0.62).toFloat()
        drawRect(color = ring, topLeft = Offset(rx - 3f, ry - 3f), size = Size(6f, 6f))
        if (i % 2 == 0) {
            val ix = cx + (cos(ang) * radius * 0.68).toFloat()
            val iy = cy + (sin(ang) * radius * 0.68 * 0.62).toFloat()
            drawRect(color = inner, topLeft = Offset(ix - 2f, iy - 2f), size = Size(4f, 4f))
        }
    }
    drawRect(
        color = Color(0xFFFF9800).copy(alpha = 0.20f * alpha),
        topLeft = Offset(cx - 7f, cy - 7f),
        size = Size(14f, 14f)
    )
}

/** Draws the whole map + entities. Map coordinates = canvas pixels. */
fun DrawScope.drawGame(
    world: GameWorld,
    faction: Faction,
    joyVec: Offset,
    joyBase: Offset?,
    holdOn: Boolean,
    art: PixelArt
) {
    val w = world.w
    val h = world.h
    if (w <= 0f || h <= 0f) return

    // Screen shake: the world (not the joystick) jitters on explosions.
    if (world.shake > 0f) {
        val sx = sin((world.elapsed * 95f).toDouble()).toFloat() * world.shake
        val sy = cos((world.elapsed * 121f).toDouble()).toFloat() * world.shake * 0.7f
        withTransform({ translate(sx, sy) }) {
            drawGameWorld(world, faction, joyVec, holdOn, art)
        }
    } else {
        drawGameWorld(world, faction, joyVec, holdOn, art)
    }

    // Floating joystick: drawn wherever the player's thumb landed.
    val base = joyBase
    if (base != null) {
        drawCircle(color = Color.White.copy(alpha = 0.16f), radius = 62f, center = base)
        drawCircle(
            color = Color.White.copy(alpha = 0.45f),
            radius = 62f,
            center = base,
            style = Stroke(width = 2f)
        )
        val knob = base + joyVec * 0.62f
        drawCircle(color = Color.White.copy(alpha = 0.38f), radius = 26f, center = knob)
    }
}

private fun DrawScope.drawGameWorld(
    world: GameWorld,
    faction: Faction,
    joyVec: Offset,
    holdOn: Boolean,
    art: PixelArt
) {
    val w = world.w
    val h = world.h

    // ---- Ground layer: dirt base, plots, road, canal, fence. ----
    // Dirt bitmap tiled over the whole map: plots sit ON it, with dirt
    // visible between them — no dark grid lines anywhere.
    var dirtY = 0f
    while (dirtY < h) {
        var dirtX = 0f
        while (dirtX < w) {
            blit(art.dirt, dirtX, dirtY, 192, 192)
            dirtX += 192f
        }
        dirtY += 192f
    }
    // Plot kinds by the same index formula as the old colour palette:
    // 0 = green crop, 1 = light crop, 2 = wheat.
    val kindOf = listOf(0, 1, 2, 0, 1, 0)
    val cols = 4
    val rows = 6
    val cellW = w / cols
    val cellH = h / rows
    for (i in 0 until cols) {
        for (j in 0 until rows) {
            val kind = kindOf[(i * 3 + j * 5 + i * j) % kindOf.size]
            val variant = (i + j) % 2
            val bmp = when (kind) {
                0 -> if (variant == 0) art.cropA else art.cropB
                1 -> if (variant == 0) art.lightA else art.lightB
                else -> if (variant == 0) art.wheatA else art.wheatB
            }
            val x0 = i * cellW + 4f
            val y0 = j * cellH + 4f
            val x1 = (i + 1) * cellW - 4f
            val y1 = (j + 1) * cellH - 4f
            tileRect(bmp, x0, y0, x1, y1)
            drawRect(
                color = Color(0xFF5D4630).copy(alpha = 0.50f),
                topLeft = Offset(x0, y0),
                size = Size(x1 - x0, y1 - y0),
                style = Stroke(width = 3f)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.18f),
                topLeft = Offset(x0, y1 - 5f),
                size = Size(x1 - x0, 5f)
            )
        }
    }

    // Dirt road: bitmap strip (native 64x16 drawn 192x48) across the map.
    val roadY = h * 0.545f
    tileRect(art.road, 0f, roadY, w, roadY + 42f, tileW = 192, tileH = 48)

    // Canal along the right side: two baked variants alternate down the
    // strip, mud banks at both edges.
    val cx0 = w * 0.865f
    val cx1 = w * 0.94f
    clipRect(left = cx0, top = 0f, right = cx1, bottom = h) {
        var cy = 0f
        var idx = 0
        while (cy < h) {
            blit(
                if (idx % 2 == 0) art.canalA else art.canalB,
                cx0, cy, (cx1 - cx0).toInt(), 192
            )
            cy += 192f
            idx++
        }
    }

    // Border fence: bitmap segment (post + dashed wires) tiled across.
    val fenceBase = h * 0.16f + 9f
    var fenceX = 0f
    while (fenceX < w) {
        blit(art.fence, fenceX, fenceBase - 28f, 48, 36)
        fenceX += 48f
    }

    // Permanent scorch patches left behind by explosions.
    for (s in world.scorches) {
        drawOval(
            color = Color.Black.copy(alpha = 0.34f),
            topLeft = Offset(s.x - s.r, s.y - s.r * 0.55f),
            size = Size(s.r * 2f, s.r * 1.1f)
        )
        for (i in 0 until 5) {
            val hh = pixelHash(i, s.seed, 31)
            val ox = Math.floorMod(hh, (s.r * 1.4f).toInt()).toFloat() - s.r * 0.7f
            val oy = Math.floorMod(hh shr 4, (s.r * 0.7f).toInt()).toFloat() - s.r * 0.35f
            drawRect(
                color = Color.Black.copy(alpha = 0.30f),
                topLeft = Offset(s.x + ox, s.y + oy),
                size = Size(7f, 7f)
            )
        }
    }

    // Range ring: the squad's auto-fire radius around the formation
    // centre, drawn under every entity.
    if (world.members.isNotEmpty()) {
        var ccx = 0f
        var ccy = 0f
        for (m in world.members) {
            ccx += m.x
            ccy += m.y
        }
        ccx /= world.members.size
        ccy /= world.members.size
        drawCircle(
            color = Color.White.copy(alpha = 0.16f),
            radius = 285f,
            center = Offset(ccx, ccy),
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
            )
        )
    }

    // ---- Depth-sorted layer: towers, cover, crates, soldiers. ----
    // Sorted by base (feet) Y ascending: lower on screen draws later,
    // so it overlaps whatever stands higher up.
    val drawables = mutableListOf<Pair<Float, DrawScope.() -> Unit>>()
    for (tx in listOf(w * 0.22f, w * 0.68f)) {
        drawables.add(fenceBase to { drawWatchtower(art, tx, fenceBase) })
    }
    for (o in world.obstacles) {
        if (o.tree) {
            drawables.add(o.y + o.r * 0.62f to { drawTree(art, o) })
        } else {
            drawables.add(o.y + o.r * 0.72f to { drawRock(art, o) })
        }
    }
    for (c in world.crates) {
        drawables.add(c.y + 36f to { drawCrate(art, c) })
    }
    for (e in world.enemies) {
        if (!e.alive) continue
        val target = world.members
            .filter { !it.down }
            .minByOrNull { distBetween(e.x, e.y, it.x, it.y) }
        val eFacing = if (target != null) {
            facingToward(e.x, e.y, target.x, target.y, Offset(0f, 1f))
        } else Offset(0f, 1f)
        val eMoving = target == null ||
            distBetween(e.x, e.y, target.x, target.y) > 172f
        drawables.add(e.y to {
            drawStandingFigure(
                e.x, e.y, eFacing, world.elapsed * 7f + e.phase, eMoving, art.enemy,
                flash = e.flash > 0f
            )
            if (e.hp < e.maxHp) {
                drawHealthBar(e.x, e.y - 108f, e.hp / e.maxHp, 34f)
            }
        })
    }
    for (d in world.deaths) {
        drawables.add(d.y to {
            drawStandingFigure(
                d.x, d.y, d.facing, 0f, false, art.enemy,
                alpha = (d.ttl / 0.25f).coerceIn(0f, 1f)
            )
        })
    }
    for (pk in world.pickups) {
        drawables.add(pk.y + 2f to { drawPickup(art, pk, world.elapsed) })
    }
    val memberSet = if (faction == Faction.PAKISTAN) art.pak else art.ind
    val squadMoving = !holdOn && joyVec.getDistance() > 3f
    world.members.forEachIndexed { idx, m ->
        if (m.down) {
            drawables.add(m.y + 6f to { drawDownFigure(art, m.x, m.y) })
        } else {
            val tEnemy = world.enemies
                .filter { it.alive }
                .minByOrNull { distBetween(m.x, m.y, it.x, it.y) }
            val tCrate = if (tEnemy == null) {
                world.crates
                    .filter { !it.destroyed }
                    .minByOrNull { distBetween(m.x, m.y, it.x, it.y) }
            } else null
            val mFacing = when {
                tEnemy != null -> facingToward(m.x, m.y, tEnemy.x, tEnemy.y, Offset(0f, -1f))
                tCrate != null -> facingToward(m.x, m.y, tCrate.x, tCrate.y, Offset(0f, -1f))
                else -> Offset(0f, -1f)
            }
            drawables.add(m.y to {
                drawStandingFigure(
                    m.x, m.y, mFacing, world.elapsed * 11f + idx * 1.3f,
                    squadMoving, memberSet
                )
                drawHealthBar(m.x, m.y - 108f, m.hp / m.maxHp)
            })
        }
    }
    drawables.sortBy { it.first }
    for (d in drawables) d.second(this)

    // ---- Effects on top, in pixel idiom: plus-shaped muzzle / hit
    // bursts and the blocky blast ring. (Tracers are gone — real
    // bullets below carry the fire now.) ----
    for (fx in world.effects) {
        val a = (fx.ttl / fx.maxTtl).coerceIn(0f, 1f)
        when (fx.kind) {
            FX_HIT -> drawPixelBurst(fx.x1, fx.y1 - 55f, Color(0xFFFFF59D), a)
            FX_BLAST -> {
                val progress = 1f - a
                val radius = 20f + progress * 95f
                drawPixelBlast(fx.x1, fx.y1, radius, a)
            }
        }
    }

    // ---- Particles: explosion sparks, lingering flames, foot dust. ----
    for (p in world.particles) {
        val frac = (p.ttl / p.maxTtl).coerceIn(0f, 1f)
        when (p.kind) {
            PT_FLAME -> {
                val flicker = 0.75f + 0.25f *
                    sin((world.elapsed * 40f + p.x).toDouble()).toFloat()
                val s = p.size * flicker
                drawRect(
                    color = p.color.copy(alpha = frac * 0.9f),
                    topLeft = Offset(p.x - s / 2f, p.y - s / 2f),
                    size = Size(s, s)
                )
            }
            PT_DUST -> drawRect(
                color = p.color.copy(alpha = frac * 0.45f),
                topLeft = Offset(p.x - p.size / 2f, p.y - p.size / 2f),
                size = Size(p.size, p.size)
            )
            else -> drawRect(
                color = p.color.copy(alpha = frac),
                topLeft = Offset(p.x - p.size / 2f, p.y - p.size / 2f),
                size = Size(p.size, p.size)
            )
        }
    }

    // ---- Bullets in flight, above the entities. ----
    for (b in world.bullets) {
        drawBullet(b)
    }

    // ---- Floating texts (damage numbers, pickup announcements). ----
    for (f in world.floaters) {
        val a = (f.ttl / f.maxTtl).coerceIn(0f, 1f)
        drawCanvasText(f.text, f.x, f.y, f.size, f.argb, a)
    }

    // ---- Call-sign labels, drawn in the Canvas so they track the
    // figures exactly (the old Compose overlay lagged behind). ----
    for (m in world.members) {
        if (m.down) {
            drawCanvasText("DOWN", m.x, m.y - 30f, 30f, 0xFFFF5252.toInt())
        } else {
            drawCanvasText(m.name, m.x, m.y - 118f, 30f, 0xFFFFFFFF.toInt())
        }
    }
}
