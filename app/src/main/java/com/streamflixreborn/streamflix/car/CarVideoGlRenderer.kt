package com.streamflixreborn.streamflix.car

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 2026-07-23 (projet Android Auto, LAB) — Compositeur OpenGL pour la projection vidéo voiture.
 *
 * ExoPlayer dessine dans une SurfaceTexture (texture externe OES) ; on redessine cette texture sur la
 * surface voiture à une ÉCHELLE réglable (`scale`), fond noir autour → permet de RÉDUIRE réellement
 * l'image (utile si le head-unit rogne les bords / overscan). Tout tourne sur un thread GL dédié.
 *
 * Usage :
 *   val r = CarVideoGlRenderer(carSurface, w, h)
 *   player.setVideoSurface(r.inputSurface)   // ExoPlayer rend dans la texture
 *   r.setScale(0.85f)                         // 85 % (réduit), 1f = plein
 *   ... r.release()
 */
@androidx.media3.common.util.UnstableApi
class CarVideoGlRenderer(
    private val outputSurface: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
) {
    @Volatile private var scale: Float = 1f
    @Volatile private var released = false

    private val thread = HandlerThread("CarVideoGl").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTexMatrix = 0

    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set

    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    // quad plein écran (positions) + coords texture
    private val quadPos = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    @Volatile private var ready = false
    private val lock = Object()

    init {
        handler.post {
            runCatching { initGl() }.onFailure { Log.e(TAG, "initGl KO: ${it.message}", it) }
            synchronized(lock) { ready = true; lock.notifyAll() }
        }
        // attendre l'init pour exposer inputSurface
        synchronized(lock) { while (!ready) runCatching { lock.wait(3000); if (!ready) return@synchronized } }
    }

    fun setScale(s: Float) {
        scale = s.coerceIn(0.4f, 1f)
        handler.post { drawFrame() }
    }

    fun release() {
        released = true
        handler.post {
            runCatching {
                if (::surfaceTexture.isInitialized) surfaceTexture.release()
                if (::inputSurface.isInitialized) inputSurface.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
            thread.quitSafely()
        }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setDefaultBufferSize(outWidth, outHeight)
        surfaceTexture.setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    private fun drawFrame() {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        runCatching {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)

            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            Matrix.setIdentityM(mvp, 0)
            Matrix.scaleM(mvp, 0, scale, scale, 1f) // réduit/agrandit le quad → marges noires

            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }.onFailure { Log.w(TAG, "drawFrame KO: ${it.message}") }
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPosition;
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """.trimIndent()
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "shader compile: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    companion object {
        private const val TAG = "CarVideoGl"
    }
}
