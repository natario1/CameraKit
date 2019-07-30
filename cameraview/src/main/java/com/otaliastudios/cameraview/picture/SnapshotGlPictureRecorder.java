package com.otaliastudios.cameraview.picture;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.Matrix;
import android.os.Build;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.internal.Issue514Workaround;
import com.otaliastudios.cameraview.internal.egl.EglBaseSurface;
import com.otaliastudios.cameraview.overlay.Overlay;
import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.engine.CameraEngine;
import com.otaliastudios.cameraview.engine.offset.Axis;
import com.otaliastudios.cameraview.engine.offset.Reference;
import com.otaliastudios.cameraview.internal.egl.EglCore;
import com.otaliastudios.cameraview.internal.egl.EglViewport;
import com.otaliastudios.cameraview.internal.egl.EglWindowSurface;
import com.otaliastudios.cameraview.internal.utils.CropHelper;
import com.otaliastudios.cameraview.internal.utils.WorkerHandler;
import com.otaliastudios.cameraview.preview.GlCameraPreview;
import com.otaliastudios.cameraview.preview.RendererFrameCallback;
import com.otaliastudios.cameraview.preview.RendererThread;
import com.otaliastudios.cameraview.size.AspectRatio;
import com.otaliastudios.cameraview.size.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.view.Surface;

/**
 * API 19.
 * Records a picture snapshots from the {@link GlCameraPreview}. It works as follows:
 *
 * - We register a one time {@link RendererFrameCallback} on the preview
 * - We get the textureId and the frame callback on the {@link RendererThread}
 * - [Optional: we construct another textureId for overlays]
 * - We take a handle of the EGL context from the {@link RendererThread}
 * - We move to another thread, and create a new EGL surface for that EGL context.
 * - We make this new surface current, and re-draw the textureId on it
 * - [Optional: fill the overlayTextureId and draw it on the same surface]
 * - We use glReadPixels (through {@link EglBaseSurface#saveFrameTo(Bitmap.CompressFormat)}) and save to file.
 *
 * We create a new EGL surface and redraw the frame because:
 * 1. We want to go off the renderer thread as soon as possible
 * 2. We have overlays to be drawn - we don't want to draw them on the preview surface, not even for a frame.
 */
public class SnapshotGlPictureRecorder extends PictureRecorder {

    private static final String TAG = SnapshotGlPictureRecorder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private CameraEngine mEngine;
    private GlCameraPreview mPreview;
    private AspectRatio mOutputRatio;

    private Overlay mOverlay;
    private boolean mHasOverlay;

    private int mTextureId;
    private float[] mTransform;

    private int mOverlayTextureId = 0;
    private SurfaceTexture mOverlaySurfaceTexture;
    private Surface mOverlaySurface;
    private float[] mOverlayTransform;

    private EglViewport mViewport;

    public SnapshotGlPictureRecorder(
            @NonNull PictureResult.Stub stub,
            @NonNull CameraEngine engine,
            @NonNull GlCameraPreview preview,
            @NonNull AspectRatio outputRatio,
            @Nullable Overlay overlay) {
        super(stub, engine);
        mEngine = engine;
        mPreview = preview;
        mOutputRatio = outputRatio;
        mOverlay = overlay;
        mHasOverlay = overlay != null && overlay.drawsOn(Overlay.Target.PICTURE_SNAPSHOT);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void take() {
        mPreview.addRendererFrameCallback(new RendererFrameCallback() {

            @RendererThread
            public void onRendererTextureCreated(int textureId) {
                SnapshotGlPictureRecorder.this.onRendererTextureCreated(textureId);
            }

            @RendererThread
            @Override
            public void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, final float scaleX, final float scaleY) {
                mPreview.removeRendererFrameCallback(this);
                SnapshotGlPictureRecorder.this.onRendererFrame(surfaceTexture, scaleX, scaleY);
            }
        });
    }

    @RendererThread
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void onRendererTextureCreated(int textureId) {
        mTextureId = textureId;
        mViewport = new EglViewport();
        // Need to crop the size.
        Rect crop = CropHelper.computeCrop(mResult.size, mOutputRatio);
        mResult.size = new Size(crop.width(), crop.height());
        mTransform = new float[16];
        Matrix.setIdentityM(mTransform, 0);

        if (mHasOverlay) {
            mOverlayTextureId = mViewport.createTexture();
            mOverlaySurfaceTexture = new SurfaceTexture(mOverlayTextureId, true);
            mOverlaySurfaceTexture.setDefaultBufferSize(mResult.size.getWidth(), mResult.size.getHeight());
            mOverlaySurface = new Surface(mOverlaySurfaceTexture);
            mOverlayTransform = new float[16];
        }
    }

