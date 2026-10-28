package app.simple.felicity.decorations.visualizer

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.PowerManager
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import app.simple.felicity.preferences.VisualizerPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * GLSurfaceView supporting a live GLSL Fragment Shader Sandbox with real-time audio
 * reactivity, dynamic camera modes (Orbit, Beat-Cam, Gyroscope tilt), and touch-draggable
 * interactive 3D meshes.
 *
 * Uniforms exported to fragment shaders:
 * - `uniform float u_time;` (playback time in seconds)
 * - `uniform vec2 u_resolution;` (viewport width, height in pixels)
 * - `uniform float u_bass;` (real-time normalized bass magnitude 0..1)
 * - `uniform float u_mid;` (real-time normalized mid magnitude 0..1)
 * - `uniform float u_treble;` (real-time normalized treble magnitude 0..1)
 * - `uniform vec2 u_touch;` (touch drag coordinate 0..1)
 * - `uniform vec3 u_gyro;` (device pitch, roll, yaw from gyroscope)
 * - `uniform vec3 u_cam_pos;` (dynamic camera position for orbit/beat-cam)
 *
 * @author Hamza417
 */
class ShaderSandboxView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), SensorEventListener {

    private val renderer: ShaderRenderer

    var orbitModeEnabled = true
    var beatCamEnabled: Boolean
        get() = app.simple.felicity.preferences.VisualizerPreferences.isBeatCamEnabled()
        set(value) {
            app.simple.felicity.preferences.VisualizerPreferences.setBeatCamEnabled(value)
        }
    var gyroscopeEnabled: Boolean
        get() = app.simple.felicity.preferences.VisualizerPreferences.isGyroscopeEnabled()
        set(value) {
            app.simple.felicity.preferences.VisualizerPreferences.setGyroscopeEnabled(value)
        }

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null

    init {
        setEGLContextClientVersion(2)
        renderer = ShaderRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!gyroscopeEnabled || event == null) return
        if (event.values.size >= 3) {
            renderer.setGyro(event.values[0], event.values[1], event.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var velocityTracker: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!app.simple.felicity.preferences.VisualizerPreferences.isTouchPhysicsEnabled()) {
            return super.onTouchEvent(event)
        }
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        val normX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val normY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        renderer.setTouch(normX, normY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                renderer.triggerParticleBurst()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastTouchX) / width.coerceAtLeast(1)
                val dy = (event.y - lastTouchY) / height.coerceAtLeast(1)
                renderer.addTouchDrag(dx * 3f, dy * 3f)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = (velocityTracker?.xVelocity ?: 0f) / width.coerceAtLeast(1)
                val vy = (velocityTracker?.yVelocity ?: 0f) / height.coerceAtLeast(1)
                renderer.setFlingVelocity(vx * 2f, vy * 2f)
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    fun setShaderCode(code: String): String? {
        return renderer.updateShader(code)
    }

    fun setAudioLevels(bass: Float, mid: Float, treble: Float) {
        renderer.setAudioLevels(bass, mid, treble)
    }

    private inner class ShaderRenderer : Renderer {
        private var program = 0
        private var vertexShader = 0
        private var fragmentShader = 0

        private var uTimeLoc = -1
        private var uResolutionLoc = -1
        private var uBassLoc = -1
        private var uMidLoc = -1
        private var uTrebleLoc = -1
        private var uTouchLoc = -1
        private var uGyroLoc = -1
        private var uCamPosLoc = -1
        private var uLightDirLoc = -1
        private var uTouchSpinLoc = -1
        private var uBurstLoc = -1
        private var uChromaticAberrationLoc = -1
        private var uBloomLoc = -1

        private var startTime = System.currentTimeMillis()
        private var lastFrameTime = 0L
        private var surfaceWidth = 1080f
        private var surfaceHeight = 1920f

        @Volatile private var gyroX = 0f
        @Volatile private var gyroY = 0f
        @Volatile private var gyroZ = 0f

        @Volatile private var touchX = 0.5f
        @Volatile private var touchY = 0.5f

        @Volatile private var spinAngleX = 0f
        @Volatile private var spinAngleY = 0f
        @Volatile private var spinVelX = 0f
        @Volatile private var spinVelY = 0f
        @Volatile private var liveBurst = 0f
        @Volatile private var liveAberration = 0f
        @Volatile private var liveBloom = 0f

        @Volatile private var pendingShaderCode: String? = DEFAULT_COSMIC_SHADER
        private var lastCompileError: String? = null

        private val quadVertices = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f,  1f,
                1f,  1f
        )
        private val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadVertices)
                position(0)
            }

        @Volatile private var liveBass = 0.2f
        @Volatile private var liveMid = 0.2f
        @Volatile private var liveTreble = 0.2f

        fun setAudioLevels(bass: Float, mid: Float, treble: Float) {
            liveBass = bass.coerceIn(0f, 1f)
            liveMid = mid.coerceIn(0f, 1f)
            liveTreble = treble.coerceIn(0f, 1f)
        }

        fun setGyro(x: Float, y: Float, z: Float) {
            gyroX = x
            gyroY = y
            gyroZ = z
        }

        fun setTouch(x: Float, y: Float) {
            touchX = x
            touchY = y
        }

        fun triggerParticleBurst() {
            liveBurst = 1.0f
        }

        fun addTouchDrag(dx: Float, dy: Float) {
            spinAngleX += dx
            spinAngleY += dy
            spinVelX = dx * 0.4f
            spinVelY = dy * 0.4f
        }

        fun setFlingVelocity(vx: Float, vy: Float) {
            spinVelX = vx.coerceIn(-4f, 4f)
            spinVelY = vy.coerceIn(-4f, 4f)
        }

        fun updateShader(code: String): String? {
            pendingShaderCode = code
            return lastCompileError
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            compileProgram(pendingShaderCode ?: DEFAULT_COSMIC_SHADER)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            surfaceWidth = width.toFloat()
            surfaceHeight = height.toFloat()
        }

        override fun onDrawFrame(gl: GL10?) {
            val isBatterySaver = VisualizerPreferences.is3dBatterySaverEnabled() ||
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

            // Battery Saver: Enforce 30 FPS pacing
            val now = System.currentTimeMillis()
            val delta = now - lastFrameTime
            if (isBatterySaver && delta < 33L) {
                try {
                    Thread.sleep(33L - delta)
                } catch (_: InterruptedException) {}
            }
            lastFrameTime = System.currentTimeMillis()

            // Battery Saver: Resolution scaling (0.65x)
            val scale = if (isBatterySaver) 0.65f else 1.0f
            val vw = (surfaceWidth * scale).toInt().coerceAtLeast(1)
            val vh = (surfaceHeight * scale).toInt().coerceAtLeast(1)
            GLES20.glViewport(0, 0, vw, vh)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val code = pendingShaderCode
            if (code != null) {
                compileProgram(code)
                pendingShaderCode = null
            }

            if (program == 0) return

            GLES20.glUseProgram(program)

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f

            val bass = liveBass
            val mid = liveMid
            val treble = liveTreble

            // Spin physics & momentum decay towards audio-reactive loop
            spinAngleX += spinVelX
            spinAngleY += spinVelY
            spinVelX *= 0.93f
            spinVelY *= 0.93f
            spinAngleX *= 0.985f
            spinAngleY *= 0.985f

            // Particle shockwave burst decay
            liveBurst *= 0.91f
            if (liveBurst < 0.005f) liveBurst = 0f

            // Reactive post-processing: chromatic aberration and bloom on heavy bass drops
            val bassTransient = if (bass > 0.62f) (bass - 0.62f) / 0.38f else 0.0f
            liveAberration = liveAberration * 0.80f + bassTransient * 0.035f * 0.20f
            liveBloom = liveBloom * 0.85f + (bass * 0.75f + mid * 0.25f) * 0.15f

            // Dynamic camera calculations
            var camX = 0f
            var camY = 0f
            var camZ = 3.0f

            if (orbitModeEnabled) {
                camX = sin(elapsedSec * 0.4f) * 2.5f
                camZ = cos(elapsedSec * 0.4f) * 2.5f
            }

            if (beatCamEnabled && bass > 0.6f) {
                val kickShake = (Random.nextFloat() - 0.5f) * 0.15f * bass
                camX += kickShake
                camY += kickShake
                camZ -= (bass * 0.4f) // zoom pulse on bass transient
            }

            if (gyroscopeEnabled) {
                camX += gyroY * 0.8f
                camY -= gyroX * 0.8f
            }

            // Dynamic holographic lighting vector from gyro & time
            var lx = sin(elapsedSec * 0.5f) + gyroY * 1.5f
            var ly = cos(elapsedSec * 0.5f) - gyroX * 1.5f
            var lz = 2.0f + gyroZ * 0.5f
            val lLen = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(0.001f)
            lx /= lLen
            ly /= lLen
            lz /= lLen

            if (uTimeLoc != -1) GLES20.glUniform1f(uTimeLoc, elapsedSec)
            if (uResolutionLoc != -1) GLES20.glUniform2f(uResolutionLoc, surfaceWidth * scale, surfaceHeight * scale)
            if (uBassLoc != -1) GLES20.glUniform1f(uBassLoc, bass)
            if (uMidLoc != -1) GLES20.glUniform1f(uMidLoc, mid)
            if (uTrebleLoc != -1) GLES20.glUniform1f(uTrebleLoc, treble)
            if (uTouchLoc != -1) GLES20.glUniform2f(uTouchLoc, touchX, touchY)
            if (uGyroLoc != -1) GLES20.glUniform3f(uGyroLoc, gyroX, gyroY, gyroZ)
            if (uCamPosLoc != -1) GLES20.glUniform3f(uCamPosLoc, camX, camY, camZ)
            if (uLightDirLoc != -1) GLES20.glUniform3f(uLightDirLoc, lx, ly, lz)
            if (uTouchSpinLoc != -1) GLES20.glUniform2f(uTouchSpinLoc, spinAngleX, spinAngleY)
            if (uBurstLoc != -1) GLES20.glUniform1f(uBurstLoc, liveBurst)
            if (uChromaticAberrationLoc != -1) GLES20.glUniform1f(uChromaticAberrationLoc, liveAberration)
            if (uBloomLoc != -1) GLES20.glUniform1f(uBloomLoc, liveBloom)

            val aPosLoc = GLES20.glGetAttribLocation(program, "a_position")
            if (aPosLoc != -1) {
                GLES20.glEnableVertexAttribArray(aPosLoc)
                GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPosLoc)
            }
        }

        private fun compileProgram(fragCode: String) {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragCode)

            if (vShader == 0 || fShader == 0) {
                return
            }

            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val error = GLES20.glGetProgramInfoLog(prog)
                Log.e(TAG, "Shader link error: $error")
                lastCompileError = error
                GLES20.glDeleteProgram(prog)
                return
            }

            if (program != 0) GLES20.glDeleteProgram(program)
            if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)

            program = prog
            vertexShader = vShader
            fragmentShader = fShader
            lastCompileError = null

            uTimeLoc = GLES20.glGetUniformLocation(prog, "u_time")
            uResolutionLoc = GLES20.glGetUniformLocation(prog, "u_resolution")
            uBassLoc = GLES20.glGetUniformLocation(prog, "u_bass")
            uMidLoc = GLES20.glGetUniformLocation(prog, "u_mid")
            uTrebleLoc = GLES20.glGetUniformLocation(prog, "u_treble")
            uTouchLoc = GLES20.glGetUniformLocation(prog, "u_touch")
            uGyroLoc = GLES20.glGetUniformLocation(prog, "u_gyro")
            uCamPosLoc = GLES20.glGetUniformLocation(prog, "u_cam_pos")
            uLightDirLoc = GLES20.glGetUniformLocation(prog, "u_light_dir")
            uTouchSpinLoc = GLES20.glGetUniformLocation(prog, "u_touch_spin")
            uBurstLoc = GLES20.glGetUniformLocation(prog, "u_burst")
            uChromaticAberrationLoc = GLES20.glGetUniformLocation(prog, "u_chromatic_aberration")
            uBloomLoc = GLES20.glGetUniformLocation(prog, "u_bloom")
            Log.i(TAG, "Custom GLSL Shader program compiled and linked successfully.")
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                Log.e(TAG, "Shader compile error: $info")
                lastCompileError = info
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    companion object {
        private const val TAG = "ShaderSandboxView"

        private const val VERTEX_SHADER_SRC = """
            attribute vec2 a_position;
            varying vec2 v_uv;
            void main() {
                v_uv = (a_position + 1.0) * 0.5;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """

        const val DEFAULT_COSMIC_SHADER = """
            precision mediump float;
            varying vec2 v_uv;
            uniform float u_time;
            uniform vec2 u_resolution;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_treble;
            uniform vec2 u_touch;
            uniform vec3 u_cam_pos;
            uniform vec3 u_light_dir;
            uniform vec2 u_touch_spin;
            uniform float u_burst;
            uniform float u_chromatic_aberration;
            uniform float u_bloom;

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / min(u_resolution.x, u_resolution.y);
                float s = sin(u_touch_spin.x * 2.0);
                float c = cos(u_touch_spin.x * 2.0);
                uv = mat2(c, -s, s, c) * uv;
                uv += (u_touch - 0.5) * 0.4;

                float dist = length(uv);
                float angle = atan(uv.y, uv.x);

                // Shockwave ripple from touch burst
                float ripple = sin(dist * 25.0 - u_time * 8.0) * u_burst * 0.15;
                dist += ripple;

                // Cosmic audio ring with chromatic aberration radial split
                float ca = u_chromatic_aberration;
                float ringR = 0.02 / max(abs(sin((dist + ca) * 12.0 - u_time * 2.0 + u_bass * 5.0)), 0.001);
                float ringG = 0.02 / max(abs(sin(dist * 12.0 - u_time * 2.0 + u_bass * 5.0)), 0.001);
                float ringB = 0.02 / max(abs(sin((dist - ca) * 12.0 - u_time * 2.0 + u_bass * 5.0)), 0.001);

                // Harmonic spiral rays
                float rays = sin(angle * 8.0 + u_time + dist * 4.0) * u_mid;

                // Holographic specular sheen from gyro light dir
                vec3 norm = normalize(vec3(uv, 0.4));
                float spec = pow(max(dot(norm, u_light_dir), 0.0), 8.0) * 0.6;

                vec3 col;
                col.r = (ringR + rays) * (0.2 + u_bass * 0.9) + spec;
                col.g = (ringG + rays) * (0.3 + u_mid * 0.7) + spec * 0.8;
                col.b = (ringB + rays) * (0.7 + u_treble * 0.8) + spec * 1.2;

                // Bloom glow on heavy transients
                col += vec3(0.3, 0.1, 0.5) * u_bloom * (ringG * 0.5);
                col *= (1.0 - smoothstep(0.35 + u_bass * 0.25, 0.95, dist));

                gl_FragColor = vec4(col, 1.0);
            }
        """

        const val NEON_TUNNEL_SHADER = """
            precision mediump float;
            varying vec2 v_uv;
            uniform float u_time;
            uniform vec2 u_resolution;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_treble;
            uniform vec2 u_touch;
            uniform vec3 u_light_dir;
            uniform vec2 u_touch_spin;
            uniform float u_burst;
            uniform float u_chromatic_aberration;
            uniform float u_bloom;

            void main() {
                vec2 p = (gl_FragCoord.xy * 2.0 - u_resolution) / min(u_resolution.x, u_resolution.y);
                float s = sin(u_touch_spin.x * 1.5);
                float c = cos(u_touch_spin.x * 1.5);
                p = mat2(c, -s, s, c) * p;
                p += (u_touch - 0.5) * 0.8;

                float a = atan(p.y, p.x);
                float r = length(p) + sin(length(p) * 20.0 - u_time * 6.0) * u_burst * 0.2;

                float ca = u_chromatic_aberration * 0.5;
                vec2 uvR = vec2(0.3 / (r + ca) + u_time * (0.4 + u_bass * 0.5), a / 3.14159265);
                vec2 uvG = vec2(0.3 / r + u_time * (0.4 + u_bass * 0.5), a / 3.14159265);
                vec2 uvB = vec2(0.3 / (r - ca) + u_time * (0.4 + u_bass * 0.5), a / 3.14159265);

                float w = cos(uvG.x * 10.0) * sin(uvG.y * 10.0);

                vec3 col;
                col.r = 0.5 + 0.5 * cos(u_time + uvR.x * 2.0);
                col.g = 0.5 + 0.5 * cos(u_time + uvG.y * 2.0 + 2.0);
                col.b = 0.5 + 0.5 * cos(u_time + uvB.x * 2.0 + 4.0);

                col *= (1.0 + u_bass * 1.8 + u_bloom * 0.8);
                col *= smoothstep(0.04, 0.4, r);

                gl_FragColor = vec4(col * (0.8 + 0.2 * w), 1.0);
            }
        """

        const val QUANTUM_LATTICE_SHADER = """
            precision mediump float;
            varying vec2 v_uv;
            uniform float u_time;
            uniform vec2 u_resolution;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_treble;
            uniform vec3 u_cam_pos;
            uniform vec3 u_light_dir;
            uniform vec2 u_touch_spin;
            uniform float u_burst;
            uniform float u_chromatic_aberration;
            uniform float u_bloom;

            void main() {
                vec2 st = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
                float s = sin(u_touch_spin.x);
                float c = cos(u_touch_spin.x);
                st = mat2(c, -s, s, c) * st;

                st *= (3.0 - u_bass * 1.2);

                vec2 grid = fract(st) - 0.5;
                float d = length(grid) - u_burst * 0.1;

                float pulse = 0.05 + 0.1 * sin(u_time * 3.0 + length(st) * 4.0) + u_bass * 0.15;
                float ca = u_chromatic_aberration * 0.2;
                float glowR = pulse / max(d + ca, 0.01);
                float glowG = pulse / max(d, 0.01);
                float glowB = pulse / max(d - ca, 0.01);

                vec3 color = vec3(
                    (0.2 + u_treble) * glowR,
                    (0.8 + u_bass * 0.2 + u_bloom * 0.4) * glowG,
                    (0.9 + u_mid) * glowB
                );

                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
