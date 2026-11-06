package app.sarhadsquad.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as NativeCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import java.io.File
import java.io.FileOutputStream

// ============================================================================
// Sarhad Squad: Border War — Mission 1 prototype (v0.6.2)
// Pixel-art squad shooter. TITLE -> GAME -> RESULT state machine.
// v0.4.0 adds the Stage-1 Feel Pack: visible projectiles, explosion
// feedback + scorch marks, Canvas damage numbers, combo counter, range
// ring, floating joystick, crate pickups, hit flashes, dust puffs.
// v0.5.0 swaps in real sprite art for the soldiers, the explosion
// sequence and the main props. The generated sheets ship in assets as
// base64 text files (<name>.b64) decoded at runtime, because the repo
// push tool only accepts text. If any asset fails to load, the
// code-drawn pixel art remains as the fallback.
// v0.6.0: dirt-dominant ground with real dirt/wheat/crop textures,
// Mission 1 regrouped into smuggler camps (tents, a parked jeep, a
// watchtower sentry), a jeep-arrival intro, a pointer-id joystick that
// cannot be stolen or dropped, a frame-clock loop with stick smoothing,
// and the poster title screen.
// v0.6.1 polish round: Camp A (and its tower sentry) moved down out
// from under the HUD strip, the sentry drawn at 0.78 scale, the plot
// grid replaced by an explicit plot list so dirt dominates (3 crop
// plots + one broken wheat line), wild-grass tufts scattered on the
// dirt, a narrower canal, taller darker fence posts, smaller
// brown-tinted rocks, and synthesized sound + vibration (GameFeel).
// v0.6.2: two on-device fixes. (1) The HUD strip is part of the
// layout (a Column) instead of an overlay on the full-screen
// canvas, so the world's top — fence, tower, tower sentry — can
// never render underneath it; the HUD's combo/flash lines are
// always composed so its height never changes mid-mission.
// (2) Enemy fire made to READ: a star muzzle flash at the gun tip
// plus glow-halo enemy bullets (the fire logic itself was sound —
// bullets flew and hit; their 7px dark-orange squares and the
// pale chest burst were simply invisible on the bright ground).
// All in-game text is English only.
// ============================================================================

enum class Faction { PAKISTAN, INDIA }

enum class Screen { TITLE, GAME, RESULT }

// Effect kinds (drawn in the Canvas, removed when ttl runs out).
// Tracers are gone in v0.4.0 — fire is carried by real Bullet entities.
const val FX_HIT = 0
const val FX_BLAST = 1
const val FX_MUZZLE = 2 // enemy muzzle flash at the gun tip (v0.6.2)

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
    // Tower sentry (v0.6.0): never patrols or approaches, only fires.
    var stationary = false
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

/** A playing explosion sequence at a blast point (rendering only). */
class Boom(val x: Float, val y: Float, val size: Float, var ttl: Float, val maxTtl: Float)

/**
 * One ground plot (v0.6.1 explicit plot list, absolute world coords).
 * [main] plots get the border + bottom shade; the small wheat "chip"
 * rects (main = false) break the wheat patches' silhouettes.
 */
class Plot(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val wheat: Boolean, val main: Boolean
)

/** One wild-grass tuft spot on the dirt (v0.6.1): (x, y) is the
 * bottom-centre ground anchor; [variant] cycles the 4 atlas cells. */
class TuftSpot(
    val x: Float, val y: Float, val size: Float,
    val variant: Int, val flip: Boolean
)

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
    val booms = mutableListOf<Boom>()
    var combo = 0
    var comboTimer = 0f
    var rapidTimer = 0f
    var shake = 0f
    var dustTimer = 0f
    var pickupAlt = false
    var flashText = ""
    var flashTtl = 0f
    // Camp layout anchors (v0.6.0): base points shared by placement and
    // drawing. Tents / the camp jeep are pure decor (drawn only when the
    // real sprite assets loaded); towers exist in both art paths.
    val towers = mutableListOf<Offset>()
    val tents = mutableListOf<Offset>()
    var campJeep: Offset? = null
    // Explicit ground layout (v0.6.1): plot rects + grass tuft spots,
    // filled by initWorld, read by the ground draw in both art paths.
    val plots = mutableListOf<Plot>()
    val tuftSpots = mutableListOf<TuftSpot>()
    // Sound + vibration (v0.6.1). Optional everywhere: null or any
    // internal failure leaves the game exactly as before, silently.
    var feel: GameFeel? = null
    // Jeep-arrival intro (v0.6.0, real-art path only). The jeep drives
    // from jeepStartY up to the drop point, the squad steps out and
    // walks into formation, then control starts. INTRO_LEN total.
    var intro = false
    var introT = 0f
    var jeepX = 0f
    var jeepY = 0f
    var jeepDropX = 0f
    var jeepDropY = 0f
    var jeepStartY = 0f

    /** Snap the intro to its end state (tap to skip / fallback path). */
    fun finishIntro() {
        if (!intro) return
        intro = false
        introT = INTRO_LEN
        jeepX = jeepDropX
        jeepY = jeepDropY
        for (m in members) {
            m.x = anchorX + m.offX
            m.y = anchorY + m.offY
        }
    }
}

