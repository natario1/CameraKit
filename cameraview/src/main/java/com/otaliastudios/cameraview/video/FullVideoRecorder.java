package com.otaliastudios.cameraview.video;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.VideoResult;
import com.otaliastudios.cameraview.controls.Audio;
import com.otaliastudios.cameraview.controls.AudioCodec;
import com.otaliastudios.cameraview.controls.VideoCodec;
import com.otaliastudios.cameraview.internal.DeviceEncoders;
import com.otaliastudios.cameraview.internal.CamcorderProfiles;
import com.otaliastudios.cameraview.size.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * A {@link VideoRecorder} that uses {@link android.media.MediaRecorder} APIs.
 *
 * When started, the media recorder will be prepared in
 * {@link #prepareMediaRecorder(VideoResult.Stub)}. This will call two abstract methods:
 * - {@link #getCamcorderProfile(VideoResult.Stub)}
 * - {@link #applyVideoSource(VideoResult.Stub, MediaRecorder)}
 *
 * Subclasses can also call {@link #prepareMediaRecorder(VideoResult.Stub)} before start happens,
 * in which case it will not be prepared twice. This can be used for example to test some
 * configurations.
 */
public abstract class FullVideoRecorder extends VideoRecorder {

    private static final String TAG = FullVideoRecorder.class.getSimpleName();
    protected static final CameraLogger LOG = CameraLogger.create(TAG);

    @SuppressWarnings("WeakerAccess") protected MediaRecorder mMediaRecorder;
    private CamcorderProfile mProfile;
    private boolean mMediaRecorderPrepared;


    FullVideoRecorder(@Nullable VideoResultListener listener) {
        super(listener);
    }

    /**
     * Subclasses should return an appropriate CamcorderProfile.
     * This could be taken from the {@link CamcorderProfiles} utility class based on the
     * stub declared size, for instance.
     *
     * @param stub the stub
     * @return the profile
     */
    @NonNull
    protected abstract CamcorderProfile getCamcorderProfile(@NonNull VideoResult.Stub stub);

    /**
     * Subclasses should apply a video source to the given recorder.
     *
     * @param stub the stub
     * @param mediaRecorder the recorder
     */
    protected abstract void applyVideoSource(@NonNull VideoResult.Stub stub,
                                             @NonNull MediaRecorder mediaRecorder);

    @SuppressWarnings("WeakerAccess")
    protected final boolean prepareMediaRecorder(@NonNull VideoResult.Stub stub) {
        if (mMediaRecorderPrepared) return true;
        // We kind of trust the stub size at this point. It's coming from CameraOptions sizes
        // and it's clipped to be less than CamcorderProfile's highest available profile.
        // However, we still can't trust the developer parameters (e.g. bit rates), and even
        // without them, the camera declared sizes can cause crashes in MediaRecorder (#467, #602).
        // A possible solution was to prepare without checking DeviceEncoders first, and should it
        // fail, prepare again checking them. However, when parameters are wrong, MediaRecorder
        // fails on start() instead of prepare() (start failed -19), so this wouldn't be effective.
        return prepareMediaRecorder(stub, true);
    }

    @SuppressWarnings("SameParameterValue")
    private boolean prepareMediaRecorder(@NonNull VideoResult.Stub stub,
                                         boolean applyEncodersConstraints) {
        LOG.i("prepareMediaRecorder:", "Preparing on thread", Thread.currentThread());
        // 1. Create reference and ask for the CamcorderProfile
        mMediaRecorder = new MediaRecorder();
        mProfile = getCamcorderProfile(stub);

        // 2. Set the video and audio sources.
        applyVideoSource(stub, mMediaRecorder);
        int audioChannels = 0;
        if (stub.audio == Audio.ON) {
            audioChannels = mProfile.audioChannels;
        } else if (stub.audio == Audio.MONO) {
            audioChannels = 1;
        } else if (stub.audio == Audio.STEREO) {
            audioChannels = 2;
        }
        boolean hasAudio = audioChannels > 0;
        if (hasAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }

        // 3. Set the output format. Before, change the profile data if the user
        // has specified a specific codec.
        if (stub.videoCodec == VideoCodec.H_264) {
            mProfile.videoCodec = MediaRecorder.VideoEncoder.H264;
            mProfile.fileFormat = MediaRecorder.OutputFormat.MPEG_4;
        } else if (stub.videoCodec == VideoCodec.H_263) {
            mProfile.videoCodec = MediaRecorder.VideoEncoder.H263;
            mProfile.fileFormat = MediaRecorder.OutputFormat.MPEG_4; // should work
        }
        // Set audio codec if the user has specified a specific codec.
        if (stub.audioCodec == AudioCodec.AAC) {
            mProfile.audioCodec = MediaRecorder.AudioEncoder.AAC;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                && stub.audioCodec == AudioCodec.HE_AAC) {
            mProfile.audioCodec = MediaRecorder.AudioEncoder.HE_AAC;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                && stub.audioCodec == AudioCodec.AAC_ELD) {
            mProfile.audioCodec = MediaRecorder.AudioEncoder.AAC_ELD;
        }
        mMediaRecorder.setOutputFormat(mProfile.fileFormat);

        // 4. Update the VideoResult stub with information from the profile, if the
        // stub values are absent or incomplete
        if (stub.videoFrameRate <= 0) stub.videoFrameRate = mProfile.videoFrameRate;
        if (stub.videoBitRate <= 0) stub.videoBitRate = mProfile.videoBitRate;
        if (stub.audioBitRate <= 0 && hasAudio) stub.audioBitRate = mProfile.audioBitRate;

        // 5. Update the VideoResult stub with DeviceEncoders constraints
        if (applyEncodersConstraints) {
            // A. Get the audio mime type
            // https://android.googlesource.com/platform/frameworks/av/+/master/media/libmediaplayerservice/StagefrightRecorder.cpp#1096
            // https://github.com/MrAlex94/Waterfox-Old/blob/master/media/libstagefright/frameworks/av/media/libstagefright/MediaDefs.cpp
            String audioType;
            switch (mProfile.audioCodec) {
                case MediaRecorder.AudioEncoder.AMR_NB: audioType = "audio/3gpp"; break;
                case MediaRecorder.AudioEncoder.AMR_WB: audioType = "audio/amr-wb"; break;
                case MediaRecorder.AudioEncoder.AAC:
                case MediaRecorder.AudioEncoder.HE_AAC:
                case MediaRecorder.AudioEncoder.AAC_ELD: audioType = "audio/mp4a-latm"; break;
                case MediaRecorder.AudioEncoder.VORBIS: audioType = "audio/vorbis"; break;
                case MediaRecorder.AudioEncoder.DEFAULT:
                default: audioType = "audio/3gpp";
            }
            // B. Get the video mime type
            // https://android.googlesource.com/platform/frameworks/av/+/master/media/libmediaplayerservice/StagefrightRecorder.cpp#1650
            // https://github.com/MrAlex94/Waterfox-Old/blob/master/media/libstagefright/frameworks/av/media/libstagefright/MediaDefs.cpp
            String videoType;
            switch (mProfile.videoCodec) {
                case MediaRecorder.VideoEncoder.H263: videoType = "video/3gpp"; break;
                case MediaRecorder.VideoEncoder.H264: videoType = "video/avc"; break;
                case MediaRecorder.VideoEncoder.MPEG_4_SP: videoType = "video/mp4v-es"; break;
                case MediaRecorder.VideoEncoder.VP8: videoType = "video/x-vnd.on2.vp8"; break;
                case MediaRecorder.VideoEncoder.HEVC: videoType = "video/hevc"; break;
                case MediaRecorder.VideoEncoder.DEFAULT:
                default: videoType = "video/avc";
            }
            // C. Check DeviceEncoders support
            boolean flip = stub.rotation % 180 != 0;
            if (flip) stub.size = stub.size.flip();
            Size newVideoSize = null;
            int newVideoBitRate = 0;
            int newAudioBitRate = 0;
            int newVideoFrameRate = 0;
            int videoEncoderOffset = 0;
            int audioEncoderOffset = 0;
            boolean encodersFound = false;
            while (!encodersFound) {
                LOG.i("prepareMediaRecorder:", "Checking DeviceEncoders...",
                        "videoOffset:", videoEncoderOffset,
                        "audioOffset:", audioEncoderOffset);
                DeviceEncoders encoders;
                try {
                    encoders = new DeviceEncoders(DeviceEncoders.MODE_RESPECT_ORDER,
                            videoType, audioType, videoEncoderOffset, audioEncoderOffset);
                } catch (RuntimeException e) {
                    LOG.w("prepareMediaRecorder:", "Could not respect encoders parameters.",
                            "Trying again without checking encoders.");
                    return prepareMediaRecorder(stub, false);
                }
                try {
                    newVideoSize = encoders.getSupportedVideoSize(stub.size);
                    newVideoBitRate = encoders.getSupportedVideoBitRate(stub.videoBitRate);
                    newVideoFrameRate = encoders.getSupportedVideoFrameRate(newVideoSize,
                            stub.videoFrameRate);
                    encoders.tryConfigureVideo(videoType, newVideoSize, newVideoFrameRate,
                            newVideoBitRate);
                    if (hasAudio) {
                        newAudioBitRate = encoders.getSupportedAudioBitRate(stub.audioBitRate);
                        encoders.tryConfigureAudio(audioType, newAudioBitRate,
                                mProfile.audioSampleRate, audioChannels);
                    }
                    encodersFound = true;
                } catch (DeviceEncoders.VideoException videoException) {
                    LOG.i("prepareMediaRecorder:", "Got VideoException:",
                            videoException.getMessage());
                    videoEncoderOffset++;
                } catch (DeviceEncoders.AudioException audioException) {
                    LOG.i("prepareMediaRecorder:", "Got AudioException:",
                            audioException.getMessage());
                    audioEncoderOffset++;
                }
            }
            // D. Apply results
            stub.size = newVideoSize;
            stub.videoBitRate = newVideoBitRate;
            stub.audioBitRate = newAudioBitRate;
            stub.videoFrameRate = newVideoFrameRate;
            if (flip) stub.size = stub.size.flip();
        }

        // 6A. Configure MediaRecorder from stub and from profile (video)
        boolean flip = stub.rotation % 180 != 0;
        mMediaRecorder.setVideoSize(
                flip ? stub.size.getHeight() : stub.size.getWidth(),
                flip ? stub.size.getWidth() : stub.size.getHeight());
        mMediaRecorder.setVideoFrameRate(stub.videoFrameRate);
        mMediaRecorder.setCaptureRate(stub.videoCaptureRate);
        mMediaRecorder.setVideoEncoder(mProfile.videoCodec);
        mMediaRecorder.setVideoEncodingBitRate(stub.videoBitRate);

        // 6B. Configure MediaRecorder from stub and from profile (audio)
        if (hasAudio) {
            mMediaRecorder.setAudioChannels(audioChannels);
            mMediaRecorder.setAudioSamplingRate(mProfile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(mProfile.audioCodec);
            mMediaRecorder.setAudioEncodingBitRate(stub.audioBitRate);
        }

        // 7. Set other params
        if (stub.location != null) {
            mMediaRecorder.setLocation(
                    (float) stub.location.getLatitude(),
                    (float) stub.location.getLongitude());
        }

        if (stub.file != null) {
            mMediaRecorder.setOutputFile(stub.file.getAbsolutePath());
        } else if (stub.fileDescriptor != null) {
            mMediaRecorder.setOutputFile(stub.fileDescriptor);
        } else {
            throw new IllegalStateException("file and fileDescriptor are both null.");
        }

        mMediaRecorder.setOrientationHint(stub.rotation);
        // When using MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, the recorder might have stopped
        // before calling it. But this creates issues on Camera2 Legacy devices - they need a
        // callback BEFORE the recorder stops (see Camera2Engine). For this reason, we increase
        // the max size and use MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING instead.
        // Would do this with max duration as well but there's no such callback.
        mMediaRecorder.setMaxFileSize(stub.maxSize <= 0 ? stub.maxSize
                : Math.round(stub.maxSize / 0.9D));
        LOG.i("prepareMediaRecorder:", "Increased max size from", stub.maxSize, "to",
                Math.round(stub.maxSize / 0.9D));
        mMediaRecorder.setMaxDuration(stub.maxDuration);
        mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mediaRecorder, int what, int extra) {
                LOG.i("OnInfoListener:", "Received info", what, extra,
                        "Thread: ", Thread.currentThread());
                boolean shouldStop = false;
                switch (what) {
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                        mResult.endReason = VideoResult.REASON_MAX_DURATION_REACHED;
                        shouldStop = true;
                        break;
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING:
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                        // On rare occasions APPROACHING is not called. Make sure we listen to
                        // REACHED as well.
                        mResult.endReason = VideoResult.REASON_MAX_SIZE_REACHED;
                        shouldStop = true;
                        break;
                }
                if (shouldStop) {
                    LOG.i("OnInfoListener:", "Stopping");
                    stop(false);
                }
            }
        });
        mMediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
            @Override
            public void onError(MediaRecorder mr, int what, int extra) {
                LOG.e("OnErrorListener: got error", what, extra, ". Stopping.");
                mResult = null;
                mError = new RuntimeException("MediaRecorder error: " + what + " " + extra);
                LOG.i("OnErrorListener:", "Stopping");
                stop(false);
            }
        });

        // 8. Prepare the Recorder
        try {
            mMediaRecorder.prepare();
            mMediaRecorderPrepared = true;
            mError = null;
            return true;
        } catch (Exception e) {
            LOG.w("prepareMediaRecorder:", "Error while preparing media recorder.", e);
            mMediaRecorderPrepared = false;
            mError = e;
            return false;
        }
    }

    @Override
    protected void onStart() {
        if (!prepareMediaRecorder(mResult)) {
            mResult = null;
            stop(false);
            return;
        }

        try {
            mMediaRecorder.start();
            dispatchVideoRecordingStart();
        } catch (Exception e) {
            LOG.w("start:", "Error while starting media recorder.", e);
            mResult = null;
            mError = e;
            stop(false);
        }
    }

    @Override
    protected void onStop(boolean isCameraShutdown) {
        if (mMediaRecorder != null) {
            dispatchVideoRecordingEnd();
            try {
                LOG.i("stop:", "Stopping MediaRecorder...");
                // TODO HANGS (rare, emulator only)
                mMediaRecorder.stop();
                LOG.i("stop:", "Stopped MediaRecorder.");
            } catch (Exception e) {
                // This can happen if stopVideo() is called right after takeVideo()
                // (in which case we don't care). Or when prepare()/start() have failed for
                // some reason and we are not allowed to call stop.
                // Make sure we don't override the error if one exists already.
                mResult = null;
                if (mError == null) {
                    LOG.w("stop:", "Error while closing media recorder.", e);
                    mError = e;
                }
            }
            try {
                LOG.i("stop:", "Releasing MediaRecorder...");
                mMediaRecorder.release();
                LOG.i("stop:", "Released MediaRecorder.");
            } catch (Exception e) {
                mResult = null;
                if (mError == null) {
                    LOG.w("stop:", "Error while releasing media recorder.", e);
                    mError = e;
                }
            }
        }
        mProfile = null;
        mMediaRecorder = null;
        mMediaRecorderPrepared = false;
        dispatchResult();
    }

}
