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
import androidx.compose.ui.graphics.Path
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
// Sarhad Squad: Border War — Mission 1 prototype (v0.3.0)
// Pixel-art squad shooter. TITLE -> GAME -> RESULT state machine.
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
                drawGame(world, faction, joyVec, holdOn)
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
                        IntOffset((m.x - 22f).toInt(), (m.y - 120f).toInt())
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

// --- Canvas drawing (pixel-art rendering; gameplay coordinates unchanged) ---

/** Darken / lighten a colour by a factor (slab edges, side faces, shades). */
fun shadeOf(c: Color, f: Float): Color = Color(
    (c.red * f).coerceIn(0f, 1f),
    (c.green * f).coerceIn(0f, 1f),
    (c.blue * f).coerceIn(0f, 1f),
    c.alpha
)

// --- Pixel-art sprite system -------------------------------------------------
// Sprites are string grids: one char per pixel, '.' is transparent. Every
// palette carries a near-black outline colour ('K') so figures and props
// pop against the ground, like the pixel-style target mockup.

enum class PixelFacing { DOWN, UP, LEFT, RIGHT }

fun DrawScope.drawPixelSprite(
    rows: Array<String>,
    palette: Map<Char, Color>,
    left: Float,
    top: Float,
    pixel: Float,
    alpha: Float = 1f
) {
    // The +0.7f overlap keeps scaled-up pixels free of hairline gaps.
    val cell = Size(pixel + 0.7f, pixel + 0.7f)
    for (r in rows.indices) {
        val row = rows[r]
        for (c in row.indices) {
            val ch = row[c]
            if (ch == '.' || ch == ' ') continue
            val color = palette[ch] ?: continue
            drawRect(
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(left + c * pixel, top + r * pixel),
                size = cell
            )
        }
    }
}

/** Stable position hash: same tile, same pattern, every single frame. */
fun pixelHash(ix: Int, iy: Int, seed: Int): Int {
    var n = ix * 374761393 + iy * 668265263 + seed * 362437
    n = (n xor (n shr 13)) * 1274126177
    return n xor (n shr 16)
}

// Soldier body rows (16x16) per facing; the two walk frames per facing
// differ only in the four leg rows appended below the body.
val SOLDIER_DOWN_BODY = arrayOf(
    ".....KKKKKK.....",
    "....KHHHHHHK....",
    "...KHHhhhhHHK...",
    "...KHHSSSSHHK...",
    "....KSSSSSSK....",
    "....KSSSSSSK....",
    ".....KUUUUK.....",
    "..KKUUUUUUUUKK..",
    ".KUUUKUUUUUURK..",
    ".KUUUUFFUUUURRK.",
    ".KUUUUWWUUUURK..",
    "..KUUUGGUUUUK...",
    "..KUUUUUUUUUUK..",
    "..KUUUUUUUUUUK..",
    "...KUUUUUUUUK...",
    "...KPPPPPPPPK..."
)
val SOLDIER_UP_BODY = arrayOf(
    ".....KKKKKK.....",
    "....KHHHHHHK....",
    "...KHHHHHHHHK...",
    "...KHHHHHHHHK...",
    "....KHHHHHHK....",
    ".....KUUUUK.....",
    "..KKUUUUUUUUKR..",
    ".KUUUUUUUUUUURK.",
    ".KUUUuuuuUUURK..",
    ".KUUUuuuuUUUUK..",
    ".KUUUuuuuUUUUK..",
    "..KUUUUUUUUUK...",
    "..KUUUUUUUUUK...",
    "...KUUUUUUUK....",
    "...KUUUUUUUK....",
    "...KPPPPPPPK...."
)
val SOLDIER_RIGHT_BODY = arrayOf(
    "....KKKKKKK.....",
    "...KHHHHHHHK....",
    "...KHhhhhhHK....",
    "...KHSSSSSH.....",
    "....KSSSSSK.....",
    ".....KUUUK......",
    "....KUUUUUKKK...",
    "....KUUUUUKRRRK.",
    "....KUUFUUKRRRK.",
    "....KUUGUUKRRRK.",
    "....KUUUUUK.K...",
    "....KUUUUUK.....",
    "....KUUUUUK.....",
    ".....KUUUK......",
    ".....KPPPK......",
    ".....KPPPK......"
)
val SOLDIER_LEGS_FRONT_A = arrayOf(
    "...KPPP..PPPK...",
    "...KPPP..PPPK...",
    "...KPPP....PPK..",
    "..KBBBK....KBBK."
)
val SOLDIER_LEGS_FRONT_B = arrayOf(
    "...KPPP..PPPK...",
    "....KPPK.KPPPK..",
    "..KPPK....KPPP..",
    ".KBBBK......KBBB"
)
val SOLDIER_LEGS_SIDE_A = arrayOf(
    "....KPPK.KPPK...",
    "....KPPK..KPPK..",
    "...KPPPK...KPPK.",
    "...KBBBK....KBBK"
)
val SOLDIER_LEGS_SIDE_B = arrayOf(
    ".....KPPK.KPPK..",
    "....KPPK.KPPK...",
    "....KPPK....PPK.",
    "....KBBK....KBBB"
)
val SOLDIER_DOWN_A = SOLDIER_DOWN_BODY + SOLDIER_LEGS_FRONT_A
val SOLDIER_DOWN_B = SOLDIER_DOWN_BODY + SOLDIER_LEGS_FRONT_B
val SOLDIER_UP_A = SOLDIER_UP_BODY + SOLDIER_LEGS_FRONT_A
val SOLDIER_UP_B = SOLDIER_UP_BODY + SOLDIER_LEGS_FRONT_B
val SOLDIER_RIGHT_A = SOLDIER_RIGHT_BODY + SOLDIER_LEGS_SIDE_A
val SOLDIER_RIGHT_B = SOLDIER_RIGHT_BODY + SOLDIER_LEGS_SIDE_B
val SOLDIER_LEFT_A = SOLDIER_RIGHT_A.map { it.reversed() }.toTypedArray()
val SOLDIER_LEFT_B = SOLDIER_RIGHT_B.map { it.reversed() }.toTypedArray()

