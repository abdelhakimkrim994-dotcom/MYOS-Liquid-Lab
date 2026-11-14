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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), SensorEventListener {
    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: LiquidRenderer
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        renderer = LiquidRenderer()
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }
        setContentView(gl)
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
        renderer.setGravity((-ax).coerceIn(-1.35f, 1.35f), ay.coerceIn(-1.35f, 1.35f), az)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private class LiquidRenderer : GLSurfaceView.Renderer {
    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = 1f
    @Volatile private var shake = 0f
    @Volatile private var lastMagnitude = 1f
    private var program = 0
    private var timeHandle = 0
    private var gravityHandle = 0
    private var resolutionHandle = 0
    private var shakeHandle = 0
    private var startNanos = 0L
    private var lastNanos = 0L

    fun setGravity(x: Float, y: Float, z: Float) {
        gravityX = x
        gravityY = y
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastMagnitude)
        if (delta > 0.30f) shake = min(1.6f, shake + delta * 1.5f)
        lastMagnitude = magnitude
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
            uniform vec2 uGravity;
            uniform float uTime;
            uniform float uShake;

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
            float sdRoundBox(vec3 p, vec3 b, float r) {
                vec3 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
            }
            vec3 surfaceNormal(vec3 p, vec3 g) {
                vec3 n = normalize(vec3(-g.x, 1.0, -g.y));
                float w = 0.010 * sin(p.x * 13.0 + uTime * 1.7)
                        + 0.006 * sin(p.z * 19.0 - uTime * 1.25)
                        + uShake * 0.012 * sin(p.x * 23.0 + p.z * 17.0 - uTime * 7.0);
                return normalize(n + vec3(-0.13 * cos(p.x * 13.0 + uTime * 1.7),
                                           0.0,
                                           -0.11 * cos(p.z * 19.0 - uTime * 1.25)));
            }
            float waterDistance(vec3 p, vec3 g) {
                vec3 n = normalize(vec3(-g.x, 1.0, -g.y));
                float wave = 0.010 * sin(p.x * 13.0 + uTime * 1.7)
                           + 0.006 * sin(p.z * 19.0 - uTime * 1.25)
                           + uShake * 0.012 * sin(p.x * 23.0 + p.z * 17.0 - uTime * 7.0);
                float container = sdRoundBox(p, TANK - vec3(0.018), 0.025);
                float freeSurface = dot(p, n) + 0.055 + wave;
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
                for (int i = 0; i < 110; i++) {
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
                vec3 g = normalize(vec3(-uGravity.x * 0.78, max(0.22, uGravity.y), 0.0));
                vec3 background = vec3(0.009, 0.025, 0.038);
                float halo = exp(-length(screen * vec2(1.1,0.85)) * 3.0);
                background += vec3(0.015,0.075,0.092) * halo;

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
                    float thickness = clamp((hit.y + TANK.y) / (2.0 * TANK.y), 0.0, 1.0);
                    float caustic = pow(max(0.0, 1.0 - abs(noise(hit * vec3(27.0,34.0,31.0) + vec3(uTime*0.15,-uTime*0.11,uTime*0.13)) - 0.5) * 2.0), 5.0);
                    vec3 deep = vec3(0.004, 0.075, 0.105);
                    vec3 body = mix(vec3(0.012,0.27,0.34), deep, 0.35 + thickness * 0.48);
                    body += vec3(0.08,0.32,0.30) * caustic * 0.32;
                    float spec = pow(max(dot(reflect(-lightDir,n),viewDir),0.0), 72.0);
                    float surfaceFactor = smoothstep(0.72,0.96,n.y);
                    vec3 surfaceColor = vec3(0.025,0.38,0.43) + vec3(0.30,0.48,0.45)*diffuse;
                    surfaceColor += vec3(0.72,0.92,0.92)*(spec*1.25 + fresnel*0.42);
                    color = mix(body, surfaceColor, surfaceFactor);
                    color += vec3(0.35,0.76,0.78) * fresnel * 0.18;
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

        program = linkProgram(vertex, fragment)
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        gravityHandle = GLES30.glGetUniformLocation(program, "uGravity")
        resolutionHandle = GLES30.glGetUniformLocation(program, "uResolution")
        shakeHandle = GLES30.glGetUniformLocation(program, "uShake")
        startNanos = System.nanoTime()
        lastNanos = startNanos
    }

    override fun onSurfaceChanged(glUnused: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(glUnused: javax.microedition.khronos.opengles.GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.04f)
        lastNanos = now
        shake = max(0f, shake - dt * 0.82f)
        val time = (now - startNanos) / 1_000_000_000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(timeHandle, time)
        GLES30.glUniform2f(gravityHandle, gravityX, gravityY)
        GLES30.glUniform1f(shakeHandle, shake)
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        GLES30.glUniform2f(resolutionHandle, viewport[2].toFloat(), viewport[3].toFloat())
        val vertices = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices).position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, buffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
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
