package com.otaliastudios.cameraview.overlay;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.R;


@SuppressLint("CustomViewStyleable")
public class OverlayLayout extends FrameLayout implements Overlay {

    private static final String TAG = OverlayLayout.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private Target currentTarget = Target.PREVIEW;

    /**
     * We set {@link #setWillNotDraw(boolean)} to false even if we don't draw anything.
     * This ensures that the View system will call {@link #draw(Canvas)} on us instead
     * of short-circuiting to {@link #dispatchDraw(Canvas)}.
     *
     * That would be a problem for us since we use {@link #draw(Canvas)} to understand if
     * we are currently drawing on the preview or not.
     *
     * @param context a context
     */
    public OverlayLayout(@NonNull Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /**
     * Returns true if this {@link AttributeSet} belongs to an overlay.
     * @param set an attribute set
     * @return true if overlay
     */
    public boolean isOverlay(@Nullable AttributeSet set) {
        if (set == null) return false;
        TypedArray a = getContext().obtainStyledAttributes(set, R.styleable.CameraView_Layout);
        boolean isOverlay = a.getBoolean(R.styleable.CameraView_Layout_layout_isOverlay, false);
        a.recycle();
        return isOverlay;
    }

    /**
     * Returns true if this {@link ViewGroup.LayoutParams} belongs to an overlay.
     * @param params a layout params
     * @return true if overlay
     */
    public boolean isOverlay(@NonNull ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    /**
     * Generates our own overlay layout params.
     * @param attrs input attrs
     * @return our params
     */
    @Override
    public OverlayLayout.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    /**
     * This is called by the View hierarchy, so at this point we are
     * likely drawing on the preview.
     * @param canvas View canvas
     */
    @SuppressLint("MissingSuperCall")
    @Override
    public void draw(Canvas canvas) {
        LOG.i("normal draw called.");
        draw(Target.PREVIEW, canvas);
    }

    /**
     * For {@link Target#PREVIEW}, this method is called by the View hierarchy. We will
     * just forward the call to super.
     *
     * For {@link Target#PICTURE_SNAPSHOT} and {@link Target#VIDEO_SNAPSHOT},
     * this method is called by the overlay drawer. We call {@link #dispatchDraw(Canvas)}
     * to draw our children only.
     *
     * @param target the draw target
     * @param canvas the canvas
     */
    @Override
    public void draw(@NonNull Target target, @NonNull Canvas canvas) {
        synchronized (this) {
            currentTarget = target;
            switch (target) {
                case PREVIEW:
                    super.draw(canvas);
                    break;
                case VIDEO_SNAPSHOT:
                case PICTURE_SNAPSHOT:
                    canvas.save();
                    // The input canvas has the Surface dimensions which means it is guaranteed
                    // to have our own aspect ratio. But we might still have to apply some scale.
                    float widthScale = canvas.getWidth() / (float) getWidth();
                    float heightScale = canvas.getHeight() / (float) getHeight();
                    LOG.i("draw",
                            "target:", target,
                            "canvas:", canvas.getWidth() + "x" + canvas.getHeight(),
                            "view:", getWidth() + "x" + getHeight(),
                            "widthScale:", widthScale,
                            "heightScale:", heightScale
                    );
                    canvas.scale(widthScale, heightScale);
                    dispatchDraw(canvas);
                    canvas.restore();
                    break;
            }
        }
    }

    /**
     * We end up here in all three cases, and should filter out
     * views that are not meant to be drawn on that specific surface.
     */
    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        LayoutParams params = (LayoutParams) child.getLayoutParams();
        boolean draw = ((currentTarget == Target.PREVIEW && params.drawOnPreview)
                || (currentTarget == Target.VIDEO_SNAPSHOT && params.drawOnVideoSnapshot)
                || (currentTarget == Target.PICTURE_SNAPSHOT && params.drawOnPictureSnapshot)
        );
        if (draw) {
            LOG.v("Performing drawing for view:", child.getClass().getSimpleName(),
                    "target:", currentTarget,
                    "params:", params);
            return super.drawChild(canvas, child, drawingTime);
        } else {
            LOG.v("Skipping drawing for view:", child.getClass().getSimpleName(),
                    "target:", currentTarget,
                    "params:", params);
            return false;
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class LayoutParams extends FrameLayout.LayoutParams {

        @SuppressWarnings("unused")
        private boolean isOverlay;
        public boolean drawOnPreview;
        public boolean drawOnPictureSnapshot;
        public boolean drawOnVideoSnapshot;

        public LayoutParams(@NonNull Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView_Layout);
            try {
                this.isOverlay = a.getBoolean(R.styleable.CameraView_Layout_layout_isOverlay, false);
                this.drawOnPreview = a.getBoolean(R.styleable.CameraView_Layout_layout_drawOnPreview, false);
                this.drawOnPictureSnapshot = a.getBoolean(R.styleable.CameraView_Layout_layout_drawOnPictureSnapshot, false);
                this.drawOnVideoSnapshot = a.getBoolean(R.styleable.CameraView_Layout_layout_drawOnVideoSnapshot, false);
            } finally {
                a.recycle();
            }
        }

        @NonNull
        @Override
        public String toString() {
            return getClass().getName() + "[isOverlay:" + isOverlay
                    + ",drawOnPreview:" + drawOnPreview
                    + ",drawOnPictureSnapshot:" + drawOnPictureSnapshot
                    + ",drawOnVideoSnapshot:" + drawOnVideoSnapshot
                    + "]";
        }
    }
}