fun soldierSprite(facing: PixelFacing, frame: Int): Array<String> = when (facing) {
    PixelFacing.DOWN -> if (frame == 0) SOLDIER_DOWN_A else SOLDIER_DOWN_B
    PixelFacing.UP -> if (frame == 0) SOLDIER_UP_A else SOLDIER_UP_B
    PixelFacing.RIGHT -> if (frame == 0) SOLDIER_RIGHT_A else SOLDIER_RIGHT_B
    PixelFacing.LEFT -> if (frame == 0) SOLDIER_LEFT_A else SOLDIER_LEFT_B
}

/** Palette swap on the shared soldier grids: role + faction decide colours. */
fun soldierPalette(enemy: Boolean, faction: Faction?): Map<Char, Color> {
    val outline = Color(0xFF0D120C)
    val skin = Color(0xFFD89A62)
    val boot = Color(0xFF17130F)
    val rifle = Color(0xFF241A12)
    if (enemy) {
        return mapOf(
            'K' to outline,
            'H' to Color(0xFFD32F2F), // red headband
            'h' to Color(0xFF8E1414),
            'S' to skin,
            'U' to Color(0xFF33373B), // charcoal clothes
            'u' to Color(0xFF202327),
            'P' to Color(0xFF17181A),
            'B' to boot,
            'R' to rifle,
            'F' to Color(0xFFD32F2F),
            'W' to Color(0xFF9E9E9E),
            'G' to Color(0xFF33373B)
        )
    }
    return when (faction) {
        Faction.INDIA -> mapOf(
            'K' to outline,
            'H' to Color(0xFF4C522C),
            'h' to Color(0xFF7A8152),
            'S' to skin,
            'U' to Color(0xFF6B7040), // olive-khaki uniform
            'u' to Color(0xFF565C31),
            'P' to Color(0xFF4F5433),
            'B' to boot,
            'R' to rifle,
            'F' to Color(0xFFFF7A1A), // flag patch: saffron / white / green
            'W' to Color.White,
            'G' to Color(0xFF138808)
        )
        else -> mapOf( // PAKISTAN (and fallback)
            'K' to outline,
            'H' to Color(0xFF1F4420),
            'h' to Color(0xFF40693A),
            'S' to skin,
            'U' to Color(0xFF2E5B27), // deep-green uniform
            'u' to Color(0xFF22451D),
            'P' to Color(0xFF24471F),
            'B' to boot,
            'R' to rifle,
            'F' to Color(0xFF046A38), // flag patch: green + white
            'W' to Color.White,
            'G' to Color(0xFF046A38)
        )
    }
}

