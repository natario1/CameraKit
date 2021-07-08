package com.otaliastudios.cameraview;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

public interface FileUriCallback {
    /**
     * Notifies that the data was succesfully written to file.
     * This is run on the UI thread.
     * Returns a null object if an exception was encountered, for example
     * if you don't have permissions to write to file.
     *
     * @param fileUri the written file, or null
     */
    @UiThread
    void onFileReady(@Nullable Uri fileUri);
}
