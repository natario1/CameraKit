package com.otaliastudios.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.otaliastudios.cameraview.filter.BaseFilter;
import com.otaliastudios.cameraview.internal.GlUtils;

/**
 * Applies back-light filling to the frames.
 */
public class FillLightFilter extends BaseFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "uniform float mult;\n"
            + "uniform float igamma;\n"
            + "varying vec2 vTextureCoord;\n"
            + "void main() {\n"
            + "  const vec3 color_weights = vec3(0.25, 0.5, 0.25);\n"
            + "  vec4 color = texture2D(sTexture, vTextureCoord);\n"
            + "  float lightmask = dot(color.rgb, color_weights);\n"
            + "  float backmask = (1.0 - lightmask);\n"
            + "  vec3 ones = vec3(1.0, 1.0, 1.0);\n"
            + "  vec3 diff = pow(mult * color.rgb, igamma * ones) - color.rgb;\n"
            + "  diff = min(diff, 1.0);\n"
            + "  vec3 new_color = min(color.rgb + diff * backmask, 1.0);\n"
            + "  gl_FragColor = vec4(new_color, color.a);\n"
            + "}\n";

    private float strength = 0.5f;
    private int multiplierLocation = -1;
    private int gammaLocation = -1;

    @SuppressWarnings("WeakerAccess")
    public FillLightFilter() { }

    /**
     * Sets the current strength.
     * 0.0: no change.
     * 1.0: max strength.
     *
     * @param strength strength
     */
    @SuppressWarnings("WeakerAccess")
    public void setStrength(float strength) {
        if (strength < 0.0f) strength = 0f;
        if (strength > 1.0f) strength = 1f;
        this.strength = strength;
    }

    /**
     * Returns the current strength.
     *
     * @see #setStrength(float)
     * @return strength
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public float getStrength() {
        return strength;
    }


    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        multiplierLocation = GLES20.glGetUniformLocation(programHandle, "mult");
        GlUtils.checkLocation(multiplierLocation, "mult");
        gammaLocation = GLES20.glGetUniformLocation(programHandle, "igamma");
        GlUtils.checkLocation(gammaLocation, "igamma");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        multiplierLocation = -1;
        gammaLocation = -1;
    }

    @Override
    protected void onPreDraw(float[] transformMatrix) {
        super.onPreDraw(transformMatrix);
        float amount = 1.0f - strength;
        float multiplier = 1.0f / (amount * 0.7f + 0.3f);
        GLES20.glUniform1f(multiplierLocation, multiplier);
        GlUtils.checkError("glUniform1f");

        float fadeGamma = 0.3f;
        float faded = fadeGamma + (1.0f - fadeGamma) * multiplier;
        float gamma = 1.0f / faded;
        GLES20.glUniform1f(gammaLocation, gamma);
        GlUtils.checkError("glUniform1f");
    }


    @Override
    protected BaseFilter onCopy() {
        FillLightFilter filter = new FillLightFilter();
        filter.setStrength(getStrength());
        return filter;
    }
}
