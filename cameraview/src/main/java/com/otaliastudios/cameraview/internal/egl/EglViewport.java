package com.otaliastudios.cameraview.internal.egl;


import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.filter.Filter;
import com.otaliastudios.cameraview.filters.NoFilter;
import com.otaliastudios.cameraview.internal.GlUtils;


public class EglViewport {

    private final static CameraLogger LOG = CameraLogger.create(EglViewport.class.getSimpleName());

    private int mProgramHandle = -1;
    private int mTextureTarget;
    private int mTextureUnit;

    private Filter mFilter;
    private boolean mFilterChanged = false;

    public EglViewport() {
        this(new NoFilter());
    }

    public EglViewport(@NonNull Filter filter) {
        mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        mTextureUnit = GLES20.GL_TEXTURE0;
        mFilter = filter;
        createProgram();
    }

    private void createProgram() {
        release(); // Release old program if present.
        mProgramHandle = GlUtils.createProgram(mFilter.getVertexShader(), mFilter.getFragmentShader());
        mFilter.onCreate(mProgramHandle);
    }

    public void release() {
        if (mProgramHandle != -1) {
            mFilter.onDestroy(mProgramHandle);
            GLES20.glDeleteProgram(mProgramHandle);
            mProgramHandle = -1;
        }
    }

    public int createTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GlUtils.checkError("glGenTextures");

        int texId = textures[0];
        GLES20.glActiveTexture(mTextureUnit);
        GLES20.glBindTexture(mTextureTarget, texId);
        GlUtils.checkError("glBindTexture " + texId);

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GlUtils.checkError("glTexParameter");

        return texId;
    }

    public void setFilter(@NonNull Filter filter){
        this.mFilter = filter;
        mFilterChanged = true;
    }

    public void drawFrame(int textureId, float[] textureMatrix) {
        if (mFilterChanged) {
            createProgram();
            mFilterChanged = false;
        }

        GlUtils.checkError("draw start");

        // Select the program and the active texture.
        GLES20.glUseProgram(mProgramHandle);
        GlUtils.checkError("glUseProgram");
        GLES20.glActiveTexture(mTextureUnit);
        GLES20.glBindTexture(mTextureTarget, textureId);

        // Draw.
        mFilter.draw(textureMatrix);

        // Release.
        GLES20.glBindTexture(mTextureTarget, 0);
        GLES20.glUseProgram(0);
    }
}
