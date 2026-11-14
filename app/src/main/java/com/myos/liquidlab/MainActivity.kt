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
    private lateinit var diagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
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
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        gl.onPause()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0] / SensorManager.GRAVITY_EARTH
        val ay = event.values[1] / SensorManager.GRAVITY_EARTH
        val az = event.values[2] / SensorManager.GRAVITY_EARTH
        renderer.setGravity((-ax).coerceIn(-1.35f, 1.35f), ay.coerceIn(-1.35f, 1.35f), az.coerceIn(-1.35f, 1.35f))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private class LiquidRenderer(private val onDiagnostics: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = 1f
    @Volatile private var gravityZ = 0f
    @Volatile private var shake = 0f
    private var previousX = 0f
    private var previousY = 1f
    private var previousZ = 0f
    private var filteredX = 0f
    private var filteredY = 1f
    private var filteredZ = 0f
    private var fluidX = 0f
    private var fluidZ = 0f
    private var fluidVelocityX = 0f
    private var fluidVelocityZ = 0f
    private val fluidSimulation = FluidSurfaceSimulation()
    private var fluidHeightTexture = 0
    private var fluidHeightHandle = 0
    private var flowHandle = 0
    private var program = 0
    private var timeHandle = 0
    private var gravityHandle = 0
    private var resolutionHandle = 0
    private var shakeHandle = 0
    private var surfaceOffsetHandle = 0
    private val volumeSolver = SurfaceVolumeSolver()
    private var lastGoodSurfaceOffset = 0f
    private var frameCounter = 0L
    private var lastDiagnosticsNanos = 0L
    private var startNanos = 0L
    private var lastNanos = 0L
    private lateinit var vertexBuffer: FloatBuffer

    fun setGravity(x: Float, y: Float, z: Float) {
        // Low-pass sensor noise while retaining quick movement for splash impulses.
        val dx = x - previousX
        val dy = y - previousY
        val dz = z - previousZ
        val motion = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (motion > 0.035f) {
            shake = min(1.6f, shake + motion * 0.92f)
            fluidSimulation.addImpulse((x - previousX) * 0.82f, (z - previousZ) * 0.82f)
        }
        previousX = x
        previousY = y
        previousZ = z
        filteredX += (x - filteredX) * 0.22f
        filteredY += (y - filteredY) * 0.22f
        filteredZ += (z - filteredZ) * 0.22f
        gravityX = filteredX
        gravityY = filteredY
        gravityZ = filteredZ
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
            uniform vec3 uGravity;
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
            float causticPattern(vec3 p) {
                vec2 q = p.xz * vec2(8.5, 10.5);
                q += vec2(sin(p.z * 5.0 + uTime * 0.55),
                          cos(p.x * 4.0 - uTime * 0.48)) * 0.24;
                float a = sin(q.x * 1.65 + sin(q.y * 1.25 + uTime * 0.82));
                float b = sin(q.y * 1.85 - sin(q.x * 1.35 - uTime * 0.68));
                return pow(clamp(1.0 - abs(a * b), 0.0, 1.0), 8.0);
            }
            float sdRoundBox(vec3 p, vec3 b, float r) {
                vec3 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
            }
            vec3 surfaceNormal(vec3 p, vec3 g) {
                vec3 n = normalize(vec3(-g.x, max(0.16, abs(g.y)), -g.z));
                float w = 0.010 * sin(p.x * 13.0 + uTime * 1.7)
                        + 0.006 * sin(p.z * 19.0 - uTime * 1.25)
                        + uShake * 0.012 * sin(p.x * 23.0 + p.z * 17.0 - uTime * 7.0);
                return normalize(n + vec3(-0.13 * cos(p.x * 13.0 + uTime * 1.7),
                                           0.0,
                                           -0.11 * cos(p.z * 19.0 - uTime * 1.25)));
            }
            float waterDistance(vec3 p, vec3 g) {
                vec3 n = normalize(vec3(-g.x, max(0.16, abs(g.y)), -g.z));
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
            vec3 waterNormal(vec3 p, vec3 g) {
                float e = 0.0025;
                return normalize(vec3(
                    waterDistance(p + vec3(e,0,0),g) - waterDistance(p - vec3(e,0,0),g),
                    waterDistance(p + vec3(0,e,0),g) - waterDistance(p - vec3(0,e,0),g),
                    waterDistance(p + vec3(0,0,e),g) - waterDistance(p - vec3(0,0,e),g)
                ));
            }
            bool traceWater(vec3 ro, vec3 rd, vec3 g, out vec3 hit, out float travel) {
                travel = 0.0;
                for (int i = 0; i < 88; i++) {
                    vec3 p = ro + rd * travel;
                    float d = waterDistance(p, g);
                    if (d < 0.0018) { hit = p; return true; }
                    travel += max(d * 0.72, 0.0015);
                    if (travel > 7.0) break;
                }
                hit = vec3(0.0);
                return false;
            }
            void main() {
                vec2 uv = vUv;
                float aspect = uResolution.x / uResolution.y;
                vec2 screen = (uv - 0.5) * vec2(aspect, 1.0);
                vec3 ro = vec3(0.0, 0.0, 3.35);
                vec3 rd = normalize(vec3(screen * 1.48, -1.85));
                vec3 g = normalize(uGravity);
                vec3 background = vec3(0.009, 0.025, 0.038);
                // Keep the background uniform: the old radial halo looked like a
                // persistent glowing blob trapped in the center of the water.

                // Ray-marched intersection of a real 3D liquid volume with its container.
                vec3 hit;
                float travel;
                vec3 color = background;
                if (traceWater(ro, rd, g, hit, travel)) {
                    vec3 n = waterNormal(hit, g);
                    vec3 viewDir = normalize(ro - hit);
                    vec3 lightDir = normalize(vec3(-0.45, 0.78, 0.62));
                    float fresnel = pow(1.0 - max(dot(n, viewDir), 0.0), 4.0);
                    float diffuse = max(dot(n, lightDir), 0.0);
                    float depth = clamp((hit.y + TANK.y) / (2.0 * TANK.y), 0.0, 1.0);
                    // Saturated, layered colour gives the water a playful 3D glass-candy look.
                    vec3 deepBlue = vec3(0.006, 0.105, 0.205);
                    vec3 oceanTeal = vec3(0.012, 0.36, 0.49);
                    vec3 sunlitAqua = vec3(0.075, 0.66, 0.70);
                    vec3 body = mix(deepBlue, oceanTeal, smoothstep(0.04, 0.88, depth));
                    body = mix(body, sunlitAqua, smoothstep(0.70, 1.0, depth) * 0.46);
                    float caustics = causticPattern(hit);
                    float softGlow = 0.5 + 0.5 * sin(hit.x * 5.5 + hit.z * 4.0 - uTime * 0.7);
                    body += vec3(0.035, 0.20, 0.22) * caustics * (0.32 + 0.24 * softGlow);
                    float spec = pow(max(dot(reflect(-lightDir,n),viewDir),0.0), 46.0);
                    float sharpSpec = pow(max(dot(reflect(-lightDir,n),viewDir),0.0), 110.0);
                    float surfaceFactor = smoothstep(0.66,0.95,n.y);
                    vec3 surfaceColor = vec3(0.018,0.43,0.51) + vec3(0.12,0.35,0.34)*diffuse;
                    float flowingRibbon = pow(max(0.0, sin(hit.x * 17.0 + hit.z * 8.0 - uTime * 1.35)), 13.0);
                    surfaceColor += vec3(0.08,0.28,0.28) * flowingRibbon;
                    surfaceColor += vec3(0.62,0.91,0.92) * (spec * 0.55 + sharpSpec * 0.85 + fresnel * 0.30);
                    color = mix(body, surfaceColor, surfaceFactor);
                    color += vec3(0.18,0.72,0.80) * fresnel * 0.22;
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
                fragColor = vec4(color,1.0);
            }
        """.trimIndent()

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(quad).position(0)
        program = linkProgram(vertex, fragment)
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        gravityHandle = GLES30.glGetUniformLocation(program, "uGravity")
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
        val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.033f)
        lastNanos = now
        shake = max(0f, shake - dt * 1.25f)
        val targetX = (gravityX * 0.82f).coerceIn(-1.15f, 1.15f)
        val targetZ = (gravityZ * 0.82f).coerceIn(-1.15f, 1.15f)
        fluidVelocityX += ((targetX - fluidX) * 5.2f - fluidVelocityX * 2.05f) * dt
        fluidVelocityZ += ((targetZ - fluidZ) * 5.2f - fluidVelocityZ * 2.05f) * dt
        fluidX = (fluidX + fluidVelocityX * dt).coerceIn(-1.25f, 1.25f)
        fluidZ = (fluidZ + fluidVelocityZ * dt).coerceIn(-1.25f, 1.25f)
        fluidSimulation.step(dt)
        val time = (now - startNanos) / 1_000_000_000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(timeHandle, time)
        GLES30.glUniform3f(gravityHandle, fluidX, gravityY, fluidZ)
        GLES30.glUniform1f(shakeHandle, shake)
        val surfaceNormalX = -fluidX
        val surfaceNormalY = max(0.16f, abs(gravityY))
        val surfaceNormalZ = -fluidZ
        val rawSurfaceOffset = volumeSolver.offsetForHalfFill(surfaceNormalX, surfaceNormalY, surfaceNormalZ)
        if (rawSurfaceOffset.isFinite()) {
            lastGoodSurfaceOffset = rawSurfaceOffset.coerceIn(-1.5f, 1.5f)
        }
        GLES30.glUniform1f(surfaceOffsetHandle, lastGoodSurfaceOffset)
        GLES30.glUniform2f(flowHandle, fluidVelocityX, fluidVelocityZ)
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
                "frames: $frameCounter   gravity: %.2f, %.2f, %.2f   offset: %.4f".format(
                    gravityX, gravityY, gravityZ, lastGoodSurfaceOffset
                )
            )
        }
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