// Downed member: dedicated lying-flat sprite (20 wide x 10 tall), grey kit.
val SOLDIER_DOWNED = arrayOf(
    ".....KKKKKKKKKK.....",
    "...KKGGGGGGGGGGKK...",
    "..KGGKWWWWWWWWKGGK..",
    "..KGGGGGGGGGGGGGGK..",
    ".KGGKGGGGGGGGGGKGGK.",
    ".KGGGGGGGGGGGGGGGGK.",
    "..KGGGGGGGGGGGGGGK..",
    "...KKGGGGGGGGGGKK...",
    "....KBBK....KBBK....",
    ".....KK......KK....."
)
val DOWNED_PALETTE = mapOf(
    'K' to Color(0xFF14161A),
    'G' to Color(0xFF8A8F8A),
    'W' to Color(0xFFC9C9C9),
    'B' to Color(0xFF2B2B2B)
)

// Wooden crate (18x18): planks, plank separators and an X brace.
val PIXEL_CRATE = arrayOf(
    "....KKKKKKKKKK....",
    "..KKWWWWWWWWWWKK..",
    ".KWWWKWWWWWWKWWWK.",
    ".KWWWKWWWWWWKWWWK.",
    ".KWWWKWWWWWWKWWWK.",
    ".KWWWKWWXWWWKWWWK.",
    ".KWWWKWWWXWWKWWWK.",
    ".KWWWKWWWWXWKWWWK.",
    ".KWWWKWWWWWXKWWWK.",
    ".KWWWKWWWWXWKWWWK.",
    ".KWWWKWWWXWWKWWWK.",
    ".KWWWKWWXWWWKWWWK.",
    ".KWWWKWWWWWWKWWWK.",
    ".KDDDKDDDDDDKDDDK.",
    ".KDDDKDDDDDDKDDDK.",
    "..KKKKKKKKKKKKKK..",
    "....KKKKKKKKKK....",
    ".................."
)
val CRATE_PALETTE = mapOf(
    'K' to Color(0xFF2A1A12),
    'W' to Color(0xFFC08A4E),
    'D' to Color(0xFF7C4A24),
    'X' to Color(0xFF5D3520)
)

// Tree (20x24): three-shade canopy blob over a trunk.
val PIXEL_TREE = arrayOf(
    ".......KKKKKK.......",
    ".....KKGGGGGGKK.....",
    "....KGGLLGGGGLLGK...",
    "...KGGGLLGGGGGLLGK..",
    "..KGGGGLLGGGGGGLLK..",
    "..KGLLGGGGLLGGGGGK..",
    ".KGGGGLLGGGGGGLLGGK.",
    ".KGLLGGGGGLLGGGGGGK.",
    ".KGGGGGLLGGGGGLLGGK.",
    "..KGGGGLLGGGGGGLLK..",
    "..KGLLGGGGLLGGGGGK..",
    "...KGGGGGLLGGGGGK...",
    "...KGLLGGGGLLGGGK...",
    "....KGGGGGGGGGGK....",
    ".....KKKTTTTKKK.....",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".......KTTTTK.......",
    ".....KKDDDDDDKK....."
)
val TREE_PALETTE = mapOf(
    'K' to Color(0xFF10250F),
    'G' to Color(0xFF2E7D32),
    'L' to Color(0xFF66BB6A),
    'T' to Color(0xFF6D4C41),
    'D' to Color(0xFF4E342E)
)

// Rock (20x12): light top face over darker shaded faces.
val PIXEL_ROCK = arrayOf(
    ".......KKKKKK.......",
    ".....KKLLLLLLKK.....",
    "....KLLGGGGGGGGLK...",
    "...KLLGGGGGGGGGGGGK.",
    "..KLLGGGGGGGGGGGGGGK",
    "..KGGGGGGGGGGGGGGGGK",
    ".KGGGGGGGGGGGGGGGGGK",
    ".KDDDDGGGGGGGGGGDDDK",
    "..KDDDDDDDDDDDDDDK..",
    "...KKDDDDDDDDDDKK...",
    ".....KKKKKKKKKK.....",
    "...................."
)
val ROCK_PALETTE = mapOf(
    'K' to Color(0xFF202124),
    'L' to Color(0xFFCFCFCF),
    'G' to Color(0xFF9E9E9E),
    'D' to Color(0xFF616161)
)