    /**
     * The tricky part here is the EGL surface creation.
     *
     * We don't have a real output window for the EGL surface - we will use glReadPixels()
     * and never call swapBuffers(), so what we draw is never published.
     *
     * 1. One option is to use a pbuffer EGL surface. This works, we just have to pass
     *    the correct width and height. However, it is significantly slower than the current
     *    solution.
     *
     * 2. Another option is to create the EGL surface out of a ImageReader.getSurface()
     *    and use the reader to create a JPEG. In this case, we would have to publish
     *    the frame with swapBuffers(). However, currently ImageReader does not support
     *    all formats, it's risky. This is an example error that we get:
     *    "RGBA override BLOB format buffer should have height == width"
     *
     * The third option, which we are using, is to create the EGL surface using whatever
     * {@link Surface} or {@link SurfaceTexture} we have at hand. Since we never call
     * swapBuffers(), the frame will not actually be rendered. This is the fastest.
     *
     * @param scaleX frame scale x in {@link Reference#VIEW}
     * @param scaleY frame scale y in {@link Reference#VIEW}
     */
    @RendererThread
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void onRendererFrame(final @NonNull SurfaceTexture surfaceTexture, final float scaleX, final float scaleY) {
        // Get egl context from the RendererThread, which is the one in which we have created
        // the textureId and the overlayTextureId, managed by the GlSurfaceView.
        // Next operations can then be performed on different threads using this handle.
        final EGLContext eglContext = EGL14.eglGetCurrentContext();
        // Calling this invalidates the rotation/scale logic below:
        // surfaceTexture.getTransformMatrix(mTransform); // TODO activate and fix the logic.
        WorkerHandler.execute(new Runnable() {
            @Override
            public void run() {
                // 0. EGL window will need an output.
                // We create a fake one as explained in javadocs.
                final int fakeOutputTextureId = 9999;
                SurfaceTexture fakeOutputSurface = new SurfaceTexture(fakeOutputTextureId);
                fakeOutputSurface.setDefaultBufferSize(mResult.size.getWidth(), mResult.size.getHeight());
                final Issue514Workaround issue514Workaround = new Issue514Workaround(mTextureId, mResult.size);

                // 1. Create an EGL surface
                final EglCore core = new EglCore(eglContext, EglCore.FLAG_RECORDABLE);
                final EglBaseSurface eglSurface = new EglWindowSurface(core, fakeOutputSurface);
                eglSurface.makeCurrent();
                issue514Workaround.onStart();

                // 2. Apply scale and crop
                boolean flip = mEngine.getAngles().flip(Reference.VIEW, Reference.SENSOR);
                float realScaleX = flip ? scaleY : scaleX;
                float realScaleY = flip ? scaleX : scaleY;
                float scaleTranslX = (1F - realScaleX) / 2F;
                float scaleTranslY = (1F - realScaleY) / 2F;
                Matrix.translateM(mTransform, 0, scaleTranslX, scaleTranslY, 0);
                Matrix.scaleM(mTransform, 0, realScaleX, realScaleY, 1);

                // 3. Apply rotation and flip
                Matrix.translateM(mTransform, 0, 0.5F, 0.5F, 0); // Go back to 0,0
                Matrix.rotateM(mTransform, 0, -mResult.rotation, 0, 0, 1); // Rotate (not sure why we need the minus)
                mResult.rotation = 0;
                if (mResult.facing == Facing.FRONT) { // 5. Flip horizontally for front camera
                    Matrix.scaleM(mTransform, 0, -1, 1, 1);
                }
                Matrix.translateM(mTransform, 0, -0.5F, -0.5F, 0); // Go back to old position

                // 4. Do pretty much the same for overlays
                if (mHasOverlay) {
                    // 1. First we must draw on the texture and get latest image
                    try {
                        final Canvas surfaceCanvas = mOverlaySurface.lockCanvas(null);
                        surfaceCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        mOverlay.drawOn(Overlay.Target.PICTURE_SNAPSHOT, surfaceCanvas);
                        mOverlaySurface.unlockCanvasAndPost(surfaceCanvas);
                    } catch (Surface.OutOfResourcesException e) {
                        LOG.w("Got Surface.OutOfResourcesException while drawing picture overlays", e);
                    }
                    mOverlaySurfaceTexture.updateTexImage();
                    mOverlaySurfaceTexture.getTransformMatrix(mOverlayTransform);

                    // 2. Then we can apply the transformations
                    int rotation = mEngine.getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE);
                    Matrix.translateM(mOverlayTransform, 0, 0.5F, 0.5F, 0);
                    Matrix.rotateM(mOverlayTransform, 0, rotation, 0, 0, 1);
                    // No need to flip the x axis for front camera, but need to flip the y axis always.
                    Matrix.scaleM(mOverlayTransform, 0, 1, -1, 1);
                    Matrix.translateM(mOverlayTransform, 0, -0.5F, -0.5F, 0);
                }

                // 5. Draw and save
                mViewport.drawFrame(mTextureId, mTransform);
                if (mHasOverlay) mViewport.drawFrame(mOverlayTextureId, mOverlayTransform);
                mResult.format = PictureResult.FORMAT_JPEG;
                mResult.data = eglSurface.saveFrameTo(Bitmap.CompressFormat.JPEG);

                // 6. Cleanup
                issue514Workaround.onEnd();
                eglSurface.releaseEglSurface();
                mViewport.release();
                fakeOutputSurface.release();
                if (mHasOverlay) {
                    mOverlaySurfaceTexture.releaseTexImage();
                    mOverlaySurface.release();
                    mOverlaySurfaceTexture.release();
                }
                core.release();
                dispatchResult();
            }
        });
    }

    @Override
    protected void dispatchResult() {
        mEngine = null;
        mOutputRatio = null;
        super.dispatchResult();
    }
}
