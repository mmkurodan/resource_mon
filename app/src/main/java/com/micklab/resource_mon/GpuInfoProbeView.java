package com.micklab.resource_mon;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.text.TextUtils;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class GpuInfoProbeView extends GLSurfaceView implements GLSurfaceView.Renderer {
    public interface Listener {
        void onGpuInfoReady(String vendor, String renderer, String version);
    }

    private final Listener listener;
    private boolean hasReported;

    public GpuInfoProbeView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        final String vendor = safeValue(gl.glGetString(GL10.GL_VENDOR));
        final String renderer = safeValue(gl.glGetString(GL10.GL_RENDERER));
        final String version = safeValue(gl.glGetString(GL10.GL_VERSION));

        if (!hasReported && listener != null) {
            hasReported = true;
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onGpuInfoReady(vendor, renderer, version);
                }
            });
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // The probe only needs the OpenGL strings.
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // The probe view never renders visible content.
    }

    private static String safeValue(String value) {
        if (TextUtils.isEmpty(value)) {
            return "Unavailable";
        }
        return value;
    }
}
