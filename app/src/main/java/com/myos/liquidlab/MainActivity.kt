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
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
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
        renderer.setGravity((-ax).coerceIn(-1.5f, 1.5f), (ay).coerceIn(-1.5f, 1.5f), az)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private class LiquidRenderer : GLSurfaceView.Renderer {
    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = 1f
    private var program = 0
    private var timeHandle = 0
    private var gravityHandle = 0
    private var resolutionHandle = 0
    private var kickHandle = 0
    private lateinit var vertices: FloatBuffer
    private var startNanos = 0L
    private var lastNanos = 0L
    private var kick = 0f
    private var previousMagnitude = 1f

    fun setGravity(x: Float, y: Float, z: Float) {
        gravityX = x
        gravityY = y
        val magnitude = kotlin.math.sqrt(x*x + y*y + z*z)
        val delta = abs(magnitude - previousMagnitude)
        if (delta > 0.42f) kick = min(1.8f, kick + delta * 1.8f)
        previousMagnitude = magnitude
    }

    override fun onSurfaceCreated(glUnused: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val vertex = """
            #version 300 es
            layout(location=0) in vec2 aPosition;
            out vec2 vUv;
            void main(){ vUv=aPosition*0.5+0.5; gl_Position=vec4(aPosition,0.0,1.0); }
        """.trimIndent()
        val fragment = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;
            uniform vec2 uResolution;
            uniform vec2 uGravity;
            uniform float uTime;
            uniform float uKick;
            float hash(vec2 p){ return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
            float noise(vec2 p){
                vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
                return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+1.0),f.x),f.y);
            }
            float roundedBox(vec2 p, vec2 b, float r){
                vec2 q=abs(p)-b+r;
                return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
            }
            void main(){
                vec2 uv=vUv;
                vec2 p=(uv-0.5)*vec2(uResolution.x/uResolution.y,1.0);
                vec3 col=vec3(0.025,0.055,0.075);
                // Soft studio illumination behind the glass vessel.
                float halo=exp(-length((uv-vec2(0.5,0.53))*vec2(1.0,0.8))*3.2);
                col+=vec3(0.035,0.12,0.15)*halo;
                vec2 tankP=p;
                float tank=roundedBox(tankP,vec2(0.405,0.435),0.055);
                float inside=1.0-smoothstep(-0.004,0.008,tank);
                // Gravity defines the free surface. The small waves decay with depth.
                vec2 g=normalize(vec2(uGravity.x*0.72, max(0.18,uGravity.y)));
                float wave=0.012*sin(p.x*18.0-uTime*2.0)+0.006*sin(p.x*31.0+uTime*1.45);
                wave+=uKick*0.018*sin(p.x*24.0-uTime*8.0)*exp(-uKick*0.7);
                float surface=dot(p,g)-0.035+wave;
                float liquid=1.0-smoothstep(-0.006,0.006,surface);
                liquid*=inside;
                float depth=clamp((0.45-p.y)*1.4,0.0,1.0);
                vec2 flow=vec2(uTime*0.035,-uTime*0.018);
                float caustic=noise(p*vec2(22.0,34.0)+flow);
                caustic=pow(max(0.0,1.0-abs(caustic-0.5)*2.0),5.0);
                float light=0.10+0.22*depth+0.12*caustic;
                vec3 deep=vec3(0.005,0.20,0.27);
                vec3 shallow=vec3(0.10,0.66,0.70);
                vec3 water=mix(shallow,deep,depth*0.82);
                water+=vec3(0.18,0.48,0.46)*caustic*0.22;
                // Surface Fresnel and narrow moving specular glints.
                float crest=exp(-abs(surface)*180.0);
                float glint=pow(max(0.0,sin(p.x*12.0+uTime*1.7+noise(p*9.0)*2.0)),18.0);
                water+=vec3(0.48,0.88,0.88)*(crest*0.48+glint*0.12);
                col=mix(col,water,liquid*0.92);
                // Darker lower edge gives the water body thickness.
                float bottomShade=smoothstep(0.28,0.42,-p.y)*liquid*inside;
                col*=1.0-0.18*bottomShade;
                // Glass wall, rim and reflected studio strips.
                float wall=1.0-smoothstep(0.001,0.009,abs(tank));
                float edgeGlow=exp(-abs(tank)*75.0);
                col=mix(col,vec3(0.50,0.82,0.88),wall*0.58);
                float verticalGlare=exp(-pow((p.x+0.365)*42.0,2.0))*inside*0.22;
                col+=vec3(0.55,0.82,0.90)*verticalGlare;
                col+=vec3(0.30,0.62,0.70)*edgeGlow*0.12;
                fragColor=vec4(col,1.0);
            }
        """.trimIndent()
        program = linkProgram(vertex, fragment)
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        gravityHandle = GLES30.glGetUniformLocation(program, "uGravity")
        resolutionHandle = GLES30.glGetUniformLocation(program, "uResolution")
        kickHandle = GLES30.glGetUniformLocation(program, "uKick")
        startNanos = System.nanoTime()
        lastNanos = startNanos
    }

    override fun onSurfaceChanged(glUnused: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(glUnused: GL10?) {
        val now = System.nanoTime()
        val dt = ((now-lastNanos)/1_000_000_000f).coerceIn(0f,0.04f)
        lastNanos=now
        kick=max(0f,kick-dt*0.75f)
        val t=(now-startNanos)/1_000_000_000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(timeHandle,t)
        GLES30.glUniform2f(gravityHandle,gravityX,gravityY)
        GLES30.glUniform1f(kickHandle,kick)
        val viewport=IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT,viewport,0)
        GLES30.glUniform2f(resolutionHandle,viewport[2].toFloat(),viewport[3].toFloat())
        val data=floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
        val buffer=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,0,buffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
        GLES30.glDisableVertexAttribArray(0)
    }

    private fun linkProgram(vertexSource:String, fragmentSource:String):Int {
        fun compile(type:Int, source:String):Int {
            val shader=GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader,source)
            GLES30.glCompileShader(shader)
            val ok=IntArray(1); GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0)
            if(ok[0]==0) throw IllegalStateException(GLES30.glGetShaderInfoLog(shader))
            return shader
        }
        val vs=compile(GLES30.GL_VERTEX_SHADER,vertexSource)
        val fs=compile(GLES30.GL_FRAGMENT_SHADER,fragmentSource)
        val p=GLES30.glCreateProgram()
        GLES30.glAttachShader(p,vs); GLES30.glAttachShader(p,fs); GLES30.glLinkProgram(p)
        val ok=IntArray(1); GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0)
        if(ok[0]==0) throw IllegalStateException(GLES30.glGetProgramInfoLog(p))
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }
}
