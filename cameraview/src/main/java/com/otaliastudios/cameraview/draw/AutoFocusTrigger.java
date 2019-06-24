package com.otaliastudios.cameraview.draw;

import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.gesture.GestureAction;

/**
 * Gives information about what triggered the autofocus operation.
 */
public enum AutoFocusTrigger {

    /**
     * Autofocus was triggered by {@link GestureAction#AUTOFOCUS}.
     */
    GESTURE,

    /**
     * Autofocus was triggered by the {@link CameraView#startAutoFocus(float, float)} method.
     */
    METHOD
}