// Watchtower (34x46): roof + cabin + platform rows, then generated leg
// rows with two crossing braces, then two base rows.
val PIXEL_TOWER_TOP = arrayOf(
    "............KKKKKKKKKK............",
    "..........KKKKKKKKKKKKKK..........",
    "........WWWWWWWWWWWWWWWWWW........",
    "......WWWWWWWWWWWWWWWWWWWWWW......",
    "....DDDDDDDDDDDDDDDDDDDDDDDDDD....",
    "......KKWWWWWWWWWWWWWWWWWWKK......",
    "......KWWWWWWWWWWWWWWWWWWWWK......",
    "......KWWWWWDDDDDDDDDDWWWWWK......",
    "......KWWWWWDDDDDDDDDDWWWWWK......",
    "......KWWWWWDDDDDDDDDDWWWWWK......",
    "......KWWWWWWWWWWWWWWWWWWWWK......",
    "......KKKKKKKKKKKKKKKKKKKKKK......",
    "....KKKKKKKKKKKKKKKKKKKKKKKKKK....",
    "....KWWWWWWWWWWWWWWWWWWWWWWWWK....",
    "....KKKKKKKKKKKKKKKKKKKKKKKKKK....",
    "......KDDDDDDDDDDDDDDDDDDDDK......",
    ".......KKKKKKKKKKKKKKKKKKKK.......",
    "......KDDDDDDDDDDDDDDDDDDDDK......",
    "......KKKKKKKKKKKKKKKKKKKKKK......",
    "....KKKKKKKKKKKKKKKKKKKKKKKKKK...."
)
val PIXEL_TOWER = PIXEL_TOWER_TOP + Array(24) { row ->
    val chars = CharArray(34) { '.' }
    chars[6] = 'K'; chars[7] = 'K'; chars[26] = 'K'; chars[27] = 'K'
    val braceA = 8 + (row * 17) / 23
    chars[braceA] = 'K'; chars[braceA + 1] = 'K'
    val braceB = 25 - (row * 17) / 23
    chars[braceB] = 'K'; chars[braceB + 1] = 'K'
    String(chars)
} + arrayOf(
    "......KK..................KK......",
    ".....KKKK................KKKK....."
)
val TOWER_PALETTE = mapOf(
    'K' to Color(0xFF20140D),
    'W' to Color(0xFFA9743F),
    'D' to Color(0xFF4A2E1B)
)

// Tiny 3x3 ground tufts, stamped in tiled rows across the plots.
val PIXEL_TUFT = arrayOf(
    "a.a",
    "aba",
    "..."
)
val PIXEL_WHEAT = arrayOf(
    ".a.",
    "aba",
    "a.a"
)

fun DrawScope.drawPixelPattern(
    rows: Array<String>,
    palette: Map<Char, Color>,
    left: Float,
    top: Float,
    pixel: Float
) = drawPixelSprite(rows, palette, left, top, pixel)


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

/** Fill a simple polygon from a list of points. */
fun DrawScope.fillPolygon(points: List<Offset>, color: Color) {
    if (points.size < 3) return
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    path.close()
    drawPath(path, color)
}

/** Unit facing vector from (x, y) toward (tx, ty), with a fallback. */
fun facingToward(x: Float, y: Float, tx: Float, ty: Float, fallback: Offset): Offset {
    val d = distBetween(x, y, tx, ty)
    if (d < 1f) return fallback
    return Offset((tx - x) / d, (ty - y) / d)
}

/**
 * One field plot in pixel style, inside the same rectangle the v0.2.0
 * layout used: blocky shaded edges, then tiled pixel texture — crop tuft
 * clusters for green plots, wheat tufts for the golden plot, sparse
 * hashed speckles for the plain plot. Hashing is position-based, so the
 * texture is identical every frame (no flicker).
 */
