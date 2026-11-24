package com.udiweinstein.reframelite

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import kotlin.math.tan

/** Minimal EGL wrapper (one context, window / encoder / pbuffer surfaces). */
class EglCore(recordable: Boolean = false) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val attribs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
        )
        if (recordable) { attribs.add(EGL_RECORDABLE_ANDROID); attribs.add(1) }
        attribs.add(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, num, 0) && num[0] > 0) { "no EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun windowSurface(surface: Any): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    fun pbuffer(w: Int, h: Int): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)

    fun makeCurrent(s: EGLSurface) {
        EGL14.eglMakeCurrent(display, s, s, context)
    }

    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)

    fun setPresentationTime(s: EGLSurface, ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, s, ns)
    }

    fun destroySurface(s: EGLSurface) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Reprojects 360 frames (GoPro EAC, Insta360 fisheye, equirect) into a rectilinear view. */
class GlRenderer {
    private val program: Int
    private val uTex0: Int
    private val uTex1: Int
    private val uSt0: Int
    private val uSt1: Int
    private val uRot: Int
    private val uTanHalf: Int
    private val uAspect: Int
    private val uKind: Int
    private val uSize: Int
    private val uHalfLens: Int
    private val uLens0: Int
    private val uLens1: Int
    private val vao: Int

