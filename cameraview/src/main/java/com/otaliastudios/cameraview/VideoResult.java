package com.otaliastudios.cameraview;

import android.location.Location;

import com.otaliastudios.cameraview.controls.Audio;
import com.otaliastudios.cameraview.controls.AudioCodec;
import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.controls.VideoCodec;
import com.otaliastudios.cameraview.size.Size;

import java.io.File;
import java.io.FileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Wraps the result of a video recording started by {@link CameraView#takeVideo(File)}.
 */
@SuppressWarnings("WeakerAccess")
public class VideoResult {

    /**
     * A result stub, for internal use only.
     */
    public static class Stub {

        Stub() {}

        public boolean isSnapshot;
        public Location location;
        public int rotation;
        public Size size;
        public File file;
        public FileDescriptor fileDescriptor;
        public Facing facing;
        public VideoCodec videoCodec;
        public AudioCodec audioCodec;
        public Audio audio;
        public long maxSize;
        public int maxDuration;
        public int endReason;
        public int videoBitRate;
        public double videoCaptureRate;
        public int videoFrameRate;
        public int audioBitRate;
    }

    @SuppressWarnings({"WeakerAccess", "unused"})
    public static final int REASON_USER = 0;

    @SuppressWarnings("WeakerAccess")
    public static final int REASON_MAX_SIZE_REACHED = 1;

    @SuppressWarnings("WeakerAccess")
    public static final int REASON_MAX_DURATION_REACHED = 2;

    private final boolean isSnapshot;
    private final Location location;
    private final int rotation;
    private final Size size;
    private final File file;
    private final FileDescriptor fileDescriptor;
    private final Facing facing;
    private final VideoCodec videoCodec;
    private final AudioCodec audioCodec;
    private final Audio audio;
    private final long maxSize;
    private final int maxDuration;
    private final int endReason;
    private final int videoBitRate;
    private final int videoFrameRate;
    private final int audioBitRate;

    VideoResult(@NonNull Stub builder) {
        isSnapshot = builder.isSnapshot;
        location = builder.location;
        rotation = builder.rotation;
        size = builder.size;
        file = builder.file;
        fileDescriptor = builder.fileDescriptor;
        facing = builder.facing;
        videoCodec = builder.videoCodec;
        audioCodec = builder.audioCodec;
        audio = builder.audio;
        maxSize = builder.maxSize;
        maxDuration = builder.maxDuration;
        endReason = builder.endReason;
        videoBitRate = builder.videoBitRate;
        videoFrameRate = builder.videoFrameRate;
        audioBitRate = builder.audioBitRate;
    }

    /**
     * Returns whether this result comes from a snapshot.
     *
     * @return whether this is a snapshot
     */
    public boolean isSnapshot() {
        return isSnapshot;
    }

    /**
     * Returns geographic information for this video, if any.
     * If it was set, it is also present in the file metadata.
     *
     * @return a nullable Location
     */
    @Nullable
    public Location getLocation() {
        return location;
    }

    /**
     * Returns the clock-wise rotation that should be applied to the
     * video frames before displaying. If it is non-zero, it is also present
     * in the video metadata, so most reader will take care of it.
     *
     * @return the clock-wise rotation
     */
    public int getRotation() {
        return rotation;
    }

    /**
     * Returns the size of the frames after the rotation is applied.
     *
     * @return the Size of this video
     */
    @NonNull
    public Size getSize() {
        return size;
    }

    /**
     * Returns the file where the video was saved.
     *
     * @return the File of this video
     */
    @NonNull
    public File getFile() {
        if (file == null) {
            throw new RuntimeException("File is only available when takeVideo(File) is used.");
        }
        return file;
    }

    /**
     * Returns the file descriptor where the video was saved.
     *
     * @return the File Descriptor of this video
     */
    @NonNull
    public FileDescriptor getFileDescriptor() {
        if (fileDescriptor == null) {
            throw new RuntimeException("FileDescriptor is only available when takeVideo(FileDescriptor) is used.");
        }
        return fileDescriptor;
    }

    /**
     * Returns the facing value with which this video was recorded.
     *
     * @return the Facing of this video
     */
    @NonNull
    public Facing getFacing() {
        return facing;
    }

    /**
     * Returns the codec that was used to encode the video frames.
     *
     * @return the video codec
     */
    @NonNull
    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    /**
     * Returns the codec that was used to encode the audio frames.
     *
     * @return the audio codec
     */
    @NonNull
    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    /**
     * Returns the max file size in bytes that was set before recording,
     * or 0 if no constraint was set.
     *
     * @return the max file size in bytes
     */
    public long getMaxSize() {
        return maxSize;
    }

    /**
     * Returns the max video duration in milliseconds that was set before recording,
     * or 0 if no constraint was set.
     *
     * @return the max duration in milliseconds
     */
    public int getMaxDuration() {
        return maxDuration;
    }

    /**
     * Returns the {@link Audio} setting for this video.
     *
     * @return the audio setting for this video
     */
    @NonNull
    public Audio getAudio() {
        return audio;
    }

    /**
     * Returns the reason why the recording was stopped.
     * @return one of {@link #REASON_USER}, {@link #REASON_MAX_DURATION_REACHED}
     *         or {@link #REASON_MAX_SIZE_REACHED}.
     */
    public int getTerminationReason() {
        return endReason;
    }

    /**
     * Returns the bit rate used for video encoding.
     *
     * @return the video bit rate
     */
    public int getVideoBitRate() {
        return videoBitRate;
    }

    /**
     * Returns the frame rate used for video encoding
     * in frames per second.
     *
     * @return the video frame rate
     */
    public int getVideoFrameRate() {
        return videoFrameRate;
    }

    /**
     * Returns the bit rate used for audio encoding.
     *
     * @return the audio bit rate
     */
    public int getAudioBitRate() {
        return audioBitRate;
    }
}