fun DrawScope.drawFieldPlot(x0: Float, y0: Float, x1: Float, y1: Float, color: Color) {
    val wheat = color == Color(0xFFC0CA33)
    val plain = color == Color(0xFFAED581)
    val base = if (wheat) Color(0xFFE0AC4C) else color
    val seedX = (x0 / 10f).toInt()
    val seedY = (y0 / 10f).toInt()
    // Blocky slab edges: darker bottom + right strips, lighter top strip.
    drawRect(
        color = shadeOf(base, 0.60f),
        topLeft = Offset(x0, y1),
        size = Size(x1 - x0, 7f)
    )
    drawRect(
        color = shadeOf(base, 0.72f),
        topLeft = Offset(x1, y0),
        size = Size(5f, y1 - y0 + 7f)
    )
    drawRect(color = base, topLeft = Offset(x0, y0), size = Size(x1 - x0, y1 - y0))
    drawRect(
        color = shadeOf(base, 1.10f).copy(alpha = 0.45f),
        topLeft = Offset(x0, y0),
        size = Size(x1 - x0, 4f)
    )
    if (wheat) {
        // Wheat: rows of golden tuft clusters + single stalk pixels.
        val tuftPalette = mapOf(
            'a' to Color(0xFFB97F26),
            'b' to Color(0xFFFFD968)
        )
        var ty = y0 + 8f
        var row = 0
        while (ty < y1 - 14f) {
            var tx = x0 + 8f + (row % 2) * 13f
            while (tx < x1 - 14f) {
                val hx = (tx / 10f).toInt()
                val hy = (ty / 10f).toInt()
                if (kotlin.math.abs(pixelHash(hx, hy, seedX + seedY) % 5) >= 2) {
                    drawPixelPattern(PIXEL_WHEAT, tuftPalette, tx, ty, 3f)
                }
                if (pixelHash(hx, hy, seedX * 3 + 7) % 4 == 0) {
                    drawRect(
                        color = Color(0xFFC98F2E),
                        topLeft = Offset(tx + 16f, ty + 2f),
                        size = Size(3f, 8f)
                    )
                }
                tx += 36f
            }
            ty += 32f
            row++
        }
    } else if (plain) {
        // Plain plot: sparse darker / lighter pixel speckles only.
        var ty = y0 + 7f
        while (ty < y1 - 7f) {
            var tx = x0 + 7f
            while (tx < x1 - 7f) {
                val hsh = pixelHash((tx / 8f).toInt(), (ty / 8f).toInt(), seedX + seedY * 5)
                if (hsh % 5 == 0) {
                    drawRect(
                        color = shadeOf(base, if (hsh % 10 == 0) 1.12f else 0.84f),
                        topLeft = Offset(tx, ty),
                        size = Size(4.5f, 4.5f)
                    )
                }
                tx += 19f
            }
            ty += 19f
        }
    } else {
        // Crop plot: rows of small 3-shade plant clusters + row dashes.
        val tuftPalette = mapOf(
            'a' to shadeOf(base, 0.70f),
            'b' to shadeOf(base, 1.16f)
        )
        var ty = y0 + 8f
        var row = 0
        while (ty < y1 - 14f) {
            var tx = x0 + 8f + (row % 2) * 13f
            while (tx < x1 - 14f) {
                val hx = (tx / 10f).toInt()
                val hy = (ty / 10f).toInt()
                if (kotlin.math.abs(pixelHash(hx, hy, seedX - seedY) % 5) >= 2) {
                    drawPixelPattern(PIXEL_TUFT, tuftPalette, tx, ty, 3f)
                }
                tx += 36f
            }
            ty += 32f
            row++
        }
    }
}

/** Watchtower as a pixel sprite (34x46 grid at 2.5px per pixel). */
fun DrawScope.drawWatchtower(cx: Float, baseY: Float) {
    drawShadow(cx, baseY + 4f, 42f, 7f)
    val px = 2.5f
    drawPixelSprite(PIXEL_TOWER, TOWER_PALETTE, cx - 17f * px, baseY - 46f * px, px)
}

/** Tree as a pixel sprite (20x24 grid at 3px per pixel). */
fun DrawScope.drawTree(o: Obstacle) {
    val bottom = o.y + o.r * 0.62f
    drawShadow(o.x, bottom - 2f, o.r * 0.95f, o.r * 0.30f)
    val px = 3f
    drawPixelSprite(PIXEL_TREE, TREE_PALETTE, o.x - 10f * px, bottom - 24f * px, px)
}

/** Rock as a pixel sprite (20x12 grid at 3px per pixel). */
fun DrawScope.drawRock(o: Obstacle) {
    val bottom = o.y + o.r * 0.72f
    drawShadow(o.x, bottom - 1f, o.r * 1.0f, o.r * 0.30f)
    val px = 3f
    drawPixelSprite(PIXEL_ROCK, ROCK_PALETTE, o.x - 10f * px, bottom - 12f * px, px)
}