// Jeep intro timing (v0.6.0): drive, step-out walk, settle.
const val INTRO_LEN = 2.4f
const val INTRO_DRIVE_END = 1.7f
const val INTRO_WALK_LEN = 0.5f

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

    // --- Mission 1 regrouped into smuggler camps (v0.6.0). Same totals
    // as before: 8 scouts (one is the tower sentry) and 3 crates. ---
    // Watchtower on the fence line, anchoring Camp A (upper-left). Its
    // base point is shared with the draw code and the sentry spawn.
    // v0.6.1: the base has a floor of 330 units — the tower top (base
    // - 140) and the sentry's head (feet at base - 105, drawn 0.78
    // scale so his head sits at about base - 186) keep at least 120
    // units of clearance from the world top. Camp A's tent, crate
    // and pair are placed RELATIVE to the base, so the whole camp
    // shifts down together, arrangement unchanged.
    // v0.6.2: the HUD clearance is now structural as well — the game
    // canvas starts below the HUD strip (see GameScreen), so world
    // y = 0 is already under no UI at all; this floor now just gives
    // Camp A its in-field breathing room.
    val towerBaseY = max(h * 0.16f + 9f, 330f)
    world.towers.add(Offset(w * 0.22f, towerBaseY))
    // Camp A decor: a tent below-right of the tower.
    world.tents.add(Offset(w * 0.31f, towerBaseY + h * 0.10f))
    // Camp B decor (right side, near the canal): the smugglers' own
    // parked jeep, base point (bottom centre of the sprite).
    world.campJeep = Offset(w * 0.74f, h * 0.35f)

    var phaseIdx = 0
    fun addScout(fx: Float, fy: Float) {
        world.enemies.add(Enemy(w * fx, h * fy, phaseIdx * 1.37f))
        phaseIdx++
    }
    fun addScoutAt(x: Float, y: Float) {
        world.enemies.add(Enemy(x, y, phaseIdx * 1.37f))
        phaseIdx++
    }
    // Tower sentry: stands on the tower platform (feet 105 above the
    // tower base), standard enemy stats, never moves.
    val sentry = Enemy(w * 0.22f, towerBaseY - 105f, phaseIdx * 1.37f)
    phaseIdx++
    sentry.stationary = true
    world.enemies.add(sentry)
    // Camp A pair, posted around the tent and crate.
    addScoutAt(w * 0.27f, towerBaseY + h * 0.14f)
    addScoutAt(w * 0.13f, towerBaseY + h * 0.17f)
    // Camp B trio, around their jeep and crate.
    addScout(0.79f, 0.38f)
    addScout(0.63f, 0.31f)
    addScout(0.71f, 0.45f)
    // Patrol pair on the mid-field path, with the third crate.
    addScout(0.38f, 0.52f)
    addScout(0.56f, 0.57f)

    // Camp A's crate rides with the camp (v0.6.1); the other two keep
    // their v0.6.0 spots — Camp B's and the patrol pair's, the loose
    // one sitting at the top edge of the wheat line (see plots below).
    world.crates.add(CrateEnt(w * 0.15f, towerBaseY + h * 0.09f))
    val crateSpots = listOf(0.67f to 0.40f, 0.47f to 0.49f)
    for (spot in crateSpots) {
        world.crates.add(CrateEnt(w * spot.first, h * spot.second))
    }

    // --- Explicit plot list (v0.6.1): dirt dominates. The old plot
    // grid covered ~80% of the world; now 3 tended crop plots (~10%
    // of the area) plus ONE wheat line at mid-field (~6%) are the
    // only green/gold — everything else is mitti. The wheat line is
    // 3 uneven patches with stepped "chip" rects breaking their
    // edges; the patrol pair and the loose crate sit in/near it. ---
    fun cropPlot(x0: Float, y0: Float, x1: Float, y1: Float) {
        world.plots.add(Plot(w * x0, h * y0, w * x1, h * y1, wheat = false, main = true))
    }
    fun wheatPatch(x0: Float, y0: Float, x1: Float, y1: Float, main: Boolean) {
        world.plots.add(Plot(w * x0, h * y0, w * x1, h * y1, wheat = true, main = main))
    }
    cropPlot(0.35f, 0.12f, 0.61f, 0.225f) // upper-middle-left
    cropPlot(0.05f, 0.62f, 0.33f, 0.76f) // lower-left
    cropPlot(0.62f, 0.56f, 0.86f, 0.685f) // mid-right, below Camp B
    wheatPatch(0.04f, 0.505f, 0.22f, 0.615f, true)
    wheatPatch(0.015f, 0.53f, 0.05f, 0.60f, false)
    wheatPatch(0.19f, 0.475f, 0.235f, 0.545f, false)
    wheatPatch(0.10f, 0.595f, 0.16f, 0.645f, false)
    wheatPatch(0.26f, 0.49f, 0.50f, 0.585f, true)
    wheatPatch(0.235f, 0.515f, 0.275f, 0.575f, false)
    wheatPatch(0.475f, 0.465f, 0.515f, 0.53f, false)
    wheatPatch(0.33f, 0.565f, 0.40f, 0.615f, false)
    wheatPatch(0.545f, 0.52f, 0.695f, 0.65f, true)
    wheatPatch(0.52f, 0.545f, 0.56f, 0.615f, false)
    wheatPatch(0.665f, 0.495f, 0.71f, 0.565f, false)
    wheatPatch(0.585f, 0.625f, 0.645f, 0.675f, false)

    // --- Wild-grass tuft spots (v0.6.1): scattered on the dirt only —
    // clear of the plots, the canal, both camps, the squad start and
    // the intro jeep's drive line. Deterministic; variants cycle. ---
    val tuftData = listOf(
        Triple(0.07f, 0.09f, 52f), Triple(0.38f, 0.06f, 44f),
        Triple(0.66f, 0.08f, 48f), Triple(0.80f, 0.20f, 56f),
        Triple(0.05f, 0.44f, 40f), Triple(0.86f, 0.28f, 42f),
        Triple(0.33f, 0.42f, 58f), Triple(0.60f, 0.44f, 38f),
        Triple(0.40f, 0.70f, 54f), Triple(0.52f, 0.78f, 44f),
        Triple(0.20f, 0.86f, 60f), Triple(0.70f, 0.84f, 48f),
        Triple(0.87f, 0.74f, 50f), Triple(0.44f, 0.635f, 42f),
        Triple(0.76f, 0.52f, 44f), Triple(0.04f, 0.26f, 44f),
        Triple(0.48f, 0.28f, 50f), Triple(0.58f, 0.90f, 40f)
    )
    tuftData.forEachIndexed { i, t ->
        world.tuftSpots.add(
            TuftSpot(w * t.first, h * t.second, t.third, i % 4, i % 3 == 1)
        )
    }

    // Trees (cover) and rocks (cover), as collision circles, placed so
    // the camps read as places: cover at the camp edges and mid-field.
    val trees = listOf(
        Triple(0.08f, 0.20f, 24f), Triple(0.60f, 0.22f, 26f),
        Triple(0.83f, 0.50f, 24f), Triple(0.30f, 0.66f, 28f),
        Triple(0.58f, 0.71f, 26f)
    )
    for (t in trees) world.obstacles.add(Obstacle(w * t.first, h * t.second, t.third, true))
    val rocks = listOf(
        Triple(0.47f, 0.36f, 18f), Triple(0.16f, 0.52f, 16f), Triple(0.66f, 0.60f, 20f)
    )
    for (r in rocks) world.obstacles.add(Obstacle(w * r.first, h * r.second, r.third, false))

    // Jeep-arrival intro setup: the jeep starts below the world and
    // stops left of the formation; the squad steps out of it. The
    // fallback art path cancels the intro in GameScreen.
    world.jeepDropX = world.anchorX - 120f
    world.jeepDropY = world.anchorY + 16f
    world.jeepStartY = h + 140f
    world.jeepX = world.jeepDropX
    world.jeepY = world.jeepStartY
    world.intro = true
    world.introT = 0f

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
    // Combo milestone chime (v0.6.1) at x3 and x5.
    if (world.combo == 3 || world.combo == 5) world.feel?.combo()
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
    world.feel?.kill()
}

