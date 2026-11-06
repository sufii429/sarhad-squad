package app.sarhadsquad.android

import android.os.Bundle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ============================================================================
// Sarhad Squad: Border War — Mission 1 prototype (v0.1.0)
// 2D top-down squad shooter. TITLE -> GAME -> RESULT state machine.
// All in-game text is English only.
// ============================================================================

enum class Faction { PAKISTAN, INDIA }

enum class Screen { TITLE, GAME, RESULT }

// Effect kinds (drawn in the Canvas, removed when ttl runs out).
const val FX_TRACER = 0
const val FX_ENEMY_TRACER = 1
const val FX_HIT = 2
const val FX_BLAST = 3

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
    var hp = 34f
    val maxHp = 34f
    var cd = 0f
    var alive = true
}

class CrateEnt(var x: Float, var y: Float) {
    var hp = 45f
    val maxHp = 45f
    var destroyed = false
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
    var frameTick by remember { mutableIntStateOf(0) }
    var holdOn by remember { mutableStateOf(false) }
    var joyVec by remember { mutableStateOf(Offset.Zero) }

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
                e.hp -= 60f
                if (e.hp <= 0f) { e.hp = 0f; e.alive = false }
            }
        }
        for (c in world.crates) {
            if (!c.destroyed && distBetween(center.x, center.y, c.x, c.y) <= 115f) {
                c.hp -= 60f
                if (c.hp <= 0f) { c.hp = 0f; c.destroyed = true }
            }
        }
        world.effects.add(Effect(FX_BLAST, center.x, center.y, center.x, center.y, 0.35f, 0.35f))
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

        // -- Squad movement: anchor moves, members follow with offsets. --
        if (!holdOn) {
            val len = joyVec.getDistance()
            if (len > 3f) {
                val mag = (len / 60f).coerceIn(0f, 1f)
                world.anchorX += joyVec.x / len * 155f * mag * dt
                world.anchorY += joyVec.y / len * 155f * mag * dt
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
                val stiff = if (m.name == "BULL") 5.5f else 7.5f
                val t = min(1f, dt * stiff)
                m.x += (tx - m.x) * t
                m.y += (ty - m.y) * t
            }
            pushMemberOut(world, m)
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
                    nearest.hp -= 4f + Random.nextFloat() * 3f
                    world.effects.add(
                        Effect(FX_ENEMY_TRACER, e.x, e.y, nearest.x, nearest.y, 0.08f, 0.08f)
                    )
                    if (nearest.hp <= 0f) {
                        nearest.hp = 0f
                        nearest.down = true
                        nearest.wasDowned = true
                    }
                }
            } else {
                val wx = e.ax + (cos((world.elapsed * 0.35f + e.phase).toDouble()) * 46.0).toFloat()
                val wy = e.ay + (sin((world.elapsed * 0.27f + e.phase).toDouble()) * 30.0).toFloat()
                moveEnemyToward(e, wx, wy, 26f, dt)
            }
        }

        // -- Squad auto-fire: nearest enemy in range, else nearest crate. --
        for (m in world.members) {
            if (m.down) continue
            m.cd -= dt
            if (m.cd > 0f) continue
            var fired = false
            val target = world.enemies
                .filter { it.alive }
                .minByOrNull { distBetween(m.x, m.y, it.x, it.y) }
            if (target != null && distBetween(m.x, m.y, target.x, target.y) <= 285f) {
                target.hp -= m.damage
                world.effects.add(Effect(FX_TRACER, m.x, m.y, target.x, target.y, 0.07f, 0.07f))
                world.effects.add(Effect(FX_HIT, target.x, target.y, target.x, target.y, 0.12f, 0.12f))
                if (target.hp <= 0f) { target.hp = 0f; target.alive = false }
                fired = true
            } else {
                val crate = world.crates
                    .filter { !it.destroyed }
                    .minByOrNull { distBetween(m.x, m.y, it.x, it.y) }
                if (crate != null && distBetween(m.x, m.y, crate.x, crate.y) <= 285f) {
                    crate.hp -= m.damage
                    world.effects.add(Effect(FX_TRACER, m.x, m.y, crate.x, crate.y, 0.07f, 0.07f))
                    if (crate.hp <= 0f) { crate.hp = 0f; crate.destroyed = true }
                    fired = true
                }
            }
            if (fired) m.cd = m.fireInterval
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
                        var tapStart: Offset? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val jc = Offset(size.width * 0.17f, size.height * 0.80f)
                            when {
                                change.pressed && !change.previousPressed -> {
                                    if ((change.position - jc).getDistance() < 120f) {
                                        joyActive = true
                                        val v = change.position - jc
                                        val l = v.getDistance()
                                        joyVec = if (l > 60f) v / l * 60f else v
                                    } else {
                                        tapStart = change.position
                                    }
                                }
                                change.pressed && joyActive -> {
                                    val v = change.position - jc
                                    val l = v.getDistance()
                                    joyVec = if (l > 60f) v / l * 60f else v
                                }
                                !change.pressed && change.previousPressed -> {
                                    if (joyActive) {
                                        joyActive = false
                                        joyVec = Offset.Zero
                                    } else {
                                        tapStart?.let { handleTap(it) }
                                        tapStart = null
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Reading frameTick here redraws the canvas every frame.
            val tick = frameTick
            if (tick >= 0 && world.initialized) {
                drawGame(world, faction, joyVec)
            }
        }

        // Call-sign labels above squad members (Compose text over the canvas).
        if (world.initialized) {
            val tick = frameTick
            for (m in world.members) {
                if (tick < -1) continue
                Text(
                    if (m.down) "DOWN" else m.name,
                    color = if (m.down) Color(0xFFFF5252) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.offset {
                        IntOffset((m.x - 22f).toInt(), (m.y - 54f).toInt())
                    }
                )
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

// --- Canvas drawing ----------------------------------------------------------

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

/** Draws the whole map + entities. Map coordinates = canvas pixels. */
fun DrawScope.drawGame(world: GameWorld, faction: Faction, joyVec: Offset) {
    val w = world.w
    val h = world.h
    if (w <= 0f || h <= 0f) return

    // Punjab fields: patchwork of greens / yellows.
    val palette = listOf(
        Color(0xFF7CB342), Color(0xFF9CCC65), Color(0xFFC0CA33),
        Color(0xFF8BC34A), Color(0xFFAED581), Color(0xFF689F38)
    )
    drawRect(color = palette[0], size = Size(w, h))
    val cols = 4
    val rows = 6
    val cellW = w / cols
    val cellH = h / rows
    for (i in 0 until cols) {
        for (j in 0 until rows) {
            val color = palette[(i * 3 + j * 5 + i * j) % palette.size]
            drawRect(
                color = color,
                topLeft = Offset(i * cellW, j * cellH),
                size = Size(cellW + 1f, cellH + 1f)
            )
        }
    }

    // Dirt road crossing horizontally.
    drawRect(
        color = Color(0xFFB08968),
        topLeft = Offset(0f, h * 0.545f),
        size = Size(w, 34f)
    )
    drawRect(
        color = Color(0xFF96714F),
        topLeft = Offset(0f, h * 0.545f + 15f),
        size = Size(w, 3f)
    )

    // Canal strip along the right side.
    drawRect(
        color = Color(0xFF42A5F5),
        topLeft = Offset(w * 0.865f, 0f),
        size = Size(w * 0.075f, h)
    )
    drawRect(
        color = Color(0xFF90CAF9),
        topLeft = Offset(w * 0.895f, 0f),
        size = Size(w * 0.012f, h)
    )

    // Border fence line across the top, with posts.
    val fenceY = h * 0.16f
    drawLine(Color(0xFF37474F), Offset(0f, fenceY), Offset(w, fenceY), strokeWidth = 3f)
    drawLine(Color(0xFF37474F), Offset(0f, fenceY + 9f), Offset(w, fenceY + 9f), strokeWidth = 2f)
    var px = 10f
    while (px < w) {
        drawRect(
            color = Color(0xFF263238),
            topLeft = Offset(px, fenceY - 8f),
            size = Size(4f, 26f)
        )
        px += 46f
    }
    // Watchtowers (two squares on the fence line).
    for (tx in listOf(w * 0.22f, w * 0.68f)) {
        drawRect(
            color = Color(0xFF6D4C41),
            topLeft = Offset(tx - 19f, fenceY - 19f),
            size = Size(38f, 38f)
        )
        drawRect(
            color = Color(0xFF4E342E),
            topLeft = Offset(tx - 12f, fenceY - 12f),
            size = Size(24f, 24f)
        )
    }

    // Cover: trees and rocks.
    for (o in world.obstacles) {
        if (o.tree) {
            drawCircle(color = Color(0xFF5D4037), radius = o.r * 0.28f, center = Offset(o.x, o.y))
            drawCircle(color = Color(0xFF2E7D32), radius = o.r, center = Offset(o.x, o.y))
            drawCircle(color = Color(0xFF43A047), radius = o.r * 0.62f, center = Offset(o.x, o.y))
        } else {
            drawCircle(color = Color(0xFF757575), radius = o.r, center = Offset(o.x, o.y))
            drawCircle(color = Color(0xFF9E9E9E), radius = o.r * 0.55f, center = Offset(o.x - o.r * 0.2f, o.y - o.r * 0.2f))
        }
    }

    // Destructible crates (44px boxes with an X).
    for (c in world.crates) {
        val half = 22f
        val base = if (c.destroyed) Color(0xFF4E342E).copy(alpha = 0.45f) else Color(0xFF8D6E63)
        drawRect(
            color = base,
            topLeft = Offset(c.x - half, c.y - half),
            size = Size(half * 2f, half * 2f)
        )
        drawLine(Color(0xFF5D4037), Offset(c.x - half, c.y - half), Offset(c.x + half, c.y + half), strokeWidth = 3f)
        drawLine(Color(0xFF5D4037), Offset(c.x + half, c.y - half), Offset(c.x - half, c.y + half), strokeWidth = 3f)
        if (!c.destroyed && c.hp < c.maxHp) {
            drawHealthBar(c.x, c.y - half - 12f, c.hp / c.maxHp)
        }
    }

    // Enemies: dark figures with a red headband dot.
    for (e in world.enemies) {
        if (!e.alive) continue
        drawCircle(color = Color(0xFF2B2B2B), radius = 14f, center = Offset(e.x, e.y))
        drawCircle(color = Color(0xFFD7A17A), radius = 6.5f, center = Offset(e.x, e.y))
        drawCircle(color = Color(0xFFD32F2F), radius = 3.5f, center = Offset(e.x, e.y - 7f))
        if (e.hp < e.maxHp) {
            drawHealthBar(e.x, e.y - 30f, e.hp / e.maxHp, 34f)
        }
    }

    // Effects under the squad: tracers, hit flashes, grenade blast.
    for (fx in world.effects) {
        val a = (fx.ttl / fx.maxTtl).coerceIn(0f, 1f)
        when (fx.kind) {
            FX_TRACER -> drawLine(
                Color(0xFFFFEB3B).copy(alpha = a),
                Offset(fx.x1, fx.y1), Offset(fx.x2, fx.y2), strokeWidth = 2.5f
            )
            FX_ENEMY_TRACER -> drawLine(
                Color(0xFFFF7043).copy(alpha = a),
                Offset(fx.x1, fx.y1), Offset(fx.x2, fx.y2), strokeWidth = 2.5f
            )
            FX_HIT -> drawCircle(
                color = Color(0xFFFFF59D).copy(alpha = a),
                radius = 7f,
                center = Offset(fx.x1, fx.y1)
            )
            FX_BLAST -> {
                val progress = 1f - a
                val radius = 20f + progress * 95f
                drawCircle(
                    color = Color(0xFFFF9800).copy(alpha = 0.22f * a),
                    radius = radius,
                    center = Offset(fx.x1, fx.y1)
                )
                drawCircle(
                    color = Color(0xFFFF5722).copy(alpha = a),
                    radius = radius,
                    center = Offset(fx.x1, fx.y1),
                    style = Stroke(width = 4f)
                )
            }
        }
    }

    // Squad members.
    val uniform = uniformColor(faction)
    for (m in world.members) {
        if (m.down) {
            // Greyed-out body; the "DOWN" label is a Compose text overlay.
            drawCircle(color = Color(0xFF9E9E9E), radius = 15f, center = Offset(m.x, m.y))
            continue
        }
        drawCircle(color = uniform, radius = 15f, center = Offset(m.x, m.y))
        drawCircle(color = Color(0xFFE0B090), radius = 6.5f, center = Offset(m.x, m.y))
        // Flag patch on the right side of the uniform.
        when (faction) {
            Faction.PAKISTAN -> {
                drawRect(color = Color.White, topLeft = Offset(m.x + 9f, m.y - 5f), size = Size(3f, 10f))
                drawRect(color = Color(0xFF046A38), topLeft = Offset(m.x + 12f, m.y - 5f), size = Size(8f, 10f))
            }
            Faction.INDIA -> {
                drawRect(color = Color(0xFFFF9933), topLeft = Offset(m.x + 9f, m.y - 5f), size = Size(10f, 3.5f))
                drawRect(color = Color.White, topLeft = Offset(m.x + 9f, m.y - 1.5f), size = Size(10f, 3.5f))
                drawRect(color = Color(0xFF138808), topLeft = Offset(m.x + 9f, m.y + 2f), size = Size(10f, 3.5f))
            }
        }
        // The call-sign label is a Compose text overlay (see GameScreen).
        drawHealthBar(m.x, m.y - 32f, m.hp / m.maxHp)
    }

    // Virtual joystick (bottom-left): base + knob.
    val jc = Offset(w * 0.17f, h * 0.80f)
    drawCircle(color = Color.White.copy(alpha = 0.16f), radius = 62f, center = jc)
    drawCircle(
        color = Color.White.copy(alpha = 0.45f),
        radius = 62f,
        center = jc,
        style = Stroke(width = 2f)
    )
    val knob = jc + joyVec * 0.62f
    drawCircle(color = Color.White.copy(alpha = 0.38f), radius = 26f, center = knob)
}