/** Crate as a pixel sprite (18x18 grid at 3px per pixel); destroyed = faded. */
fun DrawScope.drawCrate(c: CrateEnt) {
    val alpha = if (c.destroyed) 0.45f else 1f
    drawShadow(c.x + 3f, c.y + 24f, 27f, 7f)
    val px = 3f
    drawPixelSprite(PIXEL_CRATE, CRATE_PALETTE, c.x - 9f * px, c.y - 9f * px, px, alpha)
    if (!c.destroyed && c.hp < c.maxHp) {
        drawHealthBar(c.x, c.y - 42f, c.hp / c.maxHp)
    }
}

/**
 * Soldier as a pixel sprite (16x20 grid at 4px per pixel = 64x80 on
 * screen), feet at (x, groundY). The facing is the dominant axis of
 * [facing] — the vector toward the same target auto-fire uses — and the
 * walk frame flips with [stepPhase] while [moving], exactly the moving
 * conditions the v0.2.0 leg animation used. Colours come from the
 * role/faction palette swap, not from [bodyColor] / [pantsColor] (kept
 * in the signature so call sites are unchanged).
 */
fun DrawScope.drawStandingFigure(
    x: Float,
    groundY: Float,
    bodyColor: Color,
    pantsColor: Color,
    facing: Offset,
    stepPhase: Float,
    moving: Boolean,
    enemy: Boolean,
    faction: Faction?
) {
    val pixelFacing = if (kotlin.math.abs(facing.y) >= kotlin.math.abs(facing.x)) {
        if (facing.y >= 0f) PixelFacing.DOWN else PixelFacing.UP
    } else {
        if (facing.x >= 0f) PixelFacing.RIGHT else PixelFacing.LEFT
    }
    val frame = if (moving) {
        (kotlin.math.floor(stepPhase / Math.PI).toInt() and 1)
    } else 0
    drawShadow(x, groundY + 4f, 21f, 5f)
    val px = 4f
    drawPixelSprite(
        soldierSprite(pixelFacing, frame),
        soldierPalette(enemy, faction),
        x - 8f * px,
        groundY - 20f * px,
        px
    )
}

/** Downed member: lying-flat pixel sprite (20x10 grid at 4px), grey kit. */
fun DrawScope.drawDownFigure(x: Float, y: Float) {
    drawShadow(x, y + 6f, 24f, 5f)
    val px = 4f
    drawPixelSprite(SOLDIER_DOWNED, DOWNED_PALETTE, x - 10f * px, y - 4f * px, px)
}

