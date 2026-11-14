package com.myos.liquidlab

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), SensorEventListener {
    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: LiquidRenderer
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null
    private var linearAccelerometer: Sensor? = null
    private lateinit var diagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        linearAccelerometer = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        diagnostics = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(10, 7, 10, 7)
            setBackgroundColor(0x99000000.toInt())
            text = "Starting diagnostics…"
        }
        renderer = LiquidRenderer { line ->
            diagnostics.post { diagnostics.text = line }
        }
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }
        val root = FrameLayout(this)
        root.addView(gl, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(diagnostics, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        gl.onPause()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0] / SensorManager.GRAVITY_EARTH
                val ay = event.values[1] / SensorManager.GRAVITY_EARTH
                val az = event.values[2] / SensorManager.GRAVITY_EARTH
                renderer.setGravity(-ax, ay, az, event.timestamp, ax, ay, az)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                renderer.setShakeAcceleration(
                    event.values[0], event.values[1], event.values[2], event.timestamp
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private class LiquidRenderer(private val onDiagnostics: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var targetGravityX = 0f
    @Volatile private var targetGravityY = 1f
    @Volatile private var targetGravityZ = 0f
    @Volatile private var gravityJitterG = 0f
    @Volatile private var rawSensorX = 0f
    @Volatile private var rawSensorY = 0f
    @Volatile private var rawSensorZ = 0f
    @Volatile private var shake = 0f
    @Volatile private var pendingShakeX = 0f
    @Volatile private var pendingShakeZ = 0f
    private var filteredGravityX = 0f
    private var filteredGravityY = 1f
    private var filteredGravityZ = 0f
    private var acceptedGravityX = 0f
    private var acceptedGravityY = 1f
    private var acceptedGravityZ = 0f
    private var gravityFilterInitialized = false
    private var lastSensorTimestamp = 0L
    private val jitterTimes = LongArray(128)
    private val jitterX = FloatArray(128)
    private val jitterY = FloatArray(128)
    private val jitterZ = FloatArray(128)
    private var jitterIndex = 0
    private var jitterCount = 0
    private var springGravityX = 0f
    private var springGravityY = 1f
    private var springGravityZ = 0f
    private var springVelocityX = 0f
    private var springVelocityY = 0f
    private var springVelocityZ = 0f
    private val fluidSimulation = FluidSurfaceSimulation()
    private var fluidHeightTexture = 0
    private var fluidHeightHandle = 0
    private var flowHandle = 0
    private var program = 0
    private var timeHandle = 0
    private var upHandle = 0
    private var resolutionHandle = 0
    private var shakeHandle = 0
    private var surfaceOffsetHandle = 0
    private val volumeSolver = SurfaceVolumeSolver()
    private var lastGoodSurfaceOffset = 0f
    private val upSignX = -1f
    private val upSignZ = 1f
    private val computedUp = FloatArray(3)
    private var frameCounter = 0L
    private var lastDiagnosticsNanos = 0L
    private var startNanos = 0L
    private var lastNanos = 0L
    private lateinit var vertexBuffer: FloatBuffer

    @Synchronized
    fun setGravity(
        x: Float, y: Float, z: Float, timestampNanos: Long,
        rawX: Float, rawY: Float, rawZ: Float
    ) {
        rawSensorX = rawX
        rawSensorY = rawY
        rawSensorZ = rawZ
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude < 0.35f) return
        val rx = x / magnitude
        val ry = y / magnitude
        val rz = z / magnitude

        // Keep a two-second rolling estimate of stationary sensor spread.
        jitterTimes[jitterIndex] = timestampNanos
        jitterX[jitterIndex] = rx
        jitterY[jitterIndex] = ry
        jitterZ[jitterIndex] = rz
        jitterIndex = (jitterIndex + 1) % jitterTimes.size
        jitterCount = min(jitterCount + 1, jitterTimes.size)
        var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
        for (i in 0 until jitterCount) {
            if (timestampNanos - jitterTimes[i] <= 2_000_000_000L) {
                sx += jitterX[i]; sy += jitterY[i]; sz += jitterZ[i]; n++
            }
        }
        if (n > 1) {
            val mx = sx / n; val my = sy / n; val mz = sz / n
            var variance = 0f
            for (i in 0 until jitterCount) {
                if (timestampNanos - jitterTimes[i] <= 2_000_000_000L) {
                    val dx = jitterX[i] - mx; val dy = jitterY[i] - my; val dz = jitterZ[i] - mz
                    variance += dx * dx + dy * dy + dz * dz
                }
            }
            gravityJitterG = kotlin.math.sqrt(variance / n)
        }

        if (!gravityFilterInitialized) {
            filteredGravityX = rx; filteredGravityY = ry; filteredGravityZ = rz
            acceptedGravityX = rx; acceptedGravityY = ry; acceptedGravityZ = rz
            gravityFilterInitialized = true
            lastSensorTimestamp = timestampNanos
        } else {
            val sensorDt = ((timestampNanos - lastSensorTimestamp) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            lastSensorTimestamp = timestampNanos
            val alpha = 1f - kotlin.math.exp(-sensorDt / 0.20f)
            filteredGravityX += (rx - filteredGravityX) * alpha
            filteredGravityY += (ry - filteredGravityY) * alpha
            filteredGravityZ += (rz - filteredGravityZ) * alpha
            val filteredLength = kotlin.math.sqrt(
                filteredGravityX * filteredGravityX +
                filteredGravityY * filteredGravityY +
                filteredGravityZ * filteredGravityZ
            ).coerceAtLeast(0.0001f)
            filteredGravityX /= filteredLength
            filteredGravityY /= filteredLength
            filteredGravityZ /= filteredLength

            // Change-based soft dead zone: retain large tilts, suppress tiny sensor chatter.
            val dx = filteredGravityX - acceptedGravityX
            val dy = filteredGravityY - acceptedGravityY
            val dz = filteredGravityZ - acceptedGravityZ
            val change = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val deadZone = max(0.015f, (gravityJitterG * 1.5f).coerceAtMost(0.05f))
            if (change > deadZone) {
                val softFraction = ((change - deadZone) / change).coerceIn(0f, 1f)
                acceptedGravityX += dx * softFraction
                acceptedGravityY += dy * softFraction
                acceptedGravityZ += dz * softFraction
            }
        }
        targetGravityX = acceptedGravityX
        targetGravityY = acceptedGravityY
        targetGravityZ = acceptedGravityZ
    }

    fun setShakeAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long) {
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        val threshold = 1.6f // m/s²; linear-acceleration sensor has gravity removed.
        if (!magnitude.isFinite() || magnitude <= threshold) return
        val strength = ((magnitude - threshold) / SensorManager.GRAVITY_EARTH).coerceIn(0f, 1.4f)
        pendingShakeX = (pendingShakeX - x / SensorManager.GRAVITY_EARTH * 0.16f).coerceIn(-0.35f, 0.35f)
        pendingShakeZ = (pendingShakeZ + z / SensorManager.GRAVITY_EARTH * 0.16f).coerceIn(-0.35f, 0.35f)
        fluidSimulation.addImpulse(-x / SensorManager.GRAVITY_EARTH * 0.38f,
            z / SensorManager.GRAVITY_EARTH * 0.38f)
        shake = min(1.6f, shake + strength * 0.65f)
    }

    override fun onSurfaceCreated(glUnused: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES30.glClearColor(0.008f, 0.018f, 0.027f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val vertex = """
            #version 300 es
            layout(location=0) in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()
        val fragment = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;
            uniform vec2 uResolution;
            uniform vec3 uUp;
            uniform float uTime;
            uniform float uShake;
            uniform float uSurfaceOffset;
            uniform vec2 uFlow;
            uniform sampler2D uFluidHeight;

            const vec3 TANK = vec3(0.405, 0.885, 0.305);
            float hash(vec3 p) {
                p = fract(p * 0.3183099 + vec3(0.1));
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }
            float noise(vec3 x) {
                vec3 i = floor(x), f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),
                               mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
                           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                               mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
            }
            float causticPattern(vec2 p) {
                vec2 q = p * vec2(8.5, 10.5);
                q += vec2(sin(p.y * 5.0 + uTime * 0.55),
                          cos(p.x * 4.0 - uTime * 0.48)) * 0.24;
                float a = sin(q.x * 1.65 + sin(q.y * 1.25 + uTime * 0.82));
                float b = sin(q.y * 1.85 - sin(q.x * 1.35 - uTime * 0.68));
                return pow(clamp(1.0 - abs(a * b), 0.0, 1.0), 8.0);
            }
            float triCaustics(vec3 p, vec3 n) {
                vec3 w = pow(abs(n), vec3(4.0));
                w /= max(w.x + w.y + w.z, 0.0001);
                return causticPattern(p.zy) * w.x
                     + causticPattern(p.xz) * w.y
                     + causticPattern(p.xy) * w.z;
            }
            float bubbles(vec2 p, float t) {
                float acc = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    vec2 q = p * (5.0 + fi * 4.0);
                    q.y -= t * (0.30 + 0.14 * fi);
                    vec2 id = floor(q);
                    vec2 f = fract(q) - 0.5;
                    float h = hash(vec3(id, fi));
                    if (h > 0.45) {
                        vec2 off = (vec2(h, fract(h * 7.13)) - 0.5) * 0.4;
                        float r = 0.10 + 0.08 * fract(h * 3.7);
                        float d = length(f - off);
                        float ring = 1.0 - smoothstep(r - 0.025, r, d);
                        float glimmer = 1.0 - smoothstep(0.0, 0.03,
                            length(f - off - vec2(-r * 0.35, r * 0.35)));
                        acc += ring + glimmer * 0.8;
                    }
                }
                return clamp(acc, 0.0, 1.0);
            }
            float sdRoundBox(vec3 p, vec3 b, float r) {
                vec3 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
            }
            vec3 surfaceNormal(vec3 p, vec3 up) {
                vec3 n = normalize(up);
                float w = 0.010 * sin(p.x * 13.0 + uTime * 1.7)
                        + 0.006 * sin(p.z * 19.0 - uTime * 1.25)
                        + uShake * 0.012 * sin(p.x * 23.0 + p.z * 17.0 - uTime * 7.0);
                return normalize(n + vec3(-0.13 * cos(p.x * 13.0 + uTime * 1.7),
                                           0.0,
                                           -0.11 * cos(p.z * 19.0 - uTime * 1.25)));
            }
            float waterDistance(vec3 p, vec3 up) {
                vec3 n = normalize(up);
                float energy = clamp(length(uFlow) * 0.018 + uShake * 0.008, 0.0, 0.032);
                vec2 fluidUv = clamp(vec2(p.x / (2.0 * TANK.x) + 0.5,
                                          p.z / (2.0 * TANK.z) + 0.5), 0.0, 1.0);
                float simulatedHeight = texture(uFluidHeight, fluidUv).r;
                float wave = 0.0025 * sin(p.x * 11.0 + uTime * 1.1)
                           + 0.0018 * sin(p.z * 15.0 - uTime * 0.9)
                           + energy * sin(p.x * 10.0 + p.z * 7.0 - uTime * 3.6);
                float container = sdRoundBox(p, TANK - vec3(0.018), 0.025);
                float freeSurface = dot(p, n) + uSurfaceOffset + wave - simulatedHeight;
                return max(container, freeSurface);
            }
            vec3 waterNormal(vec3 p, vec3 up) {
                float e = 0.0025;
                return normalize(vec3(
                    waterDistance(p + vec3(e,0,0),up) - waterDistance(p - vec3(e,0,0),up),
                    waterDistance(p + vec3(0,e,0),up) - waterDistance(p - vec3(0,e,0),up),
                    waterDistance(p + vec3(0,0,e),up) - waterDistance(p - vec3(0,0,e),up)
                ));
            }
            bool traceWater(vec3 ro, vec3 rd, vec3 up, out vec3 hit, out float travel) {
                travel = 0.0;
                for (int i = 0; i < 88; i++) {
                    vec3 p = ro + rd * travel;
                    float d = waterDistance(p, up);
                    if (d < 0.0018) { hit = p; return true; }
                    travel += max(d * 0.72, 0.0015);
                    if (travel > 7.0) break;
                }
                hit = vec3(0.0);
                return false;
            }
            // Robust slab intersection against the tank's inner bounds.
            // Returns outward-facing normals for the near and far box faces.
            bool rayBox(vec3 ro, vec3 rd, vec3 halfSize,
                        out float tNear, out float tFar,
                        out vec3 nearNormal, out vec3 farNormal) {
                vec3 safeRd = vec3(
                    abs(rd.x) < 0.000001 ? (rd.x < 0.0 ? -0.000001 : 0.000001) : rd.x,
                    abs(rd.y) < 0.000001 ? (rd.y < 0.0 ? -0.000001 : 0.000001) : rd.y,
                    abs(rd.z) < 0.000001 ? (rd.z < 0.0 ? -0.000001 : 0.000001) : rd.z
                );
                vec3 invRd = 1.0 / safeRd;
                vec3 a = (-halfSize - ro) * invRd;
                vec3 b = ( halfSize - ro) * invRd;
                vec3 lo = min(a, b);
                vec3 hi = max(a, b);
                tNear = max(lo.x, max(lo.y, lo.z));
                tFar = min(hi.x, min(hi.y, hi.z));
                if (tFar < max(tNear, 0.0)) return false;

                if (lo.x >= lo.y && lo.x >= lo.z) nearNormal = vec3(-sign(rd.x), 0.0, 0.0);
                else if (lo.y >= lo.z) nearNormal = vec3(0.0, -sign(rd.y), 0.0);
                else nearNormal = vec3(0.0, 0.0, -sign(rd.z));

                if (hi.x <= hi.y && hi.x <= hi.z) farNormal = vec3(sign(rd.x), 0.0, 0.0);
                else if (hi.y <= hi.z) farNormal = vec3(0.0, sign(rd.y), 0.0);
                else farNormal = vec3(0.0, 0.0, sign(rd.z));
                return true;
            }
            void main() {
                vec2 uv = vUv;
                float aspect = uResolution.x / uResolution.y;
                vec2 screen = (uv - 0.5) * vec2(aspect, 1.0);
                vec3 ro = vec3(0.0, 0.0, 3.35);
                vec3 rd = normalize(vec3(screen * 1.48, -1.85));
                vec3 up = normalize(uUp);
                vec3 background = vec3(0.009, 0.025, 0.038);
                // Keep the background uniform: the old radial halo looked like a
                // persistent glowing blob trapped in the center of the water.

                // Universal water-volume intersection.
                // Clip the camera ray to both the tank box and the gravity-aligned
                // half-space below the free surface. This works at every phone angle.
                vec3 color = background;
                vec3 innerHalf = TANK - vec3(0.018);
                float boxNear;
                float boxFar;
                vec3 boxNearNormal;
                vec3 boxFarNormal;
                bool hasBox = rayBox(ro, rd, innerHalf, boxNear, boxFar,
                                     boxNearNormal, boxFarNormal);
                if (hasBox) {
                    float enterT = max(boxNear, 0.0);
                    float exitT = boxFar;
                    vec3 enterNormal = boxNearNormal;
                    vec3 exitNormal = boxFarNormal;
                    bool entersAtSurface = false;
                    bool exitsAtSurface = false;

                    // Water occupies dot(position, up) + offset <= 0.
                    float planeOrigin = dot(ro, up) + uSurfaceOffset;
                    float planeDirection = dot(rd, up);
                    bool validWaterInterval = true;
                    if (abs(planeDirection) < 0.00001) {
                        validWaterInterval = planeOrigin <= 0.0;
                    } else {
                        float surfaceT = -planeOrigin / planeDirection;
                        if (planeDirection > 0.0) {
                            if (surfaceT < exitT) {
                                exitT = surfaceT;
                                exitNormal = up;
                                exitsAtSurface = true;
                            }
                        } else {
                            if (surfaceT > enterT) {
                                enterT = surfaceT;
                                enterNormal = up;
                                entersAtSurface = true;
                            }
                        }
                    }

                    if (validWaterInterval && exitT > enterT + 0.001) {
                        vec3 entryPoint = ro + rd * enterT;
                        vec3 exitPoint = ro + rd * exitT;
                        float waterLength = max(exitT - enterT, 0.0);
                        vec3 viewDir = -rd;

                        // Continue through any air gap after the water boundary to the
                        // far tank wall. This is essential when a side-view ray exits the
                        // water at the free surface before reaching the opposite glass.
                        vec3 farWallPoint = ro + rd * boxFar;
                        vec3 farWallNormal = boxFarNormal;
                        float backCaustics = triCaustics(farWallPoint, farWallNormal);
                        float backBubbles = bubbles(farWallPoint.xy, uTime * 0.72);
                        vec3 transmittedBackground = vec3(0.018, 0.052, 0.071);
                        transmittedBackground += vec3(0.12, 0.42, 0.41) * backCaustics * 0.72;
                        transmittedBackground += vec3(0.52, 0.86, 0.82) * backBubbles * 0.34;
                        float backFacingLight = max(dot(farWallNormal,
                            normalize(vec3(-0.45, 0.78, 0.62))), 0.0);
                        transmittedBackground *= 0.78 + 0.22 * backFacingLight;

                        // Beer-Lambert attenuation: red is absorbed fastest, blue-green
                        // survives farther, while a small in-scattering term gives water body.
                        vec3 absorption = vec3(1.10, 0.48, 0.30);
                        vec3 transmittance = exp(-absorption * waterLength);
                        vec3 inScattering = vec3(0.018, 0.105, 0.115) *
                                            (1.0 - transmittance);
                        vec3 transmitted = transmittedBackground * transmittance + inScattering;

                        // Fresnel is evaluated at whichever boundary the ray enters.
                        // This keeps a restrained reflective sheen without hiding the tank.
                        float facing = clamp(abs(dot(enterNormal, viewDir)), 0.0, 1.0);
                        float fresnel = 0.02 + 0.10 * pow(1.0 - facing, 5.0);
                        vec3 lightDir = normalize(vec3(-0.45, 0.78, 0.62));
                        float surfaceWave = 0.5 + 0.5 * sin(entryPoint.x * 13.0 +
                                                           entryPoint.z * 17.0 - uTime * 1.4);
                        vec3 reflectedTint = vec3(0.12, 0.36, 0.38) +
                                             vec3(0.16, 0.22, 0.18) * surfaceWave;
                        reflectedTint += vec3(0.38, 0.54, 0.48) *
                                         pow(max(dot(reflect(rd, enterNormal), lightDir), 0.0), 24.0) * 0.35;
                        color = mix(transmitted, reflectedTint, fresnel);

                        // Soft light detail is projected onto whichever tank wall is
                        // visible through the water, independent of its orientation.
                        float causticGlow = triCaustics(farWallPoint, farWallNormal);
                        color += vec3(0.035, 0.105, 0.095) * causticGlow *
                                 transmittance * 0.55;

                        // A subtle surface ripple/meniscus cue appears when the ray
                        // enters or exits through the free surface, at any orientation.
                        if (entersAtSurface || exitsAtSurface) {
                            vec3 surfacePoint = entersAtSurface ? entryPoint : exitPoint;
                            float ripple = 0.5 + 0.5 * sin(surfacePoint.x * 24.0 +
                                                          surfacePoint.z * 19.0 - uTime * 2.2);
                            float glint = pow(max(dot(reflect(rd, up), lightDir), 0.0), 28.0);
                            color += vec3(0.055, 0.13, 0.12) * ripple * 0.10;
                            color += vec3(0.30, 0.48, 0.39) * glint * 0.18;
                        }
                    }
                }

                // Glass vessel: rounded silhouette, bright polished edges and front-face reflections.
                vec3 pNear = ro + rd * 3.05;
                float shell = abs(sdRoundBox(pNear, TANK, 0.055));
                float edge = 1.0 - smoothstep(0.002,0.013,shell);
                float rim = 1.0 - smoothstep(0.018,0.045,shell);
                color += vec3(0.27,0.64,0.72) * edge * 0.72;
                color += vec3(0.10,0.27,0.32) * rim * 0.20;
                float leftStrip = exp(-pow((pNear.x + 0.365)*45.0,2.0)) * smoothstep(-0.82,0.72,pNear.y);
                float rightStrip = exp(-pow((pNear.x - 0.37)*54.0,2.0)) * smoothstep(-0.78,0.78,pNear.y);
                color += vec3(0.42,0.72,0.78) * (leftStrip*0.24 + rightStrip*0.12);
                float topGlare = exp(-pow((pNear.y - 0.85)*38.0,2.0)) * smoothstep(-0.34,0.34,pNear.x);
                color += vec3(0.52,0.78,0.82) * topGlare * 0.25;
                float vignette = smoothstep(1.05,0.25,length(screen*vec2(0.85,0.72)));
                color *= 0.72 + 0.28*vignette;
                // Gentle exponential tone mapping keeps highlights bright without clipping.
                color = 1.0 - exp(-max(color, vec3(0.0)) * 1.35);
                fragColor = vec4(color,1.0);
            }
        """.trimIndent()

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(quad).position(0)
        program = linkProgram(vertex, fragment)
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        upHandle = GLES30.glGetUniformLocation(program, "uUp")
        resolutionHandle = GLES30.glGetUniformLocation(program, "uResolution")
        shakeHandle = GLES30.glGetUniformLocation(program, "uShake")
        surfaceOffsetHandle = GLES30.glGetUniformLocation(program, "uSurfaceOffset")
        flowHandle = GLES30.glGetUniformLocation(program, "uFlow")
        fluidHeightHandle = GLES30.glGetUniformLocation(program, "uFluidHeight")
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        fluidHeightTexture = textureIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fluidHeightTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val gridSize = fluidSimulation.resolution()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, gridSize, gridSize, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, null)
        startNanos = System.nanoTime()
        lastNanos = startNanos
    }

    override fun onSurfaceChanged(glUnused: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(glUnused: javax.microedition.khronos.opengles.GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNanos = now
        shake = max(0f, shake - dt * 1.25f)

        // A 0.7 Hz damped spring (zeta 0.30) gives the whole gravity vector
        // physical follow-through. Substeps keep integration stable across frame rates.
        val targetLength = kotlin.math.sqrt(
            targetGravityX * targetGravityX + targetGravityY * targetGravityY +
            targetGravityZ * targetGravityZ
        ).coerceAtLeast(0.0001f)
        val targetX = targetGravityX / targetLength * 0.8f
        val targetY = targetGravityY / targetLength
        val targetZ = targetGravityZ / targetLength * 0.8f
        val omega = 2f * Math.PI.toFloat() * 0.7f
        val dampingRatio = 0.30f
        val steps = max(1, kotlin.math.ceil(dt.toDouble() / (1.0 / 120.0)).toInt())
        val stepDt = if (steps > 0) dt / steps else 0f
        repeat(steps) {
            springVelocityX += (omega * omega * (targetX - springGravityX) -
                2f * dampingRatio * omega * springVelocityX) * stepDt
            springVelocityY += (omega * omega * (targetY - springGravityY) -
                2f * dampingRatio * omega * springVelocityY) * stepDt
            springVelocityZ += (omega * omega * (targetZ - springGravityZ) -
                2f * dampingRatio * omega * springVelocityZ) * stepDt
            springGravityX += springVelocityX * stepDt
            springGravityY += springVelocityY * stepDt
            springGravityZ += springVelocityZ * stepDt
        }
        val springLength = kotlin.math.sqrt(
            springGravityX * springGravityX + springGravityY * springGravityY +
            springGravityZ * springGravityZ
        ).coerceAtLeast(0.0001f)
        springGravityX /= springLength
        springGravityY /= springLength
        springGravityZ /= springLength

        // Shake contributes a short momentum kick, never a change to the sensor target.
        val kickX = pendingShakeX
        val kickZ = pendingShakeZ
        pendingShakeX = 0f
        pendingShakeZ = 0f
        springVelocityX += kickX
        springVelocityZ += kickZ
        fluidSimulation.step(dt)
        val time = (now - startNanos) / 1_000_000_000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        val up = computeUp(springGravityX, springGravityY, springGravityZ)
        GLES30.glUniform1f(timeHandle, time)
        GLES30.glUniform3f(upHandle, up[0], up[1], up[2])
        GLES30.glUniform1f(shakeHandle, shake)
        val rawSurfaceOffset = volumeSolver.offsetForHalfFill(up[0], up[1], up[2])
        if (rawSurfaceOffset.isFinite()) {
            lastGoodSurfaceOffset = rawSurfaceOffset.coerceIn(-1.5f, 1.5f)
        }
        GLES30.glUniform1f(surfaceOffsetHandle, lastGoodSurfaceOffset)
        GLES30.glUniform2f(flowHandle, springVelocityX, springVelocityZ)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fluidHeightTexture)
        GLES30.glUniform1i(fluidHeightHandle, 0)
        val heightData = fluidSimulation.createUploadBuffer()
        fluidSimulation.writeHeightTexture(heightData)
        val gridSize = fluidSimulation.resolution()
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, gridSize, gridSize,
            GLES30.GL_RED, GLES30.GL_FLOAT, heightData)
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        GLES30.glUniform2f(resolutionHandle, viewport[2].toFloat(), viewport[3].toFloat())
        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)

        frameCounter++
        if (now - lastDiagnosticsNanos >= 250_000_000L) {
            lastDiagnosticsNanos = now
            onDiagnostics(
                "frames: $frameCounter   jitter: %.3f g\nraw accel(g): %.2f, %.2f, %.2f\nup: %.2f, %.2f, %.2f  signs: %.0f/%.0f  offset: %.4f".format(
                    gravityJitterG, rawSensorX, rawSensorY, rawSensorZ,
                    up[0], up[1], up[2], upSignX, upSignZ, lastGoodSurfaceOffset
                )
            )
        }
    }

    private fun computeUp(gx: Float, gy: Float, gz: Float): FloatArray {
        // Stage 0 device test confirmed the face-up Z direction is inverted with -1.
        // Keep X unchanged; flip only Z so the solver and shader share the corrected up vector.
        val x = upSignX * gx
        val y = max(0.16f, abs(gy))
        val z = upSignZ * gz
        val length = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001f)
        computedUp[0] = x / length
        computedUp[1] = y / length
        computedUp[2] = z / length
        return computedUp
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) throw IllegalStateException(GLES30.glGetShaderInfoLog(shader))
            return shader
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vs)
        GLES30.glAttachShader(linked, fs)
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(linked))
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return linked
    }
}