    init {
        program = link(VERTEX, FRAGMENT)
        uTex0 = GLES30.glGetUniformLocation(program, "uTex0")
        uTex1 = GLES30.glGetUniformLocation(program, "uTex1")
        uSt0 = GLES30.glGetUniformLocation(program, "uSt0")
        uSt1 = GLES30.glGetUniformLocation(program, "uSt1")
        uRot = GLES30.glGetUniformLocation(program, "uRot")
        uTanHalf = GLES30.glGetUniformLocation(program, "uTanHalf")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uKind = GLES30.glGetUniformLocation(program, "uKind")
        uSize = GLES30.glGetUniformLocation(program, "uSize")
        uHalfLens = GLES30.glGetUniformLocation(program, "uHalfLens")
        uLens0 = GLES30.glGetUniformLocation(program, "uLens0")
        uLens1 = GLES30.glGetUniformLocation(program, "uLens1")
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0)
        vao = a[0]
    }

    fun draw(width: Int, height: Int, view: ViewParams, info: MediaInfo, frames: VideoFrames) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frames.tex0)
        GLES30.glUniform1i(uTex0, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, if (frames.tex1 != 0) frames.tex1 else frames.tex0)
        GLES30.glUniform1i(uTex1, 1)
        GLES30.glUniformMatrix4fv(uSt0, 1, false, frames.matrix0, 0)
        GLES30.glUniformMatrix4fv(uSt1, 1, false, if (frames.tex1 != 0) frames.matrix1 else frames.matrix0, 0)

        GLES30.glUniformMatrix3fv(uRot, 1, false, view.rotation(), 0)
        GLES30.glUniform1f(uTanHalf, tan(Math.toRadians(view.fov / 2.0)).toFloat())
        GLES30.glUniform1f(uAspect, width.toFloat() / height)
        GLES30.glUniform1i(uKind, info.kind.id)
        GLES30.glUniform2f(uSize, info.width.toFloat(), info.height.toFloat())
        GLES30.glUniform1f(uHalfLens, Math.toRadians(100.0).toFloat())   // ~200° fisheye lenses
        if (info.kind == Kind.FISHEYE_SBS) {
            GLES30.glUniform4f(uLens0, 0.25f, 0.5f, 0.25f, 0.5f)
            GLES30.glUniform4f(uLens1, 0.75f, 0.5f, 0.25f, 0.5f)
        } else {
            GLES30.glUniform4f(uLens0, 0.5f, 0.5f, 0.5f, 0.5f)
            GLES30.glUniform4f(uLens1, 0.5f, 0.5f, 0.5f, 0.5f)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    companion object {
        private const val TAG = "GlRenderer"

        fun createOesTexture(): Int {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                Log.e(TAG, "shader compile error: $log")
                throw RuntimeException("Shader error: $log")
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Link error: " + GLES30.glGetProgramInfoLog(p))
            return p
        }

        private const val VERTEX = """#version 300 es
out vec2 vNdc;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vNdc = p * 2.0 - 1.0;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex0;
uniform samplerExternalOES uTex1;
uniform mat4 uSt0;
uniform mat4 uSt1;
uniform mat3 uRot;
uniform float uTanHalf;
uniform float uAspect;
uniform int uKind;
uniform vec2 uSize;
uniform float uHalfLens;
uniform vec4 uLens0;
uniform vec4 uLens1;
in vec2 vNdc;
out vec4 fragColor;
const float PI = 3.14159265;

// uv: 0..1, origin top-left of the stream image
vec4 tex0(vec2 uv) { return texture(uTex0, (uSt0 * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy); }
vec4 tex1(vec2 uv) { return texture(uTex1, (uSt1 * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy); }
vec4 texRow(int row, vec2 px) { vec2 uv = px / uSize; return row == 0 ? tex0(uv) : tex1(uv); }

// GoPro MAX: stream 0 = left|front|right, stream 1 = down|back|up (ffmpeg "eac" layout)
vec4 gopro(vec3 d) {
    vec3 v = vec3(d.x, -d.y, -d.z);
    float phi = atan(v.x, v.z);
    float theta = asin(clamp(v.y, -1.0, 1.0));
    float Q = PI * 0.25;
    float H = PI * 0.5;
    int dir;
    float phiNorm;
    if (phi >= -Q && phi < Q) { dir = 4; phiNorm = phi; }
    else if (phi >= -(H + Q) && phi < -Q) { dir = 1; phiNorm = phi + H; }
    else if (phi >= Q && phi < H + Q) { dir = 0; phiNorm = phi - H; }
    else { dir = 5; phiNorm = phi + (phi > 0.0 ? -PI : PI); }
    float thr = atan(cos(phiNorm));
    if (theta > thr) dir = 3;
    else if (theta < -thr) dir = 2;

    float uf;
    float vf;
    if (dir == 0) { uf = -v.z / v.x; vf = v.y / v.x; }
    else if (dir == 1) { uf = -v.z / v.x; vf = -v.y / v.x; }
    else if (dir == 2) { uf = -v.x / v.y; vf = -v.z / v.y; }
    else if (dir == 3) { uf = v.x / v.y; vf = -v.z / v.y; }
    else if (dir == 4) { uf = v.x / v.z; vf = v.y / v.z; }
    else { uf = v.x / v.z; vf = -v.y / v.z; }

    int pos;
    int rot;
    if (dir == 0) { pos = 2; rot = 0; }
    else if (dir == 1) { pos = 0; rot = 0; }
    else if (dir == 2) { pos = 5; rot = 3; }
    else if (dir == 3) { pos = 3; rot = 3; }
    else if (dir == 4) { pos = 1; rot = 0; }
    else { pos = 4; rot = 1; }
    float t;
    if (rot == 1) { t = uf; uf = -vf; vf = t; }
    else if (rot == 3) { t = -uf; uf = vf; vf = t; }

    float u = clamp(2.0 / PI * atan(uf) + 0.5, 0.0, 1.0);
    float w = clamp(2.0 / PI * atan(vf) + 0.5, 0.0, 1.0);
    int row = pos / 3;
    int col = pos - row * 3;
    float face = uSize.y;
    float region = (uSize.x - face) * 0.5;
    float ex = region - face;
    float fx = u * face;
    float fy = clamp(w * face, 0.5, face - 0.5);
    if (col == 1) {
        return texRow(row, vec2(clamp(region + fx, region + 0.5, region + face - 0.5), fy));
    }
    float base = col == 0 ? 0.0 : region + face;
    float x;
    if (ex <= 0.0) {
        x = base + fx;
    } else {
        float L = face * 0.5 - 1.5 * ex;
        if (fx < L) {
            x = base + fx;
        } else if (fx >= L + 3.0 * ex) {
            x = base + fx + ex;
        } else {
            float tt = (fx - L) / (3.0 * ex);
            float a = base + L + tt * 2.0 * ex;
            float b = a + 2.0 * ex;
            return mix(texRow(row, vec2(a, fy)), texRow(row, vec2(b, fy)), tt);
        }
    }
    x = clamp(x, base + 0.5, base + region - 0.5);
    return texRow(row, vec2(x, fy));
}

// Insta360: two ~200 degree fisheye lenses, front and back, blended in the overlap.
vec4 lensSample(int lens, vec3 l, float th) {
    float rho = length(l.xy);
    vec2 un = rho > 1e-6 ? l.xy / rho : vec2(0.0);
    vec4 L = lens == 0 ? uLens0 : uLens1;
    vec2 uv = L.xy + (th / uHalfLens) * vec2(un.x * L.z, -un.y * L.w);
    if (uKind == 3 && lens == 1) return tex1(uv);
    return tex0(uv);
}

vec4 fisheye(vec3 d) {
    vec3 l0 = d;
    vec3 l1 = vec3(-d.x, d.y, -d.z);
    float th0 = acos(clamp(-l0.z, -1.0, 1.0));
    float th1 = acos(clamp(-l1.z, -1.0, 1.0));
    bool v0 = th0 < uHalfLens;
    bool v1 = th1 < uHalfLens;
    if (!v1) return lensSample(0, l0, th0);
    if (!v0) return lensSample(1, l1, th1);
    float band = max(uHalfLens - PI * 0.5, 0.001);
    float t = smoothstep(PI * 0.5 - band, PI * 0.5 + band, th0);
    return mix(lensSample(0, l0, th0), lensSample(1, l1, th1), t);
}

vec4 equirect(vec3 d) {
    float lon = atan(d.x, -d.z);
    float lat = asin(clamp(d.y, -1.0, 1.0));
    return tex0(vec2(fract(lon / (2.0 * PI) + 0.5), 0.5 - lat / PI));
}

void main() {
    if (uKind == 2) {
        float sa = uSize.x / uSize.y;
        vec2 q = vNdc;
        if (uAspect > sa) q.x *= uAspect / sa; else q.y *= sa / uAspect;
        if (abs(q.x) > 1.0 || abs(q.y) > 1.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        fragColor = vec4(tex0(vec2(q.x * 0.5 + 0.5, 0.5 - q.y * 0.5)).rgb, 1.0);
        return;
    }
    vec3 d = normalize(vec3(vNdc.x * uTanHalf * uAspect, vNdc.y * uTanHalf, -1.0));
    d = normalize(uRot * d);
    vec4 c;
    if (uKind == 0) c = gopro(d);
    else if (uKind == 1) c = equirect(d);
    else c = fisheye(d);
    fragColor = vec4(c.rgb, 1.0);
}
"""
    }
}