/** Tracer as a chain of small squares, from chest height to chest height. */
fun DrawScope.drawPixelTracer(
    x1: Float, y1: Float, x2: Float, y2: Float,
    color: Color, alpha: Float
) {
    val fromX = x1
    val fromY = y1 - 46f
    val toX = x2
    val toY = y2 - 46f
    val d = distBetween(fromX, fromY, toX, toY)
    if (d < 1f) return
    val steps = (d / 16f).toInt().coerceIn(1, 24)
    val c = color.copy(alpha = alpha)
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        drawRect(
            color = c,
            topLeft = Offset(fromX + (toX - fromX) * t - 2.5f, fromY + (toY - fromY) * t - 2.5f),
            size = Size(5f, 5f)
        )
    }
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
fun DrawScope.drawGame(world: GameWorld, faction: Faction, joyVec: Offset, holdOn: Boolean) {
    val w = world.w
    val h = world.h
    if (w <= 0f || h <= 0f) return

    // ---- Ground layer: fields, road, canal, fence (no depth sorting). ----
    val palette = listOf(
        Color(0xFF7CB342), Color(0xFF9CCC65), Color(0xFFC0CA33),
        Color(0xFF8BC34A), Color(0xFFAED581), Color(0xFF689F38)
    )
    // Dirt between the plots, with sparse hashed pixel speckles.
    drawRect(color = Color(0xFF9C7B4F), size = Size(w, h))
    var dirtY = 9f
    while (dirtY < h - 6f) {
        var dirtX = 9f
        while (dirtX < w - 6f) {
            val dh = pixelHash((dirtX / 9f).toInt(), (dirtY / 9f).toInt(), 99)
            if (dh % 8 == 0) {
                drawRect(
                    color = if (dh % 16 == 0) Color(0xFF8A6A42) else Color(0xFFAC8A5C),
                    topLeft = Offset(dirtX, dirtY),
                    size = Size(5f, 5f)
                )
            }
            dirtX += 29f
        }
        dirtY += 29f
    }
    val cols = 4
    val rows = 6
    val cellW = w / cols
    val cellH = h / rows
    for (i in 0 until cols) {
        for (j in 0 until rows) {
            val color = palette[(i * 3 + j * 5 + i * j) % palette.size]
            drawFieldPlot(
                i * cellW + 4f, j * cellH + 4f,
                (i + 1) * cellW - 4f, (j + 1) * cellH - 4f,
                color
            )
        }
    }

    // Dirt road: slanted band with a darker lower edge.
    val roadY = h * 0.545f
    fillPolygon(
        listOf(
            Offset(0f, roadY + 34f), Offset(w - 18f, roadY + 34f),
            Offset(w - 18f, roadY + 40f), Offset(0f, roadY + 40f)
        ),
        Color(0xFF7A5C3E)
    )
    fillPolygon(
        listOf(
            Offset(18f, roadY), Offset(w, roadY),
            Offset(w - 18f, roadY + 34f), Offset(0f, roadY + 34f)
        ),
        Color(0xFFB08968)
    )
    // Pixel texture on the road: hashed speckles + two dashed track lines.
    var roadX = 10f
    while (roadX < w - 8f) {
        val rh = pixelHash((roadX / 8f).toInt(), (roadY / 8f).toInt(), 41)
        if (rh % 3 == 0) {
            drawRect(
                color = Color(0xFF96714F),
                topLeft = Offset(roadX, roadY + 6f + kotlin.math.abs(rh % 22)),
                size = Size(5f, 5f)
            )
        }
        roadX += 19f
    }
    for (trackY in listOf(roadY + 10f, roadY + 25f)) {
        var dashX = 6f
        while (dashX < w - 14f) {
            drawRect(
                color = Color(0xFF96714F),
                topLeft = Offset(dashX, trackY),
                size = Size(9f, 3f)
            )
            dashX += 24f
        }
    }

    // Canal along the right side: bank bands give it depth, the water
    // strip sits inside them, slightly diagonal, with a highlight line.
    val cx0 = w * 0.865f
    val cx1 = w * 0.94f
    val cShift = 14f
    fillPolygon(
        listOf(
            Offset(cx0 - 7f + cShift, 0f), Offset(cx1 + 7f + cShift, 0f),
            Offset(cx1 + 7f, h), Offset(cx0 - 7f, h)
        ),
        Color(0xFF6D7C4F)
    )
    fillPolygon(
        listOf(
            Offset(cx0 + cShift, 0f), Offset(cx1 + cShift, 0f),
            Offset(cx1, h), Offset(cx0, h)
        ),
        Color(0xFF42A5F5)
    )
    // Pixel detail on the canal: blocky dark bank pixels down both edges,
    // plus hashed white/light-blue sparkle crosses on the water.
    var bankY = 4f
    while (bankY < h - 4f) {
        val t = bankY / h
        val lx = cx0 - 5f + cShift * (1f - t)
        val rx = cx1 + 1f + cShift * (1f - t)
        drawRect(color = Color(0xFF55613C), topLeft = Offset(lx, bankY), size = Size(5f, 9f))
        drawRect(color = Color(0xFF55613C), topLeft = Offset(rx, bankY), size = Size(5f, 9f))
        bankY += 24f
    }
    var sparkY = 26f
    while (sparkY < h - 10f) {
        val t = sparkY / h
        val midX = (cx0 + cx1) / 2f + cShift * (1f - t)
        val sh = pixelHash(7, (sparkY / 10f).toInt(), 1234)
        val sx = midX + (sh % 18)
        val sc = if (sh % 3 == 0) Color.White else Color(0xFFBBDEFB)
        val ss = 3.5f
        drawRect(color = sc, topLeft = Offset(sx - ss / 2f, sparkY - ss / 2f), size = Size(ss, ss))
        drawRect(color = sc, topLeft = Offset(sx - ss * 1.5f, sparkY - ss / 2f), size = Size(ss, ss))
        drawRect(color = sc, topLeft = Offset(sx + ss / 2f, sparkY - ss / 2f), size = Size(ss, ss))
        drawRect(color = sc, topLeft = Offset(sx - ss / 2f, sparkY - ss * 1.5f), size = Size(ss, ss))
        drawRect(color = sc, topLeft = Offset(sx - ss / 2f, sparkY + ss / 2f), size = Size(ss, ss))
        if (sh % 2 == 0) {
            drawRect(
                color = Color(0xFF90CAF9),
                topLeft = Offset(sx - 12f, sparkY + 12f),
                size = Size(11f, 3f)
            )
        }
        sparkY += 92f
    }

    // Border fence in blocky pixels: dashed wire rows + posts built from
    // stacked pixel rects (same slanted geometry as v0.2.0).
    val fenceBase = h * 0.16f + 9f
    for (rail in 0..1) {
        val yOff = if (rail == 0) -22f else -10f
        var wireX = 2f
        while (wireX < w - 9f) {
            val wy = fenceBase + yOff + (wireX / w) * 8f
            drawRect(
                color = Color(0xFF37474F),
                topLeft = Offset(wireX, wy),
                size = Size(9f, 3f)
            )
            wireX += 15f
        }
    }
    var postX = 10f
    while (postX < w) {
        val topY = fenceBase - 22f + (postX / w) * 8f
        for (seg in 0..2) {
            drawRect(
                color = if (seg == 0) Color(0xFF455A64) else Color(0xFF263238),
                topLeft = Offset(postX, topY + seg * 9f),
                size = Size(6f, 8f)
            )
        }
        postX += 46f
    }

    // ---- Depth-sorted layer: towers, cover, crates, soldiers. ----
    // Sorted by base (feet) Y ascending: lower on screen draws later,
    // so it overlaps whatever stands higher up.
    val drawables = mutableListOf<Pair<Float, DrawScope.() -> Unit>>()
    for (tx in listOf(w * 0.22f, w * 0.68f)) {
        drawables.add(fenceBase to { drawWatchtower(tx, fenceBase) })
    }
    for (o in world.obstacles) {
        if (o.tree) {
            drawables.add(o.y + o.r * 0.62f to { drawTree(o) })
        } else {
            drawables.add(o.y + o.r * 0.72f to { drawRock(o) })
        }
    }
    for (c in world.crates) {
        drawables.add(c.y + 27f to { drawCrate(c) })
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
                e.x, e.y, Color(0xFF2B2B2B), Color(0xFF111111),
                eFacing, world.elapsed * 7f + e.phase, eMoving,
                enemy = true, faction = null
            )
            if (e.hp < e.maxHp) {
                drawHealthBar(e.x, e.y - 96f, e.hp / e.maxHp, 34f)
            }
        })
    }
    val uniform = uniformColor(faction)
    val squadMoving = !holdOn && joyVec.getDistance() > 3f
    world.members.forEachIndexed { idx, m ->
        if (m.down) {
            drawables.add(m.y + 6f to { drawDownFigure(m.x, m.y) })
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
                    m.x, m.y, uniform, shadeOf(uniform, 0.55f),
                    mFacing, world.elapsed * 11f + idx * 1.3f, squadMoving,
                    enemy = false, faction = faction
                )
                drawHealthBar(m.x, m.y - 96f, m.hp / m.maxHp)
            })
        }
    }
    drawables.sortBy { it.first }
    for (d in drawables) d.second(this)

    // ---- Effects on top, in pixel idiom: square-chain tracers at chest
    // height, plus-shaped hit bursts, blocky blast ring. ----
    for (fx in world.effects) {
        val a = (fx.ttl / fx.maxTtl).coerceIn(0f, 1f)
        when (fx.kind) {
            FX_TRACER -> drawPixelTracer(
                fx.x1, fx.y1, fx.x2, fx.y2, Color(0xFFFFEB3B), a
            )
            FX_ENEMY_TRACER -> drawPixelTracer(
                fx.x1, fx.y1, fx.x2, fx.y2, Color(0xFFFF7043), a
            )
            FX_HIT -> drawPixelBurst(fx.x1, fx.y1 - 46f, Color(0xFFFFF59D), a)
            FX_BLAST -> {
                val progress = 1f - a
                val radius = 20f + progress * 95f
                drawPixelBlast(fx.x1, fx.y1, radius, a)
            }
        }
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