/** Damage to an enemy from any source; shows the floating damage number. */
fun damageEnemy(world: GameWorld, e: Enemy, dmg: Float) {
    if (!e.alive) return
    world.feel?.hit()
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
    world.feel?.explosion()
    spawnExplosion(world, c.x, c.y, big = true)
    world.booms.add(Boom(c.x, c.y, 170f, 0.55f, 0.55f))
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
        world.feel?.down()
    } else {
        world.feel?.hurt()
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
    // Real sprite art (v0.5.0), loaded once and shared by the title and
    // game screens; null = any asset failed, code art serves everywhere.
    val context = LocalContext.current
    val sprites = remember { loadSpriteAssets(context) }
    // Sound + vibration (v0.6.1), created once here so the START tap
    // can click and the same instance serves the game screen's world;
    // null = audio unavailable, the game runs silent as before.
    val feel = remember { try { GameFeel(context) } catch (e: Exception) { null } }

    when (screen) {
        Screen.TITLE -> TitleScreen(
            faction = faction,
            onFaction = { faction = it },
            onStart = { feel?.click(); session++; screen = Screen.GAME },
            poster = sprites?.poster
        )
        Screen.GAME -> key(session) {
            GameScreen(faction = faction, sprites = sprites, feel = feel) { v, s, cp, t ->
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
fun TitleScreen(
    faction: Faction,
    onFaction: (Faction) -> Unit,
    onStart: () -> Unit,
    poster: ImageBitmap? = null
) {
    // Poster title (v0.6.0): the poster art carries the game title, so
    // no big text title is drawn over it — just the side picker, the
    // mission brief and START in a shaded panel at the bottom.
    if (poster != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101A0B))
        ) {
            Image(
                bitmap = poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xF20A1206))
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "MISSION 1 — DAWN PATROL",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Clear 8 smuggler scouts and destroy 3 supply crates.",
                        color = Color(0xFFCFD8C3),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828)
                        )
                    ) { Text("START MISSION", fontSize = 15.sp) }
                }
            }
        }
        return
    }
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
fun GameScreen(
    faction: Faction,
    sprites: SpriteAssets?,
    feel: GameFeel? = null,
    onFinish: (Boolean, Int, Int, Float) -> Unit
) {
    val world = remember { GameWorld().also { it.feel = feel } }
    val art = remember { buildPixelArt() }
    var frameTick by remember { mutableIntStateOf(0) }
    var holdOn by remember { mutableStateOf(false) }
    // joyVec is the smoothed stick vector the game actually reads;
    // joyTarget is the raw vector the pointer handler writes (v0.6.0).
    var joyVec by remember { mutableStateOf(Offset.Zero) }
    var joyTarget by remember { mutableStateOf(Offset.Zero) }
    var joyBase by remember { mutableStateOf<Offset?>(null) }

    fun throwGrenade() {
        if (!world.initialized || world.finished || world.intro || world.grenadeCd > 0f) return
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
        world.booms.add(Boom(center.x, center.y, 200f, 0.55f, 0.55f))
        world.scorches.add(Scorch(center.x, center.y, 52f, (center.x * 3f + center.y).toInt()))
        world.shake = max(world.shake, 8f)
        world.grenadeCd = 6f
        world.feel?.explosion()
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
        // -- Jeep-arrival intro (v0.6.0): the world is frozen except the
        // jeep, its dust and the step-out walk. The mission timer (and
        // everything else) only starts when the intro ends. --
        if (world.intro) {
            world.introT += dt
            val t = world.introT
            val driveT = (t / INTRO_DRIVE_END).coerceIn(0f, 1f)
            val inv = 1f - driveT
            val ease = 1f - inv * inv * inv // cubic ease-out stop
            world.jeepX = world.jeepDropX
            world.jeepY = world.jeepStartY + (world.jeepDropY - world.jeepStartY) * ease
            if (driveT < 1f) {
                world.dustTimer -= dt
                if (world.dustTimer <= 0f) {
                    world.dustTimer = 0.07f
                    world.particles.add(
                        Particle(
                            world.jeepX + (Random.nextFloat() - 0.5f) * 34f,
                            world.jeepY + 50f,
                            (Random.nextFloat() - 0.5f) * 16f, 12f,
                            0.5f, 0.5f, 8f + Random.nextFloat() * 7f,
                            Color(0xFFD6C29A), PT_DUST
                        )
                    )
                }
            }
            if (t >= INTRO_DRIVE_END) {
                val walkT = ((t - INTRO_DRIVE_END) / INTRO_WALK_LEN).coerceIn(0f, 1f)
                val we = 1f - (1f - walkT) * (1f - walkT)
                world.members.forEachIndexed { i, m ->
                    val sx = world.jeepDropX + 58f
                    val sy = world.jeepDropY - 14f + i * 26f
                    val fx = world.anchorX + m.offX
                    val fy = world.anchorY + m.offY
                    m.x = sx + (fx - sx) * we
                    m.y = sy + (fy - sy) * we
                }
            }
            val introPit = world.particles.iterator()
            while (introPit.hasNext()) {
                val p = introPit.next()
                p.ttl -= dt
                if (p.ttl <= 0f) {
                    introPit.remove()
                    continue
                }
                if (p.kind == PT_DUST) {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                }
            }
            if (t >= INTRO_LEN) world.finishIntro()
            return
        }
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
                // The tower sentry holds his platform: he fires with
                // the standard stats but never approaches or patrols.
                if (!e.stationary && d > 170f) {
                    moveEnemyToward(e, nearest.x, nearest.y, 58f, dt)
                }
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
                    // Muzzle flash at the gun tip (v0.6.2). The FX_HIT
                    // burst sits on the shooter's chest and is pale and
                    // tiny, and the old enemy bullet was a 7px
                    // deep-orange square — together they made smuggler
                    // fire read as absent on device. The flash sits one
                    // gun-length along the shot direction at bullet
                    // height, from regular scouts and the tower sentry
                    // alike (this is their shared fire path).
                    world.effects.add(
                        Effect(
                            FX_MUZZLE,
                            e.x + dir.x * 36f, e.y - 55f + dir.y * 36f,
                            e.x, e.y, 0.06f, 0.06f
                        )
                    )
                    world.feel?.enemyShot()
                }
            } else if (!e.stationary) {
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
                world.feel?.memberShot()
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

        // -- Explosion sequences count down. --
        val bot = world.booms.iterator()
        while (bot.hasNext()) {
            val bo = bot.next()
            bo.ttl -= dt
            if (bo.ttl <= 0f) bot.remove()
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
            world.feel?.pickup()
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
            world.feel?.win()
            onFinish(true, stars, 50 + 25 * stars, world.elapsed)
            return
        }
        if (world.members.all { it.down }) {
            world.finished = true
            world.feel?.lose()
            onFinish(false, 0, 0, world.elapsed)
        }
    }

    // Frame-clock loop (v0.6.0): withFrameNanos instead of the old
    // withFrameMillis stepping, dt clamped to 1/30 s so a hitched frame
    // can never teleport the world. The stick vector is exponentially
    // smoothed toward the raw target (dt-correct) — "makhan" movement.
    LaunchedEffect(Unit) {
        var lastNs = -1L
        while (true) {
            withFrameNanos { now ->
                if (lastNs > 0L) {
                    val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
                    val k = 1f - exp(-dt * 18f)
                    joyVec = Offset(
                        joyVec.x + (joyTarget.x - joyVec.x) * k,
                        joyVec.y + (joyTarget.y - joyVec.y) * k
                    )
                    if (joyTarget == Offset.Zero && joyVec.getDistance() < 0.5f) {
                        joyVec = Offset.Zero
                    }
                    stepGame(dt)
                }
                lastNs = now
                frameTick++
            }
        }
    }

    // v0.6.2: the screen is a Column — the HUD strip sits in the
    // layout flow ABOVE the game canvas instead of overlaid on it.
    // Before, the root was a Box: a full-screen Canvas with the HUD
    // Column drawn over its top (80%-alpha background), so the
    // world's top strip — fence, tower top and the tower sentry —
    // always rendered underneath the HUD. v0.6.1's 330-unit floor
    // put the sentry's head ~144 units below the world top, but the
    // HUD strip is taller than that in pixels on a real phone
    // (~200 px at typical density), so he stayed covered. Now the
    // canvas physically starts below the HUD: initWorld reads the
    // canvas size, so world y = 0 IS the canvas top, pointer
    // positions are canvas-local and match world coordinates 1:1
    // exactly as before, and nothing of the world can ever be
    // drawn under the HUD. The HUD's combo/flash lines are always
    // composed (invisible when inactive) so the HUD height — and
    // thus the canvas size and the world — never changes size
    // mid-mission.
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF59743B))) {
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
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                "Scouts cleared: $cleared/8   Crates destroyed: $cratesGone/3",
                color = Color(0xFFCFD8C3),
                fontSize = 13.sp,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Time: ${formatTime(world.elapsed)}", color = Color(0xFFCFD8C3), fontSize = 13.sp, maxLines = 1)
                Text(hpLine, color = Color(0xFFCFD8C3), fontSize = 13.sp, maxLines = 1)
            }
            // v0.6.2: the combo and flash lines are ALWAYS composed —
            // invisible (alpha 0) while inactive — so the HUD strip
            // is one fixed height for the whole mission. That keeps
            // the canvas below it (and the world initialised from the
            // canvas size) from ever resizing mid-mission.
            Text(
                if (world.combo >= 2) "COMBO ×${world.combo}" else "COMBO",
                color = Color(0xFFFF5DA2).copy(alpha = if (world.combo >= 2) 1f else 0f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (world.flashTtl > 0f && world.flashText.isNotEmpty()) world.flashText else " ",
                color = Color(0xFFFFEB3B).copy(
                    alpha = if (world.flashTtl > 0f && world.flashText.isNotEmpty()) 1f else 0f
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { sz ->
                        if (sz.width > 0 && sz.height > 0 && !world.initialized) {
                            initWorld(world, sz.width.toFloat(), sz.height.toFloat())
                            // No real art = no jeep: the intro is skipped
                            // and the mission starts as before (v0.5.0).
                            if (sprites == null) world.finishIntro()
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            // Pointer-id joystick (v0.6.0). The old handler
                            // read only the FIRST change of every event, so
                            // a second finger (or the first finger's lift)
                            // could steal or kill the stick mid-drag. Now:
                            // the first finger down captures the stick by
                            // id, only that id moves it, and only that id's
                            // lift releases it — followed anywhere on the
                            // canvas, no zone-drop. Other touches are
                            // ignored while a stick is captured. A quick
                            // tap (down+up < 250 ms, < 12 units of travel)
                            // is NOT a stick drag: it is the revive tap.
                            var stickId: PointerId? = null
                            var stickBase = Offset.Zero
                            var downPos = Offset.Zero
                            var downTimeMs = 0L
                            while (true) {
                                val event = awaitPointerEvent()
                                if (world.initialized && world.intro) {
                                    // Any fresh press skips the intro.
                                    if (event.changes.any {
                                            it.pressed && !it.previousPressed
                                        }
                                    ) {
                                        world.finishIntro()
                                    }
                                    continue
                                }
                                val sid = stickId
                                if (sid != null) {
                                    val change = event.changes
                                        .firstOrNull { it.id == sid } ?: continue
                                    if (change.pressed) {
                                        val v = change.position - stickBase
                                        val l = v.getDistance()
                                        joyTarget =
                                            if (l > 60f) v / l * 60f else v
                                    } else {
                                        // Only the captured finger's lift
                                        // releases the stick.
                                        val heldMs =
                                            change.uptimeMillis - downTimeMs
                                        val moved = distBetween(
                                            downPos.x, downPos.y,
                                            change.position.x, change.position.y
                                        )
                                        if (heldMs < 250L && moved < 12f) {
                                            handleTap(downPos)
                                        }
                                        stickId = null
                                        joyBase = null
                                        joyTarget = Offset.Zero
                                    }
                                } else {
                                    val fresh = event.changes.firstOrNull {
                                        it.pressed && !it.previousPressed
                                    } ?: continue
                                    stickId = fresh.id
                                    stickBase = fresh.position
                                    downPos = fresh.position
                                    downTimeMs = fresh.uptimeMillis
                                    joyBase = fresh.position
                                    joyTarget = Offset.Zero
                                }
                            }
                        }
                    }
            ) {
                // Reading frameTick here redraws the canvas every frame.
                val tick = frameTick
                if (tick >= 0 && world.initialized) {
                    drawGame(world, faction, joyVec, joyBase, holdOn, art, sprites)
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
private fun paintFence(): ImageBitmap = pixelBitmap(16, 20) { p ->
    // v0.6.1: the khamby (posts) are ~1.6x taller and thicker than the
    // old 16x12 segment, dark wood with a visible cap; wires unchanged.
    val post = argb(0xFF4A3421)
    val postL = argb(0xFF6B4E2E)
    val cap = argb(0xFF5D4128)
    val wire = argb(0xFF2E3338)
    p.rect(0, 0, 7, 3, cap) // cap overhangs the post on both sides
    p.rect(1, 0, 1, 3, postL)
    p.rect(1, 3, 5, 17, post)
    p.rect(1, 3, 1, 17, postL)
    for (x in 0 until 16) {
        if (x < 1 || x > 5) {
            p.px(x, 7, wire)
            p.px(x, 14, wire)
        }
    }
    p.px(9, 6, wire); p.px(13, 13, wire)
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

// --- Real sprite art (v0.5.0): generated sheets loaded from assets ----------
// Sheets ship as base64 text (<name>.b64, decoded at runtime) because
// the repo push tool only accepts text files.
// Atlas sheets are 512x384: 4 columns x 3 rows of 128px cells.
// Row 0 = facing DOWN, row 1 = facing UP, row 2 = facing RIGHT; LEFT is
// the RIGHT row mirrored. Columns are the walk cycle. Cells are cropped
// once at load (the same selection drawImage srcOffset/srcSize would do
// per frame), so the per-frame draw is a plain blit and the mirrored
// LEFT frames are exact. ANY failure returns null and the game falls
// back to the code-drawn PixelArt above for everything.

class SpriteAssets(
    val pak: Map<PixelFacing, Array<ImageBitmap>>,
    val ind: Map<PixelFacing, Array<ImageBitmap>>,
    val smuggler: Map<PixelFacing, Array<ImageBitmap>>,
    val explosion: ImageBitmap,
    val crate: ImageBitmap,
    val tower: ImageBitmap,
    val tree: ImageBitmap,
    val medkit: ImageBitmap,
    // v0.6.0: ground textures, camp props and the title poster. If any
    // of these fail to decode, the whole load fails (returns null) and
    // the game keeps the v0.5.0 behaviour everywhere.
    val texDirt: ImageBitmap,
    val texWheat: ImageBitmap,
    val texCrop: ImageBitmap,
    val tent: ImageBitmap,
    val jeep: ImageBitmap,
    val poster: ImageBitmap,
    // v0.6.1: wild-grass tuft atlas (256x256 = 2x2 cells of 128px,
    // figures anchored bottom-centre), pre-cropped like the soldiers,
    // plus a mirrored set for the flipped spots.
    val tufts: Array<ImageBitmap>,
    val tuftsFlip: Array<ImageBitmap>
)

private fun loadSpriteAssets(context: Context): SpriteAssets? {
    return try {
        fun load(name: String): Bitmap {
            val text = context.assets.open(name).bufferedReader().use { it.readText() }
            val bytes = Base64.decode(text, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("asset decode failed: $name")
        }

        fun atlas(name: String): Map<PixelFacing, Array<ImageBitmap>> {
            val sheet = load(name)
            fun cell(col: Int, row: Int): ImageBitmap =
                Bitmap.createBitmap(sheet, col * 128, row * 128, 128, 128)
                    .asImageBitmap()
            fun rowFrames(row: Int): Array<ImageBitmap> =
                Array(4) { col -> cell(col, row) }
            val right = rowFrames(2)
            val left = Array(4) { col -> mirrorBitmap(right[col]) }
            return mapOf(
                PixelFacing.DOWN to rowFrames(0),
                PixelFacing.UP to rowFrames(1),
                PixelFacing.RIGHT to right,
                PixelFacing.LEFT to left
            )
        }

        fun tuftCells(name: String): Pair<Array<ImageBitmap>, Array<ImageBitmap>> {
            val sheet = load(name)
            fun cell(col: Int, row: Int): ImageBitmap =
                Bitmap.createBitmap(sheet, col * 128, row * 128, 128, 128)
                    .asImageBitmap()
            val cells = Array(4) { i -> cell(i % 2, i / 2) }
            val flips = Array(4) { i -> mirrorBitmap(cells[i]) }
            return cells to flips
        }
        val tuftPair = tuftCells("tufts.b64")

        SpriteAssets(
            pak = atlas("atlas_pak.b64"),
            ind = atlas("atlas_india.b64"),
            smuggler = atlas("atlas_smuggler.b64"),
            explosion = load("explosion.b64").asImageBitmap(),
            crate = load("crate.b64").asImageBitmap(),
            tower = load("tower.b64").asImageBitmap(),
            tree = load("tree.b64").asImageBitmap(),
            medkit = load("medkit.b64").asImageBitmap(),
            texDirt = load("tex_dirt.b64").asImageBitmap(),
            texWheat = load("tex_wheat.b64").asImageBitmap(),
            texCrop = load("tex_crop.b64").asImageBitmap(),
            tent = load("tent.b64").asImageBitmap(),
            jeep = load("jeep.b64").asImageBitmap(),
            poster = load("poster.b64").asImageBitmap(),
            tufts = tuftPair.first,
            tuftsFlip = tuftPair.second
        )
    } catch (e: Exception) {
        null
    }
}

// --- Sound + vibration (v0.6.1) ---------------------------------------------
// GameFeel synthesizes every effect from plain math (noise bursts, sine
// sweeps, note melodies) into small WAV files in the cache dir, once,
// then plays them through a SoundPool. No audio assets, no settings.
// EVERY entry point is wrapped in try/catch: audio or vibration can
// fail for any reason and the game must never notice.

class GameFeel(context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val lastPlayMs = HashMap<String, Long>()
    private val vibrator: Vibrator? = try {
        context.getSystemService(Vibrator::class.java)
    } catch (e: Exception) {
        null
    }

    init {
        try {
            val dir = File(context.cacheDir, "sarhad_sfx")
            if (!dir.exists()) dir.mkdirs()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(attrs)
                .build()
            for ((name, samples) in synthAll()) {
                val file = File(dir, "$name.wav")
                writeWav(file, samples)
                ids[name] = sp.load(file.absolutePath, 1)
            }
            pool = sp
        } catch (e: Exception) {
            pool = null
        }
    }

    private fun play(name: String, volume: Float, rate: Float, minGapMs: Long) {
        try {
            val sp = pool ?: return
            val id = ids[name] ?: return
            val now = System.currentTimeMillis()
            if (now - (lastPlayMs[name] ?: 0L) < minGapMs) return
            lastPlayMs[name] = now
            val v = (MASTER_VOL * volume).coerceIn(0f, 1f)
            sp.play(id, v, v, 1, 0, rate)
        } catch (e: Exception) {
            // Silent by design.
        }
    }

    private fun vibrate(ms: Long) {
        try {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            // Silent by design.
        }
    }

    fun memberShot() = play(
        "memberShot", 1f, 0.95f + Random.nextFloat() * 0.13f, SHOT_GAP_MS
    )

    fun enemyShot() = play(
        "enemyShot", 0.5f, 0.95f + Random.nextFloat() * 0.10f, SHOT_GAP_MS
    )

    fun hit() = play("hitTick", 0.8f, 1f, SHOT_GAP_MS)
    fun kill() = play("killPop", 0.9f, 1f, SHOT_GAP_MS)
    fun hurt() = play("memberHurt", 0.9f, 1f, 120L)
    fun pickup() = play("pickup", 0.9f, 1f, SHOT_GAP_MS)
    fun combo() = play("comboUp", 0.9f, 1f, SHOT_GAP_MS)
    fun click() = play("uiClick", 0.8f, 1f, SHOT_GAP_MS)
    fun win() = play("win", 1f, 1f, 0L)
    fun lose() = play("lose", 1f, 1f, 0L)

    /** Explosion sound + a short buzz. The 80 ms gap only stops the
     * same blast stacking on itself (grenade + crates it destroys);
     * distinct blasts are seconds apart and always play. */
    fun explosion() {
        play("explosion", 1f, 1f, 80L)
        vibrate(70L)
    }

    /** Member-down sting + a longer buzz. */
    fun down() {
        play("memberDown", 1f, 1f, SHOT_GAP_MS)
        vibrate(120L)
    }

    companion object {
        private const val SR = 22050
        private const val MASTER_VOL = 0.55f
        private const val SHOT_GAP_MS = 45L

        /** White noise through a one-pole lowpass ([dark]: 1 = bright
         * crack, low = dark rumble) under an exponential decay. */
        private fun noiseBurst(
            dur: Float, tau: Float, dark: Float, seed: Int, gain: Float
        ): FloatArray {
            val n = (dur * SR).toInt().coerceAtLeast(1)
            val out = FloatArray(n)
            val rnd = Random(seed)
            var lp = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / SR
                val white = rnd.nextFloat() * 2f - 1f
                lp += dark * (white - lp)
                out[i] = (lp * exp(-t / tau) * gain).coerceIn(-1f, 1f)
            }
            return out
        }

        /** Sine whose pitch glides from f0 to f1 over [dur]. */
        private fun sweep(
            f0: Float, f1: Float, dur: Float, tau: Float, gain: Float
        ): FloatArray {
            val n = (dur * SR).toInt().coerceAtLeast(1)
            val out = FloatArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toFloat() / SR
                val f = f0 + (f1 - f0) * (t / dur)
                phase += 2.0 * Math.PI * f / SR
                out[i] = (sin(phase).toFloat() * exp(-t / tau) * gain)
                    .coerceIn(-1f, 1f)
            }
            return out
        }

        /** Sequential sine notes, each with a quick attack and decay. */
        private fun melody(freqs: FloatArray, noteDur: Float, gain: Float): FloatArray {
            val per = (noteDur * SR).toInt().coerceAtLeast(1)
            val out = FloatArray(per * freqs.size)
            for (k in freqs.indices) {
                var phase = 0.0
                for (i in 0 until per) {
                    val t = i.toFloat() / SR
                    phase += 2.0 * Math.PI * freqs[k] / SR
                    val attack = min(1f, t / 0.012f)
                    val env = exp(-t / (noteDur * 0.9f)) * (0.4f + 0.6f * attack)
                    out[k * per + i] = (sin(phase).toFloat() * env * gain)
                        .coerceIn(-1f, 1f)
                }
            }
            return out
        }

        private fun mix(a: FloatArray, b: FloatArray): FloatArray {
            val n = max(a.size, b.size)
            val out = FloatArray(n)
            for (i in 0 until n) {
                val s = (if (i < a.size) a[i] else 0f) +
                    (if (i < b.size) b[i] else 0f)
                out[i] = s.coerceIn(-1f, 1f)
            }
            return out
        }

        private fun synthAll(): Map<String, FloatArray> = mapOf(
            "memberShot" to noiseBurst(0.07f, 0.020f, 0.85f, 11, 1.15f),
            "enemyShot" to noiseBurst(0.09f, 0.030f, 0.35f, 23, 2.1f),
            "hitTick" to noiseBurst(0.03f, 0.008f, 0.50f, 37, 2.2f),
            "killPop" to sweep(700f, 120f, 0.12f, 0.05f, 0.9f),
            "explosion" to mix(
                noiseBurst(0.50f, 0.16f, 0.16f, 41, 3.0f),
                sweep(160f, 40f, 0.50f, 0.20f, 0.7f)
            ),
            "memberHurt" to mix(
                noiseBurst(0.08f, 0.030f, 0.30f, 53, 2.0f),
                sweep(220f, 90f, 0.08f, 0.040f, 0.7f)
            ),
            "memberDown" to melody(floatArrayOf(520f, 390f, 260f), 0.12f, 0.55f),
            "pickup" to melody(floatArrayOf(880f, 1320f), 0.06f, 0.55f),
            "comboUp" to melody(floatArrayOf(660f, 880f, 1100f), 0.05f, 0.55f),
            "win" to melody(floatArrayOf(523f, 659f, 784f), 0.20f, 0.55f),
            "lose" to melody(floatArrayOf(392f, 330f, 262f), 0.20f, 0.55f),
            "uiClick" to sweep(1150f, 900f, 0.03f, 0.012f, 0.5f)
        )

        /** Minimal 16-bit mono PCM WAV writer (header built by hand). */
        private fun writeWav(file: File, samples: FloatArray) {
            val dataSize = samples.size * 2
            val header = ByteArray(44)
            fun putStr(off: Int, s: String) {
                for (i in s.indices) header[off + i] = s[i].code.toByte()
            }
            fun putInt(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = ((v shr 8) and 0xFF).toByte()
                header[off + 2] = ((v shr 16) and 0xFF).toByte()
                header[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun putShort(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = ((v shr 8) and 0xFF).toByte()
            }
            putStr(0, "RIFF"); putInt(4, 36 + dataSize); putStr(8, "WAVE")
            putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
            putInt(24, SR); putInt(28, SR * 2); putShort(32, 2); putShort(34, 16)
            putStr(36, "data"); putInt(40, dataSize)
            FileOutputStream(file).use { out ->
                out.write(header)
                val buf = ByteArray(dataSize)
                for (i in samples.indices) {
                    val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    buf[i * 2] = (v and 0xFF).toByte()
                    buf[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(buf)
            }
        }
    }
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

/**
 * Stretch a texture over a rect with the DEFAULT filter quality
 * (v0.6.0 ground textures only — sprite blits keep FilterQuality.None
 * via [blit] so their pixels stay chunky).
 */
private fun DrawScope.stretch(
    bmp: ImageBitmap,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float
) {
    drawImage(
        image = bmp,
        dstOffset = IntOffset(x0.toInt(), y0.toInt()),
        dstSize = IntSize(
            (x1 - x0).toInt().coerceAtLeast(1),
            (y1 - y0).toInt().coerceAtLeast(1)
        )
    )
}

/** Camp tent (real art 248x256 drawn 120x124), base at (x, baseY). */
fun DrawScope.drawTent(assets: SpriteAssets, x: Float, baseY: Float) {
    drawShadow(x, baseY + 4f, 52f, 8f)
    blit(assets.tent, x - 60f, baseY - 124f, 120, 124)
}

/** Watchtower sprite (native 40x56 drawn 100x140), base at [baseY]. */
fun DrawScope.drawWatchtower(art: PixelArt, cx: Float, baseY: Float, assets: SpriteAssets? = null) {
    drawShadow(cx, baseY + 4f, 44f, 7f)
    if (assets != null) {
        // Real art is tall and thin (131x256): keep its aspect, same
        // 140 height, anchored at the base like the code-drawn tower.
        blit(assets.tower, cx - 36f, baseY - 140f, 72, 140)
    } else {
        blit(art.tower, cx - 50f, baseY - 140f, 100, 140)
    }
}

/** Tree sprite (native 26x32 drawn 78x96). */
fun DrawScope.drawTree(art: PixelArt, o: Obstacle, assets: SpriteAssets? = null) {
    val bottom = o.y + o.r * 0.62f
    drawShadow(o.x, bottom - 2f, o.r * 0.95f, o.r * 0.30f)
    if (assets != null) {
        // Real art is near-square (242x256): same 96 height, aspect kept.
        blit(assets.tree, o.x - 45f, bottom - 96f, 90, 96)
    } else {
        blit(art.tree, o.x - 39f, bottom - 96f, 78, 96)
    }
}

/** Warm brown-grey multiply tint for the rocks (v0.6.1) — the raw
 * sprite is near-greyscale and read as grey saucers on the dirt. */
private val ROCK_TINT_FILTER: ColorFilter =
    ColorFilter.tint(Color(0xFFC7A887), BlendMode.Modulate)

/** Rock sprite (native 24x14; v0.6.1: drawn 58x34 = 0.8x, tinted). */
fun DrawScope.drawRock(art: PixelArt, o: Obstacle) {
    val bottom = o.y + o.r * 0.72f
    drawShadow(o.x, bottom - 1f, o.r * 1.0f, o.r * 0.30f)
    blit(art.rock, o.x - 29f, bottom - 34f, 58, 34, colorFilter = ROCK_TINT_FILTER)
}

/** Crate sprite (native 24x24 drawn 72x72); destroyed = faded. */
fun DrawScope.drawCrate(art: PixelArt, c: CrateEnt, assets: SpriteAssets? = null) {
    val alpha = if (c.destroyed) 0.45f else 1f
    drawShadow(c.x + 3f, c.y + 30f, 30f, 7f)
    blit(
        if (assets != null) assets.crate else art.crate,
        c.x - 36f, c.y - 36f, 72, 72, alpha,
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
    flash: Boolean = false,
    scale: Float = 1f
) {
    val pixelFacing = if (kotlin.math.abs(facing.y) >= kotlin.math.abs(facing.x)) {
        if (facing.y >= 0f) PixelFacing.DOWN else PixelFacing.UP
    } else {
        if (facing.x >= 0f) PixelFacing.RIGHT else PixelFacing.LEFT
    }
    val frame = if (moving) {
        (kotlin.math.floor(stepPhase / Math.PI).toInt() and 1)
    } else 0
    drawShadow(x, groundY + 4f, 23f * scale, 5f)
    blit(
        set.getValue(pixelFacing)[frame],
        x - 38f * scale, groundY - 95f * scale,
        (76f * scale).toInt(), (95f * scale).toInt(),
        alpha = alpha,
        colorFilter = if (flash) HIT_FLASH_FILTER else null
    )
}

/**
 * Real-art soldier: one 128px atlas cell drawn 104x104, feet at
 * (x, groundY). The figure fills the bottom 112px of its cell, so the
 * visible figure stands ~91 units tall — the same presence as the
 * code-drawn sprite. Same facing rule as [drawStandingFigure]
 * (dominant axis); the walk column advances at ~7 fps while [moving],
 * idle is column 0.
 */
fun DrawScope.drawAtlasFigure(
    x: Float,
    groundY: Float,
    facing: Offset,
    elapsed: Float,
    phase: Float,
    moving: Boolean,
    set: Map<PixelFacing, Array<ImageBitmap>>,
    alpha: Float = 1f,
    flash: Boolean = false,
    scale: Float = 1f
) {
    val pixelFacing = if (kotlin.math.abs(facing.y) >= kotlin.math.abs(facing.x)) {
        if (facing.y >= 0f) PixelFacing.DOWN else PixelFacing.UP
    } else {
        if (facing.x >= 0f) PixelFacing.RIGHT else PixelFacing.LEFT
    }
    val frames = set.getValue(pixelFacing)
    val frame = if (moving) {
        Math.floorMod((elapsed * 7f + phase).toInt(), frames.size)
    } else 0
    drawShadow(x, groundY + 4f, 23f * scale, 5f)
    val dst = (104f * scale).toInt()
    blit(
        frames[frame], x - 52f * scale, groundY - 104f * scale, dst, dst,
        alpha = alpha,
        colorFilter = if (flash) HIT_FLASH_FILTER else null
    )
}

/** Downed member: lying-flat sprite (native 28x13 drawn 88x41). */
fun DrawScope.drawDownFigure(art: PixelArt, x: Float, y: Float) {
    drawShadow(x, y + 6f, 26f, 5f)
    blit(art.downed, x - 44f, y - 18f, 88, 41)
}

/**
 * One bullet in flight at chest height (logical y - 55).
 * Member rounds: the pale square + trail squares, unchanged.
 * Enemy rounds (v0.6.2): the old 7px deep-orange (0xFFFF7043) square
 * vanished against the bright dirt and wheat — smuggler fire looked
 * absent on device even though the bullets flew and hit. Enemy
 * rounds now carry an orange glow halo with a white-yellow core,
 * over the same fading trail.
 */
fun DrawScope.drawBullet(b: Bullet) {
    val cy = b.y - 55f
    if (b.fromEnemy) {
        for (i in 2 downTo 1) {
            val tx = b.x - b.dirX * 10f * i
            val ty = cy - b.dirY * 10f * i
            val s = 6f - 1.6f * i
            drawRect(
                color = Color(0xFFFFAB91).copy(alpha = 0.55f - 0.18f * i),
                topLeft = Offset(tx - s / 2f, ty - s / 2f),
                size = Size(s, s)
            )
        }
        drawCircle(
            color = Color(0xFFFF9800).copy(alpha = 0.6f),
            radius = 9f,
            center = Offset(b.x, cy)
        )
        drawCircle(color = Color(0xFFFFF59D), radius = 5f, center = Offset(b.x, cy))
        drawCircle(color = Color.White, radius = 2.6f, center = Offset(b.x, cy))
        return
    }
    for (i in 2 downTo 1) {
        val tx = b.x - b.dirX * 10f * i
        val ty = cy - b.dirY * 10f * i
        val s = 6f - 1.6f * i
        drawRect(
            color = Color.White.copy(alpha = 0.55f - 0.18f * i),
            topLeft = Offset(tx - s / 2f, ty - s / 2f),
            size = Size(s, s)
        )
    }
    drawRect(
        color = Color(0xFFFFF59D),
        topLeft = Offset(b.x - 3.5f, cy - 3.5f),
        size = Size(7f, 7f)
    )
}

/**
 * Enemy muzzle flash (v0.6.2): a ~60 ms star at the gun tip — long
 * orange spikes, a yellow mid-star and a white core — drawn in the
 * effects pass, above the figures. The members' chest-level
 * [drawPixelBurst] stays as it is; this one is only for enemy fire,
 * whose old presentation (the same pale burst plus a 7px dark
 * bullet) did not read on a real screen.
 */
fun DrawScope.drawMuzzleFlash(cx: Float, cy: Float, alpha: Float) {
    val spike = Color(0xFFFF9800).copy(alpha = alpha * 0.85f)
    val mid = Color(0xFFFFEB3B).copy(alpha = alpha * 0.95f)
    val core = Color(0xFFFFFFFF).copy(alpha = alpha)
    drawRect(color = spike, topLeft = Offset(cx - 15f, cy - 2.5f), size = Size(30f, 5f))
    drawRect(color = spike, topLeft = Offset(cx - 2.5f, cy - 11f), size = Size(5f, 22f))
    drawRect(color = mid, topLeft = Offset(cx - 9f, cy - 4f), size = Size(18f, 8f))
    drawRect(color = mid, topLeft = Offset(cx - 4f, cy - 9f), size = Size(8f, 18f))
    drawRect(color = core, topLeft = Offset(cx - 4.5f, cy - 4.5f), size = Size(9f, 9f))
}

/** A pickup on the ground: shadow + bobbing pixel icon. */
fun DrawScope.drawPickup(art: PixelArt, pk: Pickup, elapsed: Float, assets: SpriteAssets? = null) {
    val bob = sin((elapsed * 3f + pk.phase).toDouble()).toFloat() * 3f
    drawShadow(pk.x, pk.y + 12f, 15f, 4f)
    val bmp = if (pk.kind == PICKUP_MEDKIT && assets != null) {
        assets.medkit
    } else if (pk.kind == PICKUP_MEDKIT) {
        art.medkit
    } else {
        art.rapid
    }
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
    art: PixelArt,
    assets: SpriteAssets? = null
) {
    val w = world.w
    val h = world.h
    if (w <= 0f || h <= 0f) return

    // Screen shake: the world (not the joystick) jitters on explosions.
    if (world.shake > 0f) {
        val sx = sin((world.elapsed * 95f).toDouble()).toFloat() * world.shake
        val sy = cos((world.elapsed * 121f).toDouble()).toFloat() * world.shake * 0.7f
        withTransform({ translate(sx, sy) }) {
            drawGameWorld(world, faction, joyVec, holdOn, art, assets)
        }
    } else {
        drawGameWorld(world, faction, joyVec, holdOn, art, assets)
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
    art: PixelArt,
    assets: SpriteAssets? = null
) {
    val w = world.w
    val h = world.h

    // ---- Ground layer: dirt base, plots, tufts, canal, fence. ----
    // v0.6.1: the plot list is explicit and shared by both art paths
    // (world.plots, built in initWorld) — dirt dominates, with 3 crop
    // plots and one broken wheat line as the only fields. The old
    // 4x6 plot grid is gone.
    if (assets != null) {
        // The real dirt texture IS the ground, stretched over the
        // whole world. (Road strip dropped, as in v0.6.0.)
        stretch(assets.texDirt, 0f, 0f, w, h)
    } else {
        // Fallback base: code-drawn dirt tiles over everything.
        var dirtY = 0f
        while (dirtY < h) {
            var dirtX = 0f
            while (dirtX < w) {
                blit(art.dirt, dirtX, dirtY, 192, 192)
                dirtX += 192f
            }
            dirtY += 192f
        }
    }
    world.plots.forEachIndexed { idx, p ->
        if (assets != null) {
            stretch(
                if (p.wheat) assets.texWheat else assets.texCrop,
                p.x0, p.y0, p.x1, p.y1
            )
        } else {
            val bmp = if (p.wheat) {
                if (idx % 2 == 0) art.wheatA else art.wheatB
            } else {
                if (idx % 2 == 0) art.cropA else art.cropB
            }
            tileRect(bmp, p.x0, p.y0, p.x1, p.y1)
        }
        if (p.main) {
            drawRect(
                color = Color(0xFF5D4630).copy(alpha = 0.50f),
                topLeft = Offset(p.x0, p.y0),
                size = Size(p.x1 - p.x0, p.y1 - p.y0),
                style = Stroke(width = 3f)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.18f),
                topLeft = Offset(p.x0, p.y1 - 5f),
                size = Size(p.x1 - p.x0, 5f)
            )
        }
    }
    // Wild-grass tufts on the dirt (real-art path): above the ground,
    // below everything else, no depth sort.
    if (assets != null) {
        for (t in world.tuftSpots) {
            val bmp = if (t.flip) assets.tuftsFlip[t.variant] else assets.tufts[t.variant]
            blit(bmp, t.x - t.size / 2f, t.y - t.size, t.size.toInt(), t.size.toInt())
        }
    }
    if (assets == null) {
        // Dirt road (fallback path only, v0.5.0 behaviour): bitmap
        // strip (native 64x16 drawn 192x48).
        val roadY = h * 0.545f
        tileRect(art.road, 0f, roadY, w, roadY + 42f, tileW = 192, tileH = 48)
    }

    // Canal along the right side: two baked variants alternate down the
    // strip, mud banks at both edges. v0.6.1: narrowed to 60% of its
    // old width (0.075w -> 0.045w), right edge unchanged, so Camp B
    // and its jeep keep their land with room to spare.
    val cx0 = w * 0.895f
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
    // Its base line is the watchtower's base (initWorld), so the tower
    // always stands on the fence — including after the v0.6.1 Camp A
    // shift. The segment is drawn 48x60 (the v0.6.1 bitmap is 16x20:
    // taller, thicker capped posts), bottom edge on the old ground
    // line (fenceBase + 8).
    val fenceBase = world.towers.firstOrNull()?.y ?: (h * 0.16f + 9f)
    var fenceX = 0f
    while (fenceX < w) {
        blit(art.fence, fenceX, fenceBase - 52f, 48, 60)
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
    // centre, drawn under every entity. Hidden while the jeep is still
    // driving in (the squad is not on the field yet).
    val squadOnField = !(world.intro && world.introT < INTRO_DRIVE_END)
    if (world.members.isNotEmpty() && squadOnField) {
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
    for (t in world.towers) {
        drawables.add(t.y to { drawWatchtower(art, t.x, t.y, assets) })
    }
    // Camp decor (real-art path only): tents and the smugglers' parked
    // jeep, so the camps read as places. Purely visual.
    if (assets != null) {
        for (tentPos in world.tents) {
            drawables.add(tentPos.y to { drawTent(assets, tentPos.x, tentPos.y) })
        }
        val cj = world.campJeep
        if (cj != null) {
            drawables.add(cj.y to {
                drawShadow(cj.x, cj.y + 3f, 40f, 7f)
                blit(assets.jeep, cj.x - 39f, cj.y - 102f, 78, 102)
            })
        }
        // The squad's own jeep: driving in during the intro, parked at
        // the drop point for the rest of the mission.
        drawables.add(world.jeepY + 51f to {
            drawShadow(world.jeepX, world.jeepY + 52f, 40f, 7f)
            blit(assets.jeep, world.jeepX - 39f, world.jeepY - 51f, 78, 102)
        })
    }
    for (o in world.obstacles) {
        if (o.tree) {
            drawables.add(o.y + o.r * 0.62f to { drawTree(art, o, assets) })
        } else {
            drawables.add(o.y + o.r * 0.72f to { drawRock(art, o) })
        }
    }
    for (c in world.crates) {
        drawables.add(c.y + 36f to { drawCrate(art, c, assets) })
    }
    for (e in world.enemies) {
        if (!e.alive) continue
        val target = world.members
            .filter { !it.down }
            .minByOrNull { distBetween(e.x, e.y, it.x, it.y) }
        val eFacing = if (target != null) {
            facingToward(e.x, e.y, target.x, target.y, Offset(0f, 1f))
        } else Offset(0f, 1f)
        val eMoving = !e.stationary && (target == null ||
            distBetween(e.x, e.y, target.x, target.y) > 172f)
        // The sentry's sort key sits just past his tower's base so he
        // draws AFTER (above) the tower sprite, standing on its
        // platform, even though his feet are 105 units higher.
        val eSortKey = if (e.stationary) e.y + 110f else e.y
        // v0.6.1: the sentry is drawn at 0.78 scale — same man, same
        // stats, but he read oversized standing on the tower. His
        // position (and so his bullet origin) is untouched.
        val eScale = if (e.stationary) 0.78f else 1f
        drawables.add(eSortKey to {
            if (assets != null) {
                drawAtlasFigure(
                    e.x, e.y, eFacing, world.elapsed, e.phase, eMoving,
                    assets.smuggler, flash = e.flash > 0f, scale = eScale
                )
            } else {
                drawStandingFigure(
                    e.x, e.y, eFacing, world.elapsed * 7f + e.phase, eMoving, art.enemy,
                    flash = e.flash > 0f, scale = eScale
                )
            }
            if (e.hp < e.maxHp) {
                drawHealthBar(e.x, e.y - 108f * eScale, e.hp / e.maxHp, 34f)
            }
        })
    }
    for (d in world.deaths) {
        drawables.add(d.y to {
            val fade = (d.ttl / 0.25f).coerceIn(0f, 1f)
            if (assets != null) {
                drawAtlasFigure(
                    d.x, d.y, d.facing, world.elapsed, 0f, false,
                    assets.smuggler, alpha = fade
                )
            } else {
                drawStandingFigure(
                    d.x, d.y, d.facing, 0f, false, art.enemy, alpha = fade
                )
            }
        })
    }
    for (pk in world.pickups) {
        drawables.add(pk.y + 2f to { drawPickup(art, pk, world.elapsed, assets) })
    }
    val memberSet = if (faction == Faction.PAKISTAN) art.pak else art.ind
    val memberAtlas = assets?.let { if (faction == Faction.PAKISTAN) it.pak else it.ind }
    val squadMoving = !holdOn && joyVec.getDistance() > 3f
    world.members.forEachIndexed { idx, m ->
        // During the jeep drive-in the squad is still inside the jeep:
        // members only appear for the step-out walk.
        if (!squadOnField) return@forEachIndexed
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
                if (memberAtlas != null) {
                    drawAtlasFigure(
                        m.x, m.y, mFacing, world.elapsed, idx * 1.3f,
                        squadMoving, memberAtlas
                    )
                } else {
                    drawStandingFigure(
                        m.x, m.y, mFacing, world.elapsed * 11f + idx * 1.3f,
                        squadMoving, memberSet
                    )
                }
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
            // x1/y1 already hold the gun-tip centre at bullet height
            // (the spawn point did the -55), so no offset here.
            FX_MUZZLE -> drawMuzzleFlash(fx.x1, fx.y1, a)
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

    // ---- Explosion sequences (real art): 6 frames over ~0.55 s,
    // centred on the blast point, over the entities. The particle
    // flames and the pixel blast ring keep burning alongside. ----
    if (assets != null) {
        for (bo in world.booms) {
            val age = bo.maxTtl - bo.ttl
            val frame = (age / bo.maxTtl * 6f).toInt().coerceIn(0, 5)
            drawImage(
                image = assets.explosion,
                srcOffset = IntOffset(frame * 128, 0),
                srcSize = IntSize(128, 128),
                dstOffset = IntOffset(
                    (bo.x - bo.size / 2f).toInt(), (bo.y - bo.size / 2f).toInt()
                ),
                dstSize = IntSize(bo.size.toInt(), bo.size.toInt()),
                filterQuality = FilterQuality.None
            )
        }
    }

    // ---- Floating texts (damage numbers, pickup announcements). ----
    for (f in world.floaters) {
        val a = (f.ttl / f.maxTtl).coerceIn(0f, 1f)
        drawCanvasText(f.text, f.x, f.y, f.size, f.argb, a)
    }

    // ---- Call-sign labels, drawn in the Canvas so they track the
    // figures exactly (the old Compose overlay lagged behind). Each
    // label is staggered 13 units above the previous one (v0.6.0), so
    // a bunched-up squad reads as a short stack, not one merged smear.
    // ----
    if (squadOnField) {
        world.members.forEachIndexed { idx, m ->
            if (m.down) {
                drawCanvasText("DOWN", m.x, m.y - 30f, 30f, 0xFFFF5252.toInt())
            } else {
                drawCanvasText(
                    m.name, m.x, m.y - 118f - idx * 13f, 30f,
                    0xFFFFFFFF.toInt()
                )
            }
        }
    }
}
