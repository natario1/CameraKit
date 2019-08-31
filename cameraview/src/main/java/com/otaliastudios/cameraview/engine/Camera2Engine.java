package com.otaliastudios.cameraview.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Pair;
import android.util.Rational;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.otaliastudios.cameraview.CameraException;
import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.CameraOptions;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.VideoResult;
import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.controls.Flash;
import com.otaliastudios.cameraview.controls.Hdr;
import com.otaliastudios.cameraview.controls.Mode;
import com.otaliastudios.cameraview.controls.WhiteBalance;
import com.otaliastudios.cameraview.engine.mappers.Camera2Mapper;
import com.otaliastudios.cameraview.engine.offset.Axis;
import com.otaliastudios.cameraview.engine.offset.Reference;
import com.otaliastudios.cameraview.frame.Frame;
import com.otaliastudios.cameraview.frame.FrameManager;
import com.otaliastudios.cameraview.gesture.Gesture;
import com.otaliastudios.cameraview.internal.utils.CropHelper;
import com.otaliastudios.cameraview.internal.utils.ImageHelper;
import com.otaliastudios.cameraview.internal.utils.WorkerHandler;
import com.otaliastudios.cameraview.picture.Full2PictureRecorder;
import com.otaliastudios.cameraview.picture.SnapshotGlPictureRecorder;
import com.otaliastudios.cameraview.preview.GlCameraPreview;
import com.otaliastudios.cameraview.size.AspectRatio;
import com.otaliastudios.cameraview.size.Size;
import com.otaliastudios.cameraview.size.SizeSelectors;
import com.otaliastudios.cameraview.video.Full2VideoRecorder;
import com.otaliastudios.cameraview.video.SnapshotVideoRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Engine extends CameraEngine implements ImageReader.OnImageAvailableListener, Meter.Callback {

    private static final String TAG = Camera2Engine.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private static final int FRAME_PROCESSING_FORMAT = ImageFormat.NV21;
    private static final int FRAME_PROCESSING_INPUT_FORMAT = ImageFormat.YUV_420_888;

    private final CameraManager mManager;
    private String mCameraId;
    private CameraDevice mCamera;
    private CameraCharacteristics mCameraCharacteristics;
    private CameraCaptureSession mSession;
    private CaptureRequest.Builder mRepeatingRequestBuilder;
    private CaptureRequest mRepeatingRequest;
    private CameraCaptureSession.CaptureCallback mRepeatingRequestCallback;
    private CaptureResult mLastRepeatingResult;
    private final Camera2Mapper mMapper = Camera2Mapper.get();

    // Frame processing
    private Size mFrameProcessingSize;
    private ImageReader mFrameProcessingReader; // need this or the reader surface is collected
    private final WorkerHandler mFrameConversionHandler;
    private Surface mFrameProcessingSurface;

    // Preview
    private Surface mPreviewStreamSurface;

    // Video recording
    private VideoResult.Stub mFullVideoPendingStub; // When takeVideo is called, we have to reset the session.

    // Picture capturing
    private ImageReader mPictureReader;
    private final boolean mPictureCaptureStopsPreview = false; // can make configurable at some point

    // 3A metering
    private Meter mMeter;

    public Camera2Engine(Callback callback) {
        super(callback);
        mManager = (CameraManager) mCallback.getContext().getSystemService(Context.CAMERA_SERVICE);
        mFrameConversionHandler = WorkerHandler.get("CameraFrameConversion");
    }

    //region Utilities

    @NonNull
    private <T> T readCharacteristic(@NonNull CameraCharacteristics.Key<T> key,
                                     @NonNull T fallback) {
        return readCharacteristic(mCameraCharacteristics, key, fallback);
    }

    @NonNull
    private <T> T readCharacteristic(@NonNull CameraCharacteristics characteristics,
                                     @NonNull CameraCharacteristics.Key<T> key,
                                     @NonNull T fallback) {
        T value = characteristics.get(key);
        return value == null ? fallback : value;
    }

    @NonNull
    private CameraException createCameraException(@NonNull CameraAccessException exception) {
        int reason;
        switch (exception.getReason()) {
            case CameraAccessException.CAMERA_DISABLED: reason = CameraException.REASON_FAILED_TO_CONNECT; break;
            case CameraAccessException.CAMERA_ERROR: reason = CameraException.REASON_DISCONNECTED; break;
            case CameraAccessException.CAMERA_DISCONNECTED: reason = CameraException.REASON_DISCONNECTED; break;
            case CameraAccessException.CAMERA_IN_USE: reason = CameraException.REASON_FAILED_TO_CONNECT; break;
            case CameraAccessException.MAX_CAMERAS_IN_USE: reason = CameraException.REASON_FAILED_TO_CONNECT; break;
            default: reason = CameraException.REASON_UNKNOWN; break;
        }
        return new CameraException(exception, reason);
    }

    @NonNull
    private CameraException createCameraException(int stateCallbackError) {
        int reason;
        switch (stateCallbackError) {
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: reason = CameraException.REASON_FAILED_TO_CONNECT; break; // Device policy
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: reason = CameraException.REASON_FAILED_TO_CONNECT; break; // Fatal error
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: reason = CameraException.REASON_FAILED_TO_CONNECT; break; // Fatal error, device might have to be restarted
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: reason = CameraException.REASON_FAILED_TO_CONNECT; break;
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: reason = CameraException.REASON_FAILED_TO_CONNECT; break;
            default: reason = CameraException.REASON_UNKNOWN; break;
        }
        return new CameraException(reason);
    }

    /**
     * When creating a new builder, we want to
     * - set it to {@link #mRepeatingRequestBuilder}, the current one
     * - add a tag for the template just in case
     * - apply all the current parameters
     */
    @SuppressWarnings("UnusedReturnValue")
    @NonNull
    private CaptureRequest.Builder createRepeatingRequestBuilder(int template) throws CameraAccessException {
        mRepeatingRequestBuilder = mCamera.createCaptureRequest(template);
        mRepeatingRequestBuilder.setTag(template);
        applyAllParameters(mRepeatingRequestBuilder);
        return mRepeatingRequestBuilder;
    }

    /**
     * Sets up the repeating request builder with default surfaces and extra ones
     * if needed (like a video recording surface).
     */
    private void addRepeatingRequestBuilderSurfaces(@NonNull Surface... extraSurfaces) {
        mRepeatingRequestBuilder.addTarget(mPreviewStreamSurface);
        if (mFrameProcessingSurface != null) {
            mRepeatingRequestBuilder.addTarget(mFrameProcessingSurface);
        }
        for (Surface extraSurface : extraSurfaces) {
            if (extraSurface == null) {
                throw new IllegalArgumentException("Should not add a null surface.");
            }
            mRepeatingRequestBuilder.addTarget(extraSurface);
        }
    }

    /**
     * Sets up the repeating request builder with default surfaces and extra ones
     * if needed (like a video recording surface).
     */
    private void removeRepeatingRequestBuilderSurfaces() {
        mRepeatingRequestBuilder.removeTarget(mPreviewStreamSurface);
        if (mFrameProcessingSurface != null) {
            mRepeatingRequestBuilder.removeTarget(mFrameProcessingSurface);
        }
    }

    /**
     * Applies the repeating request builder to the preview, assuming we actually have a preview
     * running. Can be called after changing parameters to the builder.
     *
     * To apply a new builder (for example switch between TEMPLATE_PREVIEW and TEMPLATE_RECORD)
     * it should be set before calling this method, for example by calling
     * {@link #createRepeatingRequestBuilder(int)}.
     */
    private void applyRepeatingRequestBuilder() {
        applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED, null);
    }

    private void applyRepeatingRequestBuilder(boolean checkStarted, int errorReason, @Nullable final Runnable onFirstFrame) {
        if (!checkStarted || getPreviewState() == STATE_STARTED) {
            try {
                mRepeatingRequest = mRepeatingRequestBuilder.build();
                final AtomicBoolean firstFrame = new AtomicBoolean(false);
                mRepeatingRequestCallback = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber);
                        if (firstFrame.compareAndSet(false, true) && onFirstFrame != null) {
                            onFirstFrame.run();
                        }
                        if (mPictureRecorder instanceof Full2PictureRecorder) {
                            ((Full2PictureRecorder) mPictureRecorder).onCaptureStarted(request);
                        }
                    }

                    @Override
                    public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                        super.onCaptureProgressed(session, request, partialResult);
                        if (mPictureRecorder instanceof Full2PictureRecorder) {
                            ((Full2PictureRecorder) mPictureRecorder).onCaptureProgressed(partialResult);
                        }
                        if (mMeter != null && mMeter.isMetering()) {
                            mMeter.onCapture(partialResult);
                        }
                    }

                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        mLastRepeatingResult = result;
                        if (mPictureRecorder instanceof Full2PictureRecorder) {
                            ((Full2PictureRecorder) mPictureRecorder).onCaptureCompleted(result);
                        }
                        if (mMeter != null && mMeter.isMetering()) {
                            mMeter.onCapture(result);
                        }
                    }

                };
                mSession.setRepeatingRequest(mRepeatingRequest, mRepeatingRequestCallback, null);
            } catch (CameraAccessException e) {
                throw new CameraException(e, errorReason);
            }
        }
    }
    //endregion

    //region Protected APIs

    @NonNull
    @Override
    protected List<Size> getPreviewStreamAvailableSizes() {
        try {
            CameraCharacteristics characteristics = mManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streamMap == null)
                throw new RuntimeException("StreamConfigurationMap is null. Should not happen.");
            // This works because our previews return either a SurfaceTexture or a SurfaceHolder, which are
            // accepted class types by the getOutputSizes method.
            android.util.Size[] sizes = streamMap.getOutputSizes(mPreview.getOutputClass());
            List<Size> candidates = new ArrayList<>(sizes.length);
            for (android.util.Size size : sizes) {
                Size add = new Size(size.getWidth(), size.getHeight());
                if (!candidates.contains(add)) candidates.add(add);
            }
            return candidates;
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
    }

    @WorkerThread
    @Override
    protected void onPreviewStreamSizeChanged() {
        restartBind();
    }

    @Override
    protected boolean collectCameraInfo(@NonNull Facing facing) {
        int internalFacing = mMapper.mapFacing(facing);
        String[] cameraIds = null;
        try {
            cameraIds = mManager.getCameraIdList();
        } catch (CameraAccessException e) {
            // This should never happen, I don't see how it could crash here.
            // However, let's launch an unrecoverable exception.
            throw createCameraException(e);
        }
        LOG.i("collectCameraInfo", "Facing:", facing, "Internal:", internalFacing, "Cameras:", cameraIds.length);
        for (String cameraId : cameraIds) {
            try {
                CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraId);
                if (internalFacing == readCharacteristic(characteristics, CameraCharacteristics.LENS_FACING, -99)) {
                    mCameraId = cameraId;
                    int sensorOffset = readCharacteristic(characteristics, CameraCharacteristics.SENSOR_ORIENTATION, 0);
                    getAngles().setSensorOffset(facing, sensorOffset);
                    return true;
                }
            } catch (CameraAccessException ignore) {
                // This specific camera has been disconnected.
                // Keep searching in other camerIds.
            }
        }
        return false;
    }

    //endregion

    //region Start

    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    protected Task<Void> onStartEngine() {
        final TaskCompletionSource<Void> task = new TaskCompletionSource<>();
        try {
            // We have a valid camera for this Facing. Go on.
            mManager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCamera = camera;

                    // Set parameters that might have been set before the camera was opened.
                    try {
                        LOG.i("createCamera:", "Applying default parameters.");
                        mCameraCharacteristics = mManager.getCameraCharacteristics(mCameraId);
                        boolean flip = getAngles().flip(Reference.SENSOR, Reference.VIEW);
                        mCameraOptions = new CameraOptions(mManager, mCameraId, flip);
                        createRepeatingRequestBuilder(CameraDevice.TEMPLATE_PREVIEW);
                    } catch (CameraAccessException e) {
                        task.trySetException(createCameraException(e));
                        return;
                    }
                    task.trySetResult(null);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    // Not sure if this is called INSTEAD of onOpened() or can be called after as well.
                    // However, using trySetException should address this problem - it will only trigger
                    // if the task has no result.
                    //
                    // Docs say to release this camera instance, however, since we throw an unrecoverable CameraException,
                    // this will trigger a stop() through the exception handler.
                    task.trySetException(new CameraException(CameraException.REASON_DISCONNECTED));
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    task.trySetException(createCameraException(error));
                }
            }, null);
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
        return task.getTask();
    }

    @NonNull
    @Override
    protected Task<Void> onStartBind() {
        LOG.i("onStartBind:", "Started");
        final TaskCompletionSource<Void> task = new TaskCompletionSource<>();

        // Compute sizes.
        mCaptureSize = computeCaptureSize();
        mPreviewStreamSize = computePreviewStreamSize();

        // Deal with surfaces.
        // In Camera2, instead of applying the size to the camera params object,
        // we must resize our own surfaces and configure them before opening the session.
        List<Surface> outputSurfaces = new ArrayList<>();

        // 1. PREVIEW
        // Create a preview surface with the correct size.
        final Object output = mPreview.getOutput();
        if (output instanceof SurfaceHolder) {
            try {
                // This must be called from the UI thread...
                Tasks.await(Tasks.call(new Callable<Void>() {
                    @Override
                    public Void call() {
                        ((SurfaceHolder) output).setFixedSize(
                                mPreviewStreamSize.getWidth(),
                                mPreviewStreamSize.getHeight());
                        return null;
                    }
                }));
            } catch (ExecutionException | InterruptedException e) {
                throw new CameraException(e, CameraException.REASON_FAILED_TO_CONNECT);
            }
            mPreviewStreamSurface = ((SurfaceHolder) output).getSurface();
        } else if (output instanceof SurfaceTexture) {
            ((SurfaceTexture) output).setDefaultBufferSize(
                    mPreviewStreamSize.getWidth(),
                    mPreviewStreamSize.getHeight());
            mPreviewStreamSurface = new Surface((SurfaceTexture) output);
        } else {
            throw new RuntimeException("Unknown CameraPreview output class.");
        }
        outputSurfaces.add(mPreviewStreamSurface);

        // 2. VIDEO RECORDING
        if (getMode() == Mode.VIDEO) {
            if (mFullVideoPendingStub != null) {
                Full2VideoRecorder recorder = new Full2VideoRecorder(this, mCameraId);
                try {
                    outputSurfaces.add(recorder.createInputSurface(mFullVideoPendingStub));
                } catch (Full2VideoRecorder.PrepareException e) {
                    throw new CameraException(e, CameraException.REASON_FAILED_TO_CONNECT);
                }
                mVideoRecorder = recorder;
            }
        }

        // 3. PICTURE RECORDING
        if (getMode() == Mode.PICTURE) {
            mPictureReader = ImageReader.newInstance(
                    mCaptureSize.getWidth(),
                    mCaptureSize.getHeight(),
                    ImageFormat.JPEG,
                    2
            );
            outputSurfaces.add(mPictureReader.getSurface());
        }

        // 4. FRAME PROCESSING
        if (hasFrameProcessors()) {
            // Choose the size.
            StreamConfigurationMap streamMap = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streamMap == null) throw new RuntimeException("StreamConfigurationMap is null. Should not happen.");
            android.util.Size[] aSizes = streamMap.getOutputSizes(FRAME_PROCESSING_INPUT_FORMAT);
            List<Size> sizes = new ArrayList<>();
            for (android.util.Size aSize : aSizes) {
                sizes.add(new Size(aSize.getWidth(), aSize.getHeight()));
            }
            mFrameProcessingSize = SizeSelectors.and(
                    SizeSelectors.maxWidth(Math.min(700, mPreviewStreamSize.getWidth())),
                    SizeSelectors.maxHeight(Math.min(700, mPreviewStreamSize.getHeight())),
                    SizeSelectors.biggest()).select(sizes).get(0);
            mFrameProcessingReader = ImageReader.newInstance(
                    mFrameProcessingSize.getWidth(),
                    mFrameProcessingSize.getHeight(),
                    FRAME_PROCESSING_INPUT_FORMAT,
                    2);
            mFrameProcessingReader.setOnImageAvailableListener(this, mFrameConversionHandler.getHandler());
            mFrameProcessingSurface = mFrameProcessingReader.getSurface();
            outputSurfaces.add(mFrameProcessingSurface);
        } else {
            mFrameProcessingReader = null;
            mFrameProcessingSize = null;
            mFrameProcessingSurface = null;
        }

        try {
            // null handler means using the current looper which is totally ok.
            mCamera.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mSession = session;
                    LOG.i("onStartBind:", "Completed");
                    task.trySetResult(null);
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    // I would say this should be a library error and as such we throw a Runtime Exception.
                    String message = LOG.e("onConfigureFailed! Session", session);
                    throw new RuntimeException(message);
                }
            }, null);
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
        return task.getTask();
    }

    @NonNull
    @Override
    protected Task<Void> onStartPreview() {
        LOG.i("onStartPreview", "Dispatching onCameraPreviewStreamSizeChanged.");
        mCallback.onCameraPreviewStreamSizeChanged();

        Size previewSizeForView = getPreviewStreamSize(Reference.VIEW);
        if (previewSizeForView == null) {
            throw new IllegalStateException("previewStreamSize should not be null at this point.");
        }
        mPreview.setStreamSize(previewSizeForView.getWidth(), previewSizeForView.getHeight());
        mPreview.setDrawRotation(getAngles().offset(Reference.BASE, Reference.VIEW, Axis.ABSOLUTE));
        if (hasFrameProcessors()) {
            getFrameManager().setUp(FRAME_PROCESSING_FORMAT, mFrameProcessingSize);
        }

        LOG.i("onStartPreview", "Starting preview.");
        addRepeatingRequestBuilderSurfaces();
        applyRepeatingRequestBuilder(false, CameraException.REASON_FAILED_TO_START_PREVIEW, null);
        LOG.i("onStartPreview", "Started preview.");

        // Start delayed video if needed.
        if (mFullVideoPendingStub != null) {
            // Do not call takeVideo/onTakeVideo. It will reset some stub parameters that the recorder sets.
            // Also we are posting this so that doTakeVideo sees a started preview.
            LOG.i("onStartPreview", "Posting doTakeVideo call.");
            final VideoResult.Stub stub = mFullVideoPendingStub;
            mFullVideoPendingStub = null;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    LOG.i("onStartPreview", "Executing doTakeVideo call.");
                    doTakeVideo(stub);
                }
            });
        }
        return Tasks.forResult(null);
    }

    //endregion

    //region Stop

    @NonNull
    @Override
    protected Task<Void> onStopPreview() {
        LOG.i("onStopPreview:", "About to clean up.");
        if (mVideoRecorder != null) {
            // This should synchronously call onVideoResult that will reset the repeating builder
            // to the PREVIEW template. This is very important.
            mVideoRecorder.stop(true);
            mVideoRecorder = null;
        }
        mPictureRecorder = null;
        if (hasFrameProcessors()) {
            getFrameManager().release();
        }
        try {
            // NOTE: should we wait for onReady() like docs say?
            // Leaving this synchronous for now.
            mSession.stopRepeating();
        } catch (CameraAccessException e) {
            // This tells us that we should stop everything. It's better to throw an unrecoverable
            // exception rather than just swallow this, so everything gets stopped.
            LOG.w("stopRepeating failed!", e);
            throw createCameraException(e);
        }
        removeRepeatingRequestBuilderSurfaces();
        mRepeatingRequest = null;
        mMeter = null;
        LOG.i("onStopPreview:", "Returning.");
        return Tasks.forResult(null);
    }


    @NonNull
    @Override
    protected Task<Void> onStopBind() {
        LOG.i("onStopBind:", "About to clean up.");
        mFrameProcessingSurface = null;
        mPreviewStreamSurface = null;
        mPreviewStreamSize = null;
        mCaptureSize = null;
        mFrameProcessingSize = null;
        if (mFrameProcessingReader != null) {
            mFrameProcessingReader.close();
            mFrameProcessingReader = null;
        }
        if (mPictureReader != null) {
            mPictureReader.close();
            mPictureReader = null;
        }
        mSession.close();
        mSession = null;
        LOG.i("onStopBind:", "Returning.");
        return Tasks.forResult(null);
    }


    @NonNull
    @Override
    protected Task<Void> onStopEngine() {
        LOG.i("onStopEngine:", "About to clean up.");
        try {
            LOG.i("onStopEngine:", "Clean up.", "Releasing camera.");
            mCamera.close();
            LOG.i("onStopEngine:", "Clean up.", "Released camera.");
        } catch (Exception e) {
            LOG.w("onStopEngine:", "Clean up.", "Exception while releasing camera.", e);
        }
        mCamera = null;
        mCameraOptions = null;
        mVideoRecorder = null;
        mRepeatingRequestBuilder = null;
        LOG.w("onStopEngine:", "Returning.");
        return Tasks.forResult(null);
    }

    //endregion

    //region Pictures

    @WorkerThread
    @Override
    protected void onTakePictureSnapshot(@NonNull PictureResult.Stub stub, @NonNull AspectRatio outputRatio) {
        stub.size = getUncroppedSnapshotSize(Reference.OUTPUT); // Not the real size: it will be cropped to match the view ratio
        stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT, Axis.RELATIVE_TO_SENSOR); // Actually it will be rotated and set to 0.
        if (mPreview instanceof GlCameraPreview) {
            mPictureRecorder = new SnapshotGlPictureRecorder(stub, this, (GlCameraPreview) mPreview, outputRatio, getOverlay());
        } else {
            throw new RuntimeException("takePictureSnapshot with Camera2 is only supported with Preview.GL_SURFACE");
        }
        mPictureRecorder.take();
    }

    @Override
    protected void onTakePicture(@NonNull PictureResult.Stub stub) {
        stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT, Axis.RELATIVE_TO_SENSOR);
        stub.size = getPictureSize(Reference.OUTPUT);
        try {
            CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            applyAllParameters(builder);
            mPictureRecorder = new Full2PictureRecorder(stub, this,
                    mCameraCharacteristics,
                    mSession,
                    mRepeatingRequestBuilder,
                    mRepeatingRequestCallback,
                    builder,
                    mPictureReader,
                    mPictureCaptureStopsPreview);
            mPictureRecorder.take();
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
    }

    @Override
    public void onPictureResult(@Nullable PictureResult.Stub result, @Nullable Exception error) {
        boolean fullPicture = mPictureRecorder instanceof Full2PictureRecorder;
        super.onPictureResult(result, error);
        if (fullPicture && mPictureCaptureStopsPreview) {
            // See comments in Full2PictureRecorder.
            applyRepeatingRequestBuilder();
        }
    }

    //endregion

    //region Videos

    @WorkerThread
    @Override
    protected void onTakeVideo(@NonNull VideoResult.Stub stub) {
        LOG.i("onTakeVideo", "called.");
        stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT, Axis.RELATIVE_TO_SENSOR);
        stub.size = getAngles().flip(Reference.SENSOR, Reference.OUTPUT) ? mCaptureSize.flip() : mCaptureSize;
        // We must restart the session at each time.
        // Save the pending data and restart the session.
        LOG.w("onTakeVideo", "calling restartBind.");
        mFullVideoPendingStub = stub;
        restartBind();
    }

    private void doTakeVideo(@NonNull final VideoResult.Stub stub) {
        if (!(mVideoRecorder instanceof Full2VideoRecorder)) {
            throw new IllegalStateException("doTakeVideo called, but video recorder is not a Full2VideoRecorder! " + mVideoRecorder);
        }
        Full2VideoRecorder recorder = (Full2VideoRecorder) mVideoRecorder;
        try {
            createRepeatingRequestBuilder(CameraDevice.TEMPLATE_RECORD);
            addRepeatingRequestBuilderSurfaces(recorder.getInputSurface());
            applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED, new Runnable() {
                @Override
                public void run() {
                    mVideoRecorder.start(stub);
                }
            });
        } catch (CameraAccessException e) {
            onVideoResult(null, e);
            throw createCameraException(e);
        } catch (CameraException e) {
            onVideoResult(null, e);
            throw e;
        }
    }

    @WorkerThread
    @Override
    protected void onTakeVideoSnapshot(@NonNull VideoResult.Stub stub, @NonNull AspectRatio outputRatio) {
        if (!(mPreview instanceof GlCameraPreview)) {
            throw new IllegalStateException("Video snapshots are only supported with GlCameraPreview.");
        }
        GlCameraPreview glPreview = (GlCameraPreview) mPreview;
        Size outputSize = getUncroppedSnapshotSize(Reference.OUTPUT);
        if (outputSize == null) {
            throw new IllegalStateException("outputSize should not be null.");
        }
        Rect outputCrop = CropHelper.computeCrop(outputSize, outputRatio);
        outputSize = new Size(outputCrop.width(), outputCrop.height());
        stub.size = outputSize;
        // Vertical:               0   (270-0-0)
        // Left (unlocked):        270 (270-90-270)
        // Right (unlocked):       90  (270-270-90)
        // Upside down (unlocked): 180 (270-180-180)
        // Left (locked):          270 (270-0-270)
        // Right (locked):         90  (270-0-90)
        // Upside down (locked):   180 (270-0-180)
        // Unlike Camera1, the correct formula seems to be deviceOrientation,
        // which means offset(Reference.BASE, Reference.OUTPUT, Axis.ABSOLUTE).
        stub.rotation = getAngles().offset(Reference.BASE, Reference.OUTPUT, Axis.ABSOLUTE);
        LOG.i("onTakeVideoSnapshot", "rotation:", stub.rotation, "size:", stub.size);

        // Start.
        // The overlay rotation should alway be VIEW-OUTPUT, just liek Camera1Engine.
        int overlayRotation = getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE);
        mVideoRecorder = new SnapshotVideoRecorder(this, glPreview, getOverlay(), overlayRotation);
        mVideoRecorder.start(stub);
    }

    @Override
    public void onVideoRecordingEnd() {
        super.onVideoRecordingEnd();
        // When video ends we must stop the recorder and remove the recorder surface from camera outputs.
        // This is done in onVideoResult. However, on some devices, order matters. If we stop the recorder
        // and AFTER send camera frames to it, the camera will try to fill the recorder "abandoned"
        // Surface and on some devices with a poor internal implementation (HW_LEVEL_LEGACY) this crashes.
        // So if the conditions are met, we restore here. Issue #549.
        boolean needsIssue549Workaround = (mVideoRecorder instanceof Full2VideoRecorder) ||
                (readCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, -1)
                        == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
        if (needsIssue549Workaround) {
            maybeRestorePreviewTemplateAfterVideo();
        }
    }

    @Override
    public void onVideoResult(@Nullable VideoResult.Stub result, @Nullable Exception exception) {
        super.onVideoResult(result, exception);
        maybeRestorePreviewTemplateAfterVideo();
    }

    /**
     * Some video recorders might change the camera template to {@link CameraDevice#TEMPLATE_RECORD}.
     * After the video is taken, we should restore the template preview, which also means that
     * we'll remove any extra surface target that was added by the video recorder.
     *
     * This method avoids doing this twice by checking the request tag, as set by
     * the {@link #createRepeatingRequestBuilder(int)} method.
     */
    private void maybeRestorePreviewTemplateAfterVideo() {
        int template = (int) mRepeatingRequest.getTag();
        if (template != CameraDevice.TEMPLATE_PREVIEW) {
            try {
                createRepeatingRequestBuilder(CameraDevice.TEMPLATE_PREVIEW);
                addRepeatingRequestBuilderSurfaces();
                applyRepeatingRequestBuilder();
            } catch (CameraAccessException e) {
                throw createCameraException(e);
            }
        }
    }

    //endregion

    //region Parameters

    private void applyAllParameters(@NonNull CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        applyDefaultFocus(builder);
        applyFlash(builder, Flash.OFF);
        applyLocation(builder, null);
        applyWhiteBalance(builder, WhiteBalance.AUTO);
        applyHdr(builder, Hdr.OFF);
        applyZoom(builder, 0F);
        applyExposureCorrection(builder, 0F);
    }

    private void applyDefaultFocus(@NonNull CaptureRequest.Builder builder) {
        int[] modesArray = readCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, new int[]{});
        List<Integer> modes = new ArrayList<>();
        for (int mode : modesArray) { modes.add(mode); }
        if (getMode() == Mode.VIDEO &&
                modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0F);
            //noinspection UnnecessaryReturnStatement
            return;
        }
    }

    private void applyFocusForMetering(@NonNull CaptureRequest.Builder builder) {
        // All focus modes support the AF trigger, except OFF and EDOF.
        // However, unlike the preview, we'd prefer AUTO to any CONTINUOUS value.
        int[] modesArray = readCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, new int[]{});
        List<Integer> modes = new ArrayList<>();
        for (int mode : modesArray) { modes.add(mode); }
        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            return;
        }
        if (getMode() == Mode.VIDEO &&
                modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            //noinspection UnnecessaryReturnStatement
            return;
        }
    }

    @Override
    public void setFlash(@NonNull Flash flash) {
        final Flash old = mFlash;
        mFlash = flash;
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                if (getEngineState() == STATE_STARTED) {
                    if (applyFlash(mRepeatingRequestBuilder, old)) {
                        applyRepeatingRequestBuilder();
                    }
                }
                mFlashOp.end(null);
            }
        });
    }

    /**
     * This sets the CONTROL_AE_MODE to either:
     * - {@link CaptureRequest#CONTROL_AE_MODE_ON}
     * - {@link CaptureRequest#CONTROL_AE_MODE_ON_AUTO_FLASH}
     * - {@link CaptureRequest#CONTROL_AE_MODE_ON_ALWAYS_FLASH}
     *
     * The API offers a high level control through {@link CaptureRequest#CONTROL_AE_MODE},
     * which is what the mapper looks at. It will trigger (if specified) flash only for still captures
     * which is exactly what we want.
     *
     * However, we set CONTROL_AE_MODE to ON/OFF (depending
     * on which is available) with both {@link Flash#OFF} and {@link Flash#TORCH}.
     *
     * When CONTROL_AE_MODE is ON or OFF, the low level control, called {@link CaptureRequest#FLASH_MODE},
     * becomes effective, and that's where we can actually distinguish between a turned off flash
     * and a torch flash.
     */
    private boolean applyFlash(@NonNull CaptureRequest.Builder builder,
                               @NonNull Flash oldFlash) {
        if (mCameraOptions.supports(mFlash)) {
            int[] availableAeModesArray = readCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, new int[]{});
            List<Integer> availableAeModes = new ArrayList<>();
            for (int mode : availableAeModesArray) { availableAeModes.add(mode); }

            List<Pair<Integer, Integer>> pairs = mMapper.mapFlash(mFlash);
            for (Pair<Integer, Integer> pair : pairs) {
                if (availableAeModes.contains(pair.first)) {
                    LOG.i("applyFlash: setting CONTROL_AE_MODE to", pair.first);
                    LOG.i("applyFlash: setting FLASH_MODE to", pair.second);
                    builder.set(CaptureRequest.CONTROL_AE_MODE, pair.first);
                    builder.set(CaptureRequest.FLASH_MODE, pair.second);

                    // On some devices, switching from TORCH/OFF to AUTO/ON is not immediately
                    // reflected (for example, torch stays active) unless we do as follows.
                    // It's just a way to wake up the AE routine.
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                    return true;
                }
            }
        }
        mFlash = oldFlash;
        return false;
    }

    @Override
    public void setLocation(@Nullable Location location) {
        final Location old = mLocation;
        mLocation = location;
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                if (getEngineState() == STATE_STARTED) {
                    if (applyLocation(mRepeatingRequestBuilder, old)) {
                        applyRepeatingRequestBuilder();
                    }
                }
                mLocationOp.end(null);
            }
        });
    }

    private boolean applyLocation(@NonNull CaptureRequest.Builder builder,
                                  @SuppressWarnings("unused") @Nullable Location oldLocation) {
        if (mLocation != null) {
            builder.set(CaptureRequest.JPEG_GPS_LOCATION, mLocation);
        }
        return true;
    }

    @Override
    public void setWhiteBalance(@NonNull WhiteBalance whiteBalance) {
        final WhiteBalance old = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                if (getEngineState() == STATE_STARTED) {
                    if (applyWhiteBalance(mRepeatingRequestBuilder, old)) {
                        applyRepeatingRequestBuilder();
                    }
                }
                mWhiteBalanceOp.end(null);
            }
        });
    }

    private boolean applyWhiteBalance(@NonNull CaptureRequest.Builder builder,
                                      @NonNull WhiteBalance oldWhiteBalance) {
        if (mCameraOptions.supports(mWhiteBalance)) {
            int whiteBalance = mMapper.mapWhiteBalance(mWhiteBalance);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalance);
            return true;
        }
        mWhiteBalance = oldWhiteBalance;
        return false;
    }

    @Override
    public void setHdr(@NonNull Hdr hdr) {
        final Hdr old = mHdr;
        mHdr = hdr;
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                if (getEngineState() == STATE_STARTED) {
                    if (applyHdr(mRepeatingRequestBuilder, old)) {
                        applyRepeatingRequestBuilder();
                    }
                }
                mHdrOp.end(null);
            }
        });
    }


    private boolean applyHdr(@NonNull CaptureRequest.Builder builder, @NonNull Hdr oldHdr) {
        if (mCameraOptions.supports(mHdr)) {
            int hdr = mMapper.mapHdr(mHdr);
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, hdr);
            return true;
        }
        mHdr = oldHdr;
        return false;
    }

    @Override
    public void setZoom(final float zoom, final @Nullable PointF[] points, final boolean notify) {
        final float old = mZoomValue;
        mZoomValue = zoom;
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                if (getEngineState() == STATE_STARTED) {
                    if (applyZoom(mRepeatingRequestBuilder, old)) {
                        applyRepeatingRequestBuilder();
                        if (notify) {
                            mCallback.dispatchOnZoomChanged(zoom, points);
                        }
                    }
                }
                mZoomOp.end(null);
            }
        });
    }

    private boolean applyZoom(@NonNull CaptureRequest.Builder builder, float oldZoom) {
        if (mCameraOptions.isZoomSupported()) {
            float maxZoom = readCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1F);
            // converting 0.0f-1.0f zoom scale to the actual camera digital zoom scale
            // (which will be for example, 1.0-10.0)
            float calculatedZoom = (mZoomValue * (maxZoom - 1.0f)) + 1.0f;
            Rect newRect = getZoomRect(calculatedZoom, maxZoom);
            builder.set(CaptureRequest.SCALER_CROP_REGION, newRect);
            return true;
        }
        mZoomValue = oldZoom;
        return false;
    }

    @NonNull
    private Rect getZoomRect(float zoomLevel, float maxDigitalZoom) {
        Rect activeRect = readCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect());

        int minW = (int) (activeRect.width() / maxDigitalZoom);
        int minH = (int) (activeRect.height() / maxDigitalZoom);
        int difW = activeRect.width() - minW;
        int difH = activeRect.height() - minH;

        // When zoom is 1, we want to return new Rect(0, 0, width, height).
        // When zoom is maxZoom, we want to return a centered rect with minW and minH
        int cropW = (int) (difW * (zoomLevel - 1) / (maxDigitalZoom - 1) / 2F);
        int cropH = (int) (difH * (zoomLevel - 1) / (maxDigitalZoom - 1) / 2F);
        return new Rect(cropW, cropH, activeRect.width() - cropW, activeRect.height() - cropH);
    }

    @Override
    public void setExposureCorrection(final float EVvalue, @NonNull final float[] bounds, @Nullable final PointF[] points, final boolean notify) {
        final float old = mExposureCorrectionValue;
        mExposureCorrectionValue = EVvalue;
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                if (getEngineState() == STATE_STARTED) {
                    if (applyExposureCorrection(mRepeatingRequestBuilder, old)) {
                        applyRepeatingRequestBuilder();
                        if (notify) {
                            mCallback.dispatchOnExposureCorrectionChanged(EVvalue, bounds, points);
                        }
                    }
                }
                mExposureCorrectionOp.end(null);
            }
        });
    }

    private boolean applyExposureCorrection(@NonNull CaptureRequest.Builder builder, float oldEVvalue) {
        if (mCameraOptions.isExposureCorrectionSupported()) {
            Rational exposureCorrectionStep = readCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                    new Rational(1, 1));
            int exposureCorrectionSteps = Math.round(mExposureCorrectionValue * exposureCorrectionStep.floatValue());
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCorrectionSteps);
            return true;
        }
        mExposureCorrectionValue = oldEVvalue;
        return false;
    }

    @Override
    public void setPlaySounds(boolean playSounds) {
        mPlaySounds = playSounds;
        mPlaySoundsOp.end(null);
    }

    //endregion

    //region Frame Processing

    @NonNull
    @Override
    protected FrameManager instantiateFrameManager() {
        return new FrameManager(2, null);
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        byte[] data = getFrameManager().getBuffer();
        if (data == null) {
            LOG.w("onImageAvailable", "no byte buffer!");
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException ignore) { }
        if (image == null) {
            LOG.w("onImageAvailable", "we have a byte buffer but no Image!");
            getFrameManager().onBufferUnused(data);
            return;
        }
        LOG.i("onImageAvailable", "we have both a byte buffer and an Image.");
        try {
            ImageHelper.convertToNV21(image, data);
        } catch (Exception e) {
            LOG.w("onImageAvailable", "error while converting.");
            getFrameManager().onBufferUnused(data);
            image.close();
            return;
        }
        image.close();
        if (getPreviewState() == STATE_STARTED) {
            // After preview, the frame manager is correctly set up
            Frame frame = getFrameManager().getFrame(data,
                    System.currentTimeMillis(),
                    getAngles().offset(Reference.SENSOR, Reference.OUTPUT, Axis.RELATIVE_TO_SENSOR));
            mCallback.dispatchFrame(frame);
        } else {
            getFrameManager().onBufferUnused(data);
        }
    }

    @Override
    public void setHasFrameProcessors(final boolean hasFrameProcessors) {
        super.setHasFrameProcessors(hasFrameProcessors);
        LOG.i("setHasFrameProcessors", "changed to", hasFrameProcessors, "posting.");
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                LOG.i("setHasFrameProcessors", "changed to", hasFrameProcessors, "executing. BindState:", getBindState());
                if (getBindState() == STATE_STARTED) {
                    LOG.i("setHasFrameProcessors", "triggering a restart.");
                    // TODO if taking video, this stops it.
                    restartBind();
                } else {
                    LOG.i("setHasFrameProcessors", "not bound so won't restart.");
                }
            }
        });
    }

    //endregion

    //region 3A Metering

    @Override
    public void startAutoFocus(@Nullable final Gesture gesture, @NonNull final PointF point) {
        // TODO Should change this name at some point, and deprecate AF methods
        startMetering(gesture, point);
    }

    private void startMetering(@Nullable final Gesture gesture, @NonNull final PointF point) {
        LOG.i("startMetering", "dispatching. Gesture:", gesture);
        mHandler.run(new Runnable() {
            @Override
            public void run() {
                LOG.i("startMetering", "executing. Preview state:", getPreviewState());
                // This will only work when we have a preview, since it launches the preview in the end.
                // Even without this it would need the bind state at least, since we need the preview size.
                if (getPreviewState() < STATE_STARTED) return;

                // The camera options API still has the auto focus API but it really
                // refers to 3A metering.
                if (!mCameraOptions.isAutoFocusSupported()) return;

                // Reset the old meter if present.
                if (mMeter != null) {
                    mMeter.resetMetering();
                }

                // The meter will check the current configuration to see if AF/AE/AWB should run.
                // - AE should be on CONTROL_AE_MODE_ON*    (this depends on setFlash())
                // - AWB should be on CONTROL_AWB_MODE_AUTO (this depends on setWhiteBalance())
                // - AF should be on CONTROL_AF_MODE_AUTO or others
                // The last one is under our control because the library has no focus API.
                // So let's set a good af mode here. This operation is reverted during onMeteringReset().
                applyFocusForMetering(mRepeatingRequestBuilder);

                // Create the meter and start.
                mMeter = new Meter(Camera2Engine.this,
                        mRepeatingRequestBuilder,
                        mCameraCharacteristics,
                        Camera2Engine.this);
                mMeter.startMetering(mLastRepeatingResult, point, gesture);
            }
        });
    }

    /**
     * Called by {@link Meter} when the metering process has started.
     * We are currently exposing an auto focus API so that's what we dispatch.
     * @param point point
     * @param gesture gesture
     */
    @Override
    public void onMeteringStarted(@Nullable PointF point, @Nullable Gesture gesture) {
        LOG.w("onMeteringStarted - point:", point, "gesture:", gesture);
        if (point != null) {
            mCallback.dispatchOnFocusStart(gesture, point);
        }
        applyRepeatingRequestBuilder();
    }

    /**
     * Called by {@link Meter} when the metering process has ended.
     * We are currently exposing an auto focus API so that's what we dispatch.
     * @param point point
     * @param gesture gesture
     * @param success success
     */
    @Override
    public void onMeteringEnd(@Nullable PointF point, @Nullable Gesture gesture, boolean success) {
        LOG.w("onMeteringEnd - point:", point, "gesture:", gesture, "success:", success);
        if (point != null) {
            mCallback.dispatchOnFocusEnd(gesture, success, point);
        }
    }

    /**
     * When metering is reset, we're not sure that the engine is still alive.
     * We should check this here.
     * @param point point
     * @param gesture gesture
     * @return true if metering can be reset
     */
    @Override
    public boolean canResetMetering(@Nullable PointF point, @Nullable Gesture gesture) {
        return getEngineState() == STATE_STARTED;
    }

    /**
     * Called by {@link Meter} after resetting the metering parameters.
     * We should apply them, and also go back to default focus.
     * @param point point
     * @param gesture gesture
     */
    @Override
    public void onMeteringReset(@Nullable PointF point, @Nullable Gesture gesture) {
        applyDefaultFocus(mRepeatingRequestBuilder);
        applyRepeatingRequestBuilder(); // only if preview started already
    }

    //endregion
}