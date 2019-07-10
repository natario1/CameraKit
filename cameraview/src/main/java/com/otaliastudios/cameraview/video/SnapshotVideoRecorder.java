package com.otaliastudios.cameraview.video;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.os.Build;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.VideoResult;
import com.otaliastudios.cameraview.controls.Audio;
import com.otaliastudios.cameraview.engine.CameraEngine;
import com.otaliastudios.cameraview.engine.offset.Reference;
import com.otaliastudios.cameraview.preview.GlCameraPreview;
import com.otaliastudios.cameraview.preview.RendererFrameCallback;
import com.otaliastudios.cameraview.preview.RendererThread;
import com.otaliastudios.cameraview.size.Size;
import com.otaliastudios.cameraview.video.encoding.AudioMediaEncoder;
import com.otaliastudios.cameraview.video.encoding.EncoderThread;
import com.otaliastudios.cameraview.video.encoding.MediaEncoderEngine;
import com.otaliastudios.cameraview.video.encoding.TextureMediaEncoder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * A {@link VideoRecorder} that uses {@link android.media.MediaCodec} APIs.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class SnapshotVideoRecorder extends VideoRecorder implements RendererFrameCallback,
        MediaEncoderEngine.Listener {

    private static final String TAG = SnapshotVideoRecorder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private static final int DEFAULT_VIDEO_FRAMERATE = 30;
    private static final int DEFAULT_VIDEO_BITRATE = 1000000;
    private static final int DEFAULT_AUDIO_BITRATE = 64000;

    private static final int STATE_RECORDING = 0;
    private static final int STATE_NOT_RECORDING = 1;

    private MediaEncoderEngine mEncoderEngine;
    private CameraEngine mEngine;
    private GlCameraPreview mPreview;
    private boolean mFlipped;

    private int mCurrentState = STATE_NOT_RECORDING;
    private int mDesiredState = STATE_NOT_RECORDING;
    private int mTextureId = 0;

    public SnapshotVideoRecorder(@NonNull CameraEngine engine,
                                 @NonNull GlCameraPreview preview) {
        super(engine);
        mPreview = preview;
        mEngine = engine;
    }

    @Override
    protected void onStart() {
        mPreview.addRendererFrameCallback(this);
        mFlipped = mEngine.getAngles().flip(Reference.SENSOR, Reference.VIEW);
        mDesiredState = STATE_RECORDING;
    }

    @Override
    protected void onStop() {
        mDesiredState = STATE_NOT_RECORDING;
    }

    @RendererThread
    @Override
    public void onRendererTextureCreated(int textureId) {
        mTextureId = textureId;
    }

    @RendererThread
    @Override
    public void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, float scaleX, float scaleY) {
        if (mCurrentState == STATE_NOT_RECORDING && mDesiredState == STATE_RECORDING) {
            LOG.i("Starting the encoder engine.");

            // Set default options
            if (mResult.videoBitRate <= 0) mResult.videoBitRate = DEFAULT_VIDEO_BITRATE;
            if (mResult.videoFrameRate <= 0) mResult.videoFrameRate = DEFAULT_VIDEO_FRAMERATE;
            if (mResult.audioBitRate <= 0) mResult.audioBitRate = DEFAULT_AUDIO_BITRATE;

            // Video. Ensure width and height are divisible by 2, as I have read somewhere.
            Size size = mResult.size;
            int width = size.getWidth();
            int height = size.getHeight();
            width = width % 2 == 0 ? width : width + 1;
            height = height % 2 == 0 ? height : height + 1;
            String type = "";
            switch (mResult.videoCodec) {
                case H_263: type = "video/3gpp"; break; // MediaFormat.MIMETYPE_VIDEO_H263;
                case H_264: type = "video/avc"; break; // MediaFormat.MIMETYPE_VIDEO_AVC:
                case DEVICE_DEFAULT: type = "video/avc"; break;
            }
            LOG.w("Creating frame encoder. Rotation:", mResult.rotation);
            TextureMediaEncoder.Config config = new TextureMediaEncoder.Config(width, height,
                    mResult.videoBitRate,
                    mResult.videoFrameRate,
                    mResult.rotation,
                    type, mTextureId,
                    scaleX, scaleY,
                    mFlipped,
                    EGL14.eglGetCurrentContext()
            );
            TextureMediaEncoder videoEncoder = new TextureMediaEncoder(config);

            // Audio
            AudioMediaEncoder audioEncoder = null;
            if (mResult.audio == Audio.ON) {
                audioEncoder = new AudioMediaEncoder(new AudioMediaEncoder.Config(mResult.audioBitRate));
            }

            // Engine
            mEncoderEngine = new MediaEncoderEngine(mResult.file, videoEncoder, audioEncoder,
                    mResult.maxDuration, mResult.maxSize, SnapshotVideoRecorder.this);
            mEncoderEngine.start();
            mResult.rotation = 0; // We will rotate the result instead.
            mCurrentState = STATE_RECORDING;
        }

        if (mCurrentState == STATE_RECORDING) {
            LOG.v("dispatching frame.");
            TextureMediaEncoder textureEncoder = (TextureMediaEncoder) mEncoderEngine.getVideoEncoder();
            TextureMediaEncoder.TextureFrame textureFrame = textureEncoder.acquireFrame();
            textureFrame.timestamp = surfaceTexture.getTimestamp();
            surfaceTexture.getTransformMatrix(textureFrame.transform);
            if (mEncoderEngine != null) {
                // can happen on teardown
                mEncoderEngine.notify(TextureMediaEncoder.FRAME_EVENT, textureFrame);
            }
        }

        if (mCurrentState == STATE_RECORDING && mDesiredState == STATE_NOT_RECORDING) {
            LOG.i("Stopping the encoder engine.");
            mCurrentState = STATE_NOT_RECORDING; // before nulling encoderEngine!
            mEncoderEngine.stop();
            mEncoderEngine = null;
            mPreview.removeRendererFrameCallback(SnapshotVideoRecorder.this);
            mPreview = null;
        }

    }

    @EncoderThread
    @Override
    public void onEncoderStop(int stopReason, @Nullable Exception e) {
        // If something failed, undo the result, since this is the mechanism
        // to notify Camera1Engine about this.
        if (e != null) {
            LOG.e("Error onEncoderStop", e);
            mResult = null;
            mError = e;
        } else {
            if (stopReason == MediaEncoderEngine.STOP_BY_MAX_DURATION) {
                LOG.i("onEncoderStop because of max duration.");
                mResult.endReason = VideoResult.REASON_MAX_DURATION_REACHED;
            } else if (stopReason == MediaEncoderEngine.STOP_BY_MAX_SIZE) {
                LOG.i("onEncoderStop because of max size.");
                mResult.endReason = VideoResult.REASON_MAX_SIZE_REACHED;
            } else {
                LOG.i("onEncoderStop because of user.");
            }
        }
        // Cleanup
        mCurrentState = STATE_NOT_RECORDING;
        mDesiredState = STATE_NOT_RECORDING;
        mPreview.removeRendererFrameCallback(SnapshotVideoRecorder.this);
        mEncoderEngine = null;
        dispatchResult();
    }
}
