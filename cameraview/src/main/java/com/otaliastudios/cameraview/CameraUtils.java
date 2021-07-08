package com.otaliastudios.cameraview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Handler;
import android.os.ParcelFileDescriptor;

import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.engine.mappers.Camera1Mapper;
import com.otaliastudios.cameraview.internal.ExifHelper;
import com.otaliastudios.cameraview.internal.WorkerHandler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Static utilities for dealing with camera I/O, orientations, etc.
 */
@SuppressWarnings("unused")
public class CameraUtils {

    private final static String TAG = CameraUtils.class.getSimpleName();
    private final static CameraLogger LOG = CameraLogger.create(TAG);

    /**
     * Determines whether the device has valid camera sensors, so the library
     * can be used.
     *
     * @param context a valid Context
     * @return whether device has cameras
     */
    @SuppressWarnings("WeakerAccess")
    public static boolean hasCameras(@NonNull Context context) {
        PackageManager manager = context.getPackageManager();
        // There's also FEATURE_CAMERA_EXTERNAL , should we support it?
        return manager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
                || manager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
    }


    /**
     * Determines whether the device has a valid camera sensor with the given
     * Facing value, so that a session can be started.
     *
     * @param context a valid context
     * @param facing either {@link Facing#BACK} or {@link Facing#FRONT}
     * @return true if such sensor exists
     */
    public static boolean hasCameraFacing(@SuppressWarnings("unused") @NonNull Context context,
                                          @NonNull Facing facing) {
        int internal = Camera1Mapper.get().mapFacing(facing);
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == internal) return true;
        }
        return false;
    }


    /**
     * Simply writes the given data to the given file. It is done synchronously. If you are
     * running on the UI thread, please use {@link #writeToFile(byte[], File, FileCallback)}
     * and pass a file callback.
     *
     * If any error is encountered, this returns null.
     *
     * @param data the data to be written
     * @param file the file to write into
     * @return the source file, or null if error
     */
    @SuppressWarnings("WeakerAccess")
    @Nullable
    @WorkerThread
    @SuppressLint("NewApi")
    public static File writeToFile(@NonNull final byte[] data, @NonNull File file) {
        if (file.exists() && !file.delete()) return null;
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(file))) {
            stream.write(data);
            stream.flush();
            return file;
        } catch (IOException e) {
            LOG.e("writeToFile:", "could not write file.", e);
            return null;
        }
    }

    @SuppressWarnings("WeakerAccess")
    @Nullable
    @WorkerThread
    @SuppressLint("NewApi")
    public static Uri writeToFile(Context context, @NonNull final byte[] data, @NonNull Uri file) {
        OutputStream stream =  null;
        Uri result;
        try {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(file, "w");
            stream = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
            stream.write(data);
            stream.flush();
            result = file;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (Exception ignored) {

            }
        }
        return result;
    }


    /**
     * Writes the given data to the given file in a background thread, returning on the
     * original thread (typically the UI thread) once writing is done.
     * If some error is encountered, the {@link FileCallback} will return null instead of the
     * original file.
     *
     * @param data the data to be written
     * @param file the file to write into
     * @param callback a callback
     */
    @SuppressWarnings("WeakerAccess")
    public static void writeToFile(@NonNull final byte[] data,
                                   @NonNull final File file,
                                   @NonNull final FileCallback callback) {
        final Handler ui = new Handler();
        WorkerHandler.execute(new Runnable() {
            @Override
            public void run() {
                final File result = writeToFile(data, file);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onFileReady(result);
                    }
                });
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    public static void writeToFile(final Context context,
                                   @NonNull final byte[] data,
                                   @NonNull final Uri file,
                                   @NonNull final FileUriCallback callback) {
        final Handler ui = new Handler();
        WorkerHandler.execute(new Runnable() {
            @Override
            public void run() {
                final Uri result = writeToFile(context, data, file);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onFileReady(result);
                    }
                });
            }
        });
    }

    /**
     * Decodes an input byte array and outputs a Bitmap that is ready to be displayed.
     * The difference with {@link android.graphics.BitmapFactory#decodeByteArray(byte[], int, int)}
     * is that this cares about orientation, reading it from the EXIF header.
     *
     * @param source a JPEG byte array
     * @return decoded bitmap or null if error is encountered
     */
    @SuppressWarnings("WeakerAccess")
    @Nullable
    @WorkerThread
    public static Bitmap decodeBitmap(@NonNull final byte[] source) {
        return decodeBitmap(source, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Decodes an input byte array and outputs a Bitmap that is ready to be displayed.
     * The difference with {@link android.graphics.BitmapFactory#decodeByteArray(byte[], int, int)}
     * is that this cares about orientation, reading it from the EXIF header.
     * This is executed in a background thread, and returns the result to the original thread.
     *
     * @param source a JPEG byte array
     * @param callback a callback to be notified
     */
    @SuppressWarnings("WeakerAccess")
    public static void decodeBitmap(@NonNull final byte[] source,
                                    @NonNull final BitmapCallback callback) {
        decodeBitmap(source, Integer.MAX_VALUE, Integer.MAX_VALUE, callback);
    }

    /**
     * Decodes an input byte array and outputs a Bitmap that is ready to be displayed.
     * The difference with {@link android.graphics.BitmapFactory#decodeByteArray(byte[], int, int)}
     * is that this cares about orientation, reading it from the EXIF header.
     * This is executed in a background thread, and returns the result to the original thread.
     *
     * The image is also downscaled taking care of the maxWidth and maxHeight arguments.
     *
     * @param source a JPEG byte array
     * @param maxWidth the max allowed width
     * @param maxHeight the max allowed height
     * @param callback a callback to be notified
     */
    @SuppressWarnings("WeakerAccess")
    public static void decodeBitmap(@NonNull final byte[] source,
                                    final int maxWidth,
                                    final int maxHeight,
                                    @NonNull final BitmapCallback callback) {
        decodeBitmap(source, maxWidth, maxHeight, new BitmapFactory.Options(), callback);
    }

    /**
     * Decodes an input byte array and outputs a Bitmap that is ready to be displayed.
     * The difference with {@link android.graphics.BitmapFactory#decodeByteArray(byte[], int, int)}
     * is that this cares about orientation, reading it from the EXIF header.
     * This is executed in a background thread, and returns the result to the original thread.
     *
     * The image is also downscaled taking care of the maxWidth and maxHeight arguments.
     *
     * @param source a JPEG byte array
     * @param maxWidth the max allowed width
     * @param maxHeight the max allowed height
     * @param options the options to be passed to decodeByteArray
     * @param callback a callback to be notified
     */
    @SuppressWarnings("WeakerAccess")
    public static void decodeBitmap(@NonNull final byte[] source,
                                    final int maxWidth,
                                    final int maxHeight,
                                    @NonNull final BitmapFactory.Options options,
                                    @NonNull final BitmapCallback callback) {
        decodeBitmap(source, maxWidth, maxHeight, options, -1, callback);
    }

    static void decodeBitmap(@NonNull final byte[] source,
                             final int maxWidth,
                             final int maxHeight,
                             @NonNull final BitmapFactory.Options options,
                             final int rotation,
                             @NonNull final BitmapCallback callback) {
        final Handler ui = new Handler();
        WorkerHandler.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodeBitmap(source, maxWidth, maxHeight, options, rotation);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onBitmapReady(bitmap);
                    }
                });
            }
        });
    }

    /**
     * Decodes an input byte array and outputs a Bitmap that is ready to be displayed.
     * The difference with {@link android.graphics.BitmapFactory#decodeByteArray(byte[], int, int)}
     * is that this cares about orientation, reading it from the EXIF header.
     *
     * The image is also downscaled taking care of the maxWidth and maxHeight arguments.
     *
     * @param source a JPEG byte array
     * @param maxWidth the max allowed width
     * @param maxHeight the max allowed height
     * @return decoded bitmap or null if error is encountered
     */
    @SuppressWarnings("SameParameterValue")
    @Nullable
    @WorkerThread
    public static Bitmap decodeBitmap(@NonNull byte[] source, int maxWidth, int maxHeight) {
        return decodeBitmap(source, maxWidth, maxHeight, new BitmapFactory.Options());
    }

    /**
     * Decodes an input byte array and outputs a Bitmap that is ready to be displayed.
     * The difference with {@link android.graphics.BitmapFactory#decodeByteArray(byte[], int, int)}
     * is that this cares about orientation, reading it from the EXIF header.
     *
     * The image is also downscaled taking care of the maxWidth and maxHeight arguments.
     *
     * @param source a JPEG byte array
     * @param maxWidth the max allowed width
     * @param maxHeight the max allowed height
     * @param options the options to be passed to decodeByteArray
     * @return decoded bitmap or null if error is encountered
     */
    @SuppressWarnings("WeakerAccess")
    @Nullable
    @WorkerThread
    public static Bitmap decodeBitmap(@NonNull byte[] source,
                                      int maxWidth,
                                      int maxHeight,
                                      @NonNull BitmapFactory.Options options) {
        return decodeBitmap(source, maxWidth, maxHeight, options, -1);
    }

    // Null means we got OOM
    // Ignores flipping, but it should be super rare.
    @SuppressWarnings("TryFinallyCanBeTryWithResources")
    @Nullable
    private static Bitmap decodeBitmap(@NonNull byte[] source,
                                       int maxWidth,
                                       int maxHeight,
                                       @NonNull BitmapFactory.Options options,
                                       int rotation) {
        if (maxWidth <= 0) maxWidth = Integer.MAX_VALUE;
        if (maxHeight <= 0) maxHeight = Integer.MAX_VALUE;
        int orientation;
        boolean flip;
        if (rotation == -1) {
            InputStream stream = null;
            try {
                // http://sylvana.net/jpegcrop/exif_orientation.html
                stream = new ByteArrayInputStream(source);
                ExifInterface exif = new ExifInterface(stream);
                int exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                orientation = ExifHelper.getOrientation(exifOrientation);
                flip = exifOrientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
                        exifOrientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
                        exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                        exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE;
                LOG.i("decodeBitmap:", "got orientation from EXIF.", orientation);
            } catch (IOException e) {
                LOG.e("decodeBitmap:", "could not get orientation from EXIF.", e);
                orientation = 0;
                flip = false;
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Exception ignored) { }
                }
            }
        } else {
            orientation = rotation;
            flip = false;
            LOG.i("decodeBitmap:", "got orientation from constructor.", orientation);
        }

        Bitmap bitmap;
        try {
            if (maxWidth < Integer.MAX_VALUE || maxHeight < Integer.MAX_VALUE) {
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(source, 0, source.length, options);

                int outHeight = options.outHeight;
                int outWidth = options.outWidth;
                if (orientation % 180 != 0) {
                    //noinspection SuspiciousNameCombination
                    outHeight = options.outWidth;
                    //noinspection SuspiciousNameCombination
                    outWidth = options.outHeight;
                }

                options.inSampleSize = computeSampleSize(outWidth, outHeight, maxWidth, maxHeight);
                options.inJustDecodeBounds = false;
                bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            } else {
                bitmap = BitmapFactory.decodeByteArray(source, 0, source.length);
            }

            if (orientation != 0 || flip) {
                Matrix matrix = new Matrix();
                matrix.setRotate(orientation);
                Bitmap temp = bitmap;
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                        bitmap.getHeight(), matrix, true);
                temp.recycle();
            }
        } catch (OutOfMemoryError e) {
            bitmap = null;
        }
        return bitmap;
    }

    private static int computeSampleSize(int width, int height, int maxWidth, int maxHeight) {
        // https://developer.android.com/topic/performance/graphics/load-bitmap.html
        int inSampleSize = 1;
        if (height > maxHeight || width > maxWidth) {
            while ((height / inSampleSize) >= maxHeight
                    || (width / inSampleSize) >= maxWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }


}
