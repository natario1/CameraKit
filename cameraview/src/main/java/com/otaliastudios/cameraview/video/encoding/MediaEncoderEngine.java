package com.otaliastudios.cameraview.video.encoding;

import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import com.otaliastudios.cameraview.CameraLogger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * The entry point for encoding video files.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MediaEncoderEngine {

    /**
     * Receives the stop event callback to know when the video
     * was written (or what went wrong).
     */
    public interface Listener {

        /**
         * Called when encoding stopped for some reason.
         * If there's an exception, it failed.
         * @param stopReason the reason
         * @param e the error, if present
         */
        @EncoderThread
        void onEncoderStop(int stopReason, @Nullable Exception e);
    }

    private final static String TAG = MediaEncoderEngine.class.getSimpleName();
    private final static CameraLogger LOG = CameraLogger.create(TAG);

    @SuppressWarnings("WeakerAccess")
    public final static int STOP_BY_USER = 0;
    public final static int STOP_BY_MAX_DURATION = 1;
    public final static int STOP_BY_MAX_SIZE = 2;

    private ArrayList<MediaEncoder> mEncoders;
    private MediaMuxer mMediaMuxer;
    private int mStartedEncodersCount;
    private int mStoppedEncodersCount;
    private boolean mMediaMuxerStarted;
    @SuppressWarnings("FieldCanBeLocal")
    private Controller mController;
    private Listener mListener;
    private int mStopReason = STOP_BY_USER;
    private int mPossibleStopReason;
    private final Object mControllerLock = new Object();

    /**
     * Creates a new engine for the given file, with the given encoders and max limits,
     * and listener to receive events.
     *
     * @param file output file
     * @param videoEncoder video encoder to use
     * @param audioEncoder audio encoder to use
     * @param maxDuration max duration in millis
     * @param maxSize max size
     * @param listener a listener
     */
    public MediaEncoderEngine(@NonNull File file,
                              @NonNull VideoMediaEncoder videoEncoder,
                              @Nullable AudioMediaEncoder audioEncoder,
                              final int maxDuration,
                              final long maxSize,
                              @Nullable Listener listener) {
        mListener = listener;
        mController = new Controller();
        mEncoders = new ArrayList<>();
        mEncoders.add(videoEncoder);
        if (audioEncoder != null) {
            mEncoders.add(audioEncoder);
        }
        try {
            mMediaMuxer = new MediaMuxer(file.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mStartedEncodersCount = 0;
        mMediaMuxerStarted = false;
        mStoppedEncodersCount = 0;

        // Trying to convert the size constraints to duration constraints,
        // because they are super easy to check.
        // This is really naive & probably not accurate, but...
        int bitRate = 0;
        for (MediaEncoder encoder : mEncoders) {
            bitRate += encoder.getEncodedBitRate();
        }
        int bytePerSecond = bitRate / 8;
        long sizeMaxDuration = (maxSize / bytePerSecond) * 1000L;

        long finalMaxDuration = Long.MAX_VALUE;
        if (maxSize > 0 && maxDuration > 0) {
            mPossibleStopReason = sizeMaxDuration < maxDuration ? STOP_BY_MAX_SIZE : STOP_BY_MAX_DURATION;
            finalMaxDuration = Math.min(sizeMaxDuration, maxDuration);
        } else if (maxSize > 0) {
            mPossibleStopReason = STOP_BY_MAX_SIZE;
            finalMaxDuration = sizeMaxDuration;
        } else if (maxDuration > 0) {
            mPossibleStopReason = STOP_BY_MAX_DURATION;
            finalMaxDuration = maxDuration;
        }
        LOG.w("Computed a max duration of", (finalMaxDuration / 1000F));
        for (MediaEncoder encoder : mEncoders) {
            encoder.prepare(mController, finalMaxDuration);
        }
    }

    /**
     * Asks encoders to start (each one on its own track).
     */
    public final void start() {
        for (MediaEncoder encoder : mEncoders) {
            encoder.start();
        }
    }

    /**
     * Notifies encoders of some event with the given payload.
     * Can be used for example to notify the video encoder of new frame available.
     * @param event an event string
     * @param data an event payload
     */
    @SuppressWarnings("SameParameterValue")
    public final void notify(final String event, final Object data) {
        for (MediaEncoder encoder : mEncoders) {
            encoder.notify(event, data);
        }
    }

    /**
     * Asks encoders to stop. This is not sync, of course we will ask for encoders
     * to call {@link Controller#requestRelease(int)} before actually stop the muxer.
     * When all encoders request a release, {@link #release()} is called to do cleanup
     * and notify the listener.
     */
    public final void stop() {
        for (MediaEncoder encoder : mEncoders) {
            encoder.stop();
        }
    }

    /**
     * Called after all encoders have requested a release using {@link Controller#requestRelease(int)}.
     * At this point we will do cleanup and notify the listener.
     */
    private void release() {
        Exception error = null;
        if (mMediaMuxer != null) {
            // stop() throws an exception if you haven't fed it any data.
            // But also in other occasions. So this is a signal that something
            // went wrong, and we propagate that to the listener.
            try {
                mMediaMuxer.stop();
                mMediaMuxer.release();
            } catch (Exception e) {
                error = e;
            }
            mMediaMuxer = null;
        }
        if (mListener != null) {
            mListener.onEncoderStop(mStopReason, error);
            mListener = null;
        }
        mStopReason = STOP_BY_USER;
        mStartedEncodersCount = 0;
        mStoppedEncodersCount = 0;
        mMediaMuxerStarted = false;
    }

    /**
     * Returns the current video encoder.
     * @return the current video encoder
     */
    @NonNull
    public VideoMediaEncoder getVideoEncoder() {
        return (VideoMediaEncoder) mEncoders.get(0);
    }

    /**
     * Returns the current audio encoder.
     * @return the current audio encoder
     */
    @SuppressWarnings("unused")
    @Nullable
    public AudioMediaEncoder getAudioEncoder() {
        if (mEncoders.size() > 1) {
            return (AudioMediaEncoder) mEncoders.get(1);
        } else {
            return null;
        }
    }

    /**
     * A handle for {@link MediaEncoder}s to pass information to this engine.
     * All methods here can be called for multiple threads.
     */
    class Controller {

        /**
         * Request that the muxer should start. This is not guaranteed to be executed:
         * we wait for all encoders to call this method, and only then, start the muxer.
         * @param format the media format
         * @return the encoder track index
         */
        int requestStart(@NonNull MediaFormat format) {
            synchronized (mControllerLock) {
                if (mMediaMuxerStarted) {
                    throw new IllegalStateException("Trying to start but muxer started already");
                }
                int track = mMediaMuxer.addTrack(format);
                LOG.w("Controller:", "Assigned track", track, "to format", format.getString(MediaFormat.KEY_MIME));
                if (++mStartedEncodersCount == mEncoders.size()) {
                    mMediaMuxer.start();
                    mMediaMuxerStarted = true;
                }
                return track;
            }
        }

        /**
         * Whether the muxer is started.
         * @return true if muxer was started
         */
        boolean isStarted() {
            synchronized (mControllerLock) {
                return mMediaMuxerStarted;
            }
        }

        /**
         * Writes the given data to the muxer. Should be called after {@link #isStarted()}
         * returns true. Note: this seems to be thread safe, no lock.
         * TODO cache values if not started yet, then apply later. Read comments in drain().
         * Currently they are recycled instantly.
         */
        void write(@NonNull OutputBufferPool pool, @NonNull OutputBuffer buffer) {
            if (!mMediaMuxerStarted) {
                throw new IllegalStateException("Trying to write before muxer started");
            }
            // This is a bad idea and causes crashes.
            // if (info.presentationTimeUs < mLastTimestampUs) info.presentationTimeUs = mLastTimestampUs;
            // mLastTimestampUs = info.presentationTimeUs;
            LOG.v("Writing for track", buffer.trackIndex, ". Presentation:", buffer.info.presentationTimeUs);
            mMediaMuxer.writeSampleData(buffer.trackIndex, buffer.data, buffer.info);
            pool.recycle(buffer);
        }

        /**
         * Requests that the engine stops. This is not executed until all encoders call
         * this method, so it is a kind of soft request, just like {@link #requestStart(MediaFormat)}.
         * To be used when maxLength / maxSize constraints are reached, for example.
         *
         * When this succeeds, {@link MediaEncoder#stop()} is called.
         */
        void requestStop(int track) {
            LOG.i("RequestStop was called for track", track);
            synchronized (mControllerLock) {
                if (--mStartedEncodersCount == 0) {
                    mStopReason = mPossibleStopReason;
                    stop();
                }
            }
        }

        /**
         * Notifies that the encoder was stopped. After this is called by all encoders,
         * we will actually stop the muxer.
         */
        void requestRelease(int track) {
            LOG.i("requestRelease was called for track", track);
            synchronized (mControllerLock) {
                if (++mStoppedEncodersCount == mEncoders.size()) {
                    release();
                }
            }
        }
    }
}
