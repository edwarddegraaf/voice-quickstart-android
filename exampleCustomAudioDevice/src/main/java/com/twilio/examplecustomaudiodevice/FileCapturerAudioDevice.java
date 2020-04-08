package com.twilio.examplecustomaudiodevice;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.twilio.voice.AudioDevice;
import com.twilio.voice.AudioDeviceContext;
import com.twilio.voice.AudioFormat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import tvo.webrtc.voiceengine.WebRtcAudioUtils;

public class FileCapturerAudioDevice implements AudioDevice {
    private static final String TAG = "FileCapturerAudioDevice";
    private Context context;

    private boolean keepAliveRendererRunnable = true;
    // Requested size of each recorded buffer provided to the client.
    private static final int CALLBACK_BUFFER_SIZE_MS = 10;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int WAV_FILE_HEADER_SIZE = 44;
    private int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;
    private static final int BUFFER_SIZE_FACTOR = 2;

    private ByteBuffer fileWriteByteBuffer;
    private int writeBufferSize;
    private InputStream inputStream;
    private DataInputStream dataInputStream;

    private AudioDeviceContext audioDeviceContext;
    private AudioRecord audioRecord;
    private ByteBuffer micWriteBuffer;
    private android.os.Handler capturerHandler;
    private final HandlerThread capturerThread;

    private ByteBuffer readByteBuffer;
    private AudioTrack audioTrack = null;
    private android.os.Handler rendererHandler;
    private final HandlerThread rendererThread;

    private boolean isMusicPlaying = true;

    private final Runnable fileCapturerRunnable = () -> {
        int bytesRead;
        try {
            if (dataInputStream != null && (bytesRead = dataInputStream.read(fileWriteByteBuffer.array(), 0, writeBufferSize)) > -1) {
                if (bytesRead == fileWriteByteBuffer.capacity()) {
                    AudioDevice.audioDeviceWriteCaptureData(audioDeviceContext, fileWriteByteBuffer);
                } else {
                    processRemaining(fileWriteByteBuffer, fileWriteByteBuffer.capacity());
                    AudioDevice.audioDeviceWriteCaptureData(audioDeviceContext, fileWriteByteBuffer);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        capturerHandler.postDelayed(this.fileCapturerRunnable, CALLBACK_BUFFER_SIZE_MS);
    };

    private final Runnable microphoneCapturerRunnable = () -> {
        audioRecord.startRecording();
        while (true) {
            int bytesRead = audioRecord.read(micWriteBuffer, micWriteBuffer.capacity());
            if (bytesRead == micWriteBuffer.capacity()) {
                AudioDevice.audioDeviceWriteCaptureData(audioDeviceContext, micWriteBuffer);
            } else {
                String errorMessage = "AudioRecord.read failed: " + bytesRead;
                Log.e(TAG, errorMessage);
                if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    stopRecording();
                    Log.e(TAG, errorMessage);
                }
                break;
            }
        }
    };

    private final Runnable rendererRunnable = () -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        try {
            audioTrack.play();
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioTrack.play failed: " + e.getMessage());
            this.releaseAudioResources();
            return;
        }

        // Fixed size in bytes of each 10ms block of audio data that we ask for
        // using callbacks to the native WebRTC client.
        final int sizeInBytes = readByteBuffer.capacity();

        while (keepAliveRendererRunnable) {
            // Get 10ms of PCM data from the native WebRTC client. Audio data is
            // written into the common ByteBuffer using the address that was
            // cached at construction.
            AudioDevice.audioDeviceReadRenderData(audioDeviceContext, readByteBuffer);

            int bytesWritten = 0;
            if (WebRtcAudioUtils.runningOnLollipopOrHigher()) {
                bytesWritten = writeOnLollipop(audioTrack, readByteBuffer, sizeInBytes);
            } else {
                bytesWritten = writePreLollipop(audioTrack, readByteBuffer, sizeInBytes);
            }
            if (bytesWritten != sizeInBytes) {
                Log.e(TAG, "AudioTrack.write failed: " + bytesWritten);
                if (bytesWritten == AudioTrack.ERROR_INVALID_OPERATION) {
                    keepAliveRendererRunnable = false;
                }
            }
            // The byte buffer must be rewinded since byteBuffer.position() is
            // increased at each call to AudioTrack.write(). If we don't do this,
            // next call to AudioTrack.write() will fail.
            readByteBuffer.rewind();
        }
    };

    public FileCapturerAudioDevice(Context context) {
        this.context = context;
        this.capturerThread = new HandlerThread("CapturerThread");
        this.capturerThread.start();
        this.capturerHandler = new android.os.Handler(capturerThread.getLooper());

        this.rendererThread = new HandlerThread("RendererThread");
        this.rendererThread.start();
        this.rendererHandler = new android.os.Handler(rendererThread.getLooper());
    }

    public void switchInput(boolean playMusic) {
        isMusicPlaying = playMusic;
        if (playMusic) {
            initializeStreams();
            stopRecording();
            capturerHandler.post(fileCapturerRunnable);
        } else {
            capturerHandler.removeCallbacks(fileCapturerRunnable);
            capturerHandler.post(microphoneCapturerRunnable);
        }
    }

    private void initializeStreams() {
        inputStream = null;
        dataInputStream = null;
        inputStream = context.getResources().openRawResource(context.getResources().getIdentifier("music",
                "raw", context.getPackageName()));
        dataInputStream = new DataInputStream(inputStream);
        try {
            int bytes = dataInputStream.skipBytes(WAV_FILE_HEADER_SIZE);
            Log.d(TAG, "Number of bytes skipped : " + bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isMusicPlaying() {
        return isMusicPlaying;
    }

    @Nullable
    @Override
    public AudioFormat getCapturerFormat() {
        return new AudioFormat(AudioFormat.AUDIO_SAMPLE_RATE_44100,
                AudioFormat.AUDIO_SAMPLE_STEREO);
    }

    @Override
    public boolean onInitCapturer() {
        int bytesPerFrame = 2 * (BITS_PER_SAMPLE / 8);
        int framesPerBuffer = getCapturerFormat().getSampleRate() / BUFFERS_PER_SECOND;

        fileWriteByteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
        writeBufferSize = fileWriteByteBuffer.capacity();
        initializeStreams();
        int channelConfig = channelCountToConfiguration(getCapturerFormat().getChannelCount());
        int minBufferSize =
                AudioRecord.getMinBufferSize(getCapturerFormat().getSampleRate(),
                        channelConfig, android.media.AudioFormat.ENCODING_PCM_16BIT);

        micWriteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
        int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, micWriteBuffer.capacity());
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, getCapturerFormat().getSampleRate(),
                android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes);
        return true;
    }

    @Override
    public boolean onStartCapturing(@NonNull AudioDeviceContext audioDeviceContext) {
        this.audioDeviceContext = audioDeviceContext;
        capturerHandler.post(fileCapturerRunnable);
        return true;
    }

    @Override
    public boolean onStopCapturing() {
        stopRecording();
        closeStreams();
        return true;
    }

    @Nullable
    @Override
    public AudioFormat getRendererFormat() {
        return new AudioFormat(AudioFormat.AUDIO_SAMPLE_RATE_44100,
                AudioFormat.AUDIO_SAMPLE_STEREO);
    }

    @Override
    public boolean onInitRenderer() {
        int bytesPerFrame = getRendererFormat().getChannelCount() * (BITS_PER_SAMPLE / 8);
        readByteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * (getRendererFormat().getSampleRate() / BUFFERS_PER_SECOND));
        int channelConfig = channelCountToConfiguration(getRendererFormat().getChannelCount());
        int minBufferSize = AudioRecord.getMinBufferSize(getRendererFormat().getSampleRate(), channelConfig, android.media.AudioFormat.ENCODING_PCM_16BIT);
        this.audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, getRendererFormat().getSampleRate(), channelConfig,
                android.media.AudioFormat.ENCODING_PCM_16BIT, minBufferSize, AudioTrack.MODE_STREAM);
        return true;
    }

    @Override
    public boolean onStartRendering(@NonNull AudioDeviceContext audioDeviceContext) {
        this.audioDeviceContext = audioDeviceContext;
        rendererHandler.post(rendererRunnable);
        return true;
    }

    @Override
    public boolean onStopRendering() {
        keepAliveRendererRunnable = false;

        try {
            audioTrack.stop();
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioTrack.stop failed: " + e.getMessage());
        }
        audioTrack.flush();

        return true;
    }

    private void processRemaining(ByteBuffer bb, int chunkSize) {
        bb.position(bb.limit()); // move at the end
        bb.limit(chunkSize); // get ready to pad with longs
        while (bb.position() < chunkSize) {
            bb.putLong(0);
        }
        bb.limit(chunkSize);
        bb.flip();
    }

    private void closeStreams() {
        capturerHandler.removeCallbacks(fileCapturerRunnable);
        try {
            dataInputStream.close();
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        capturerHandler.removeCallbacks(microphoneCapturerRunnable);
        try {
            if (audioRecord != null) {
                audioRecord.stop();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioRecord.stop failed: " + e.getMessage());
        }
    }

    private int channelCountToConfiguration(int channels) {
        return (channels == 1 ? android.media.AudioFormat.CHANNEL_IN_MONO : android.media.AudioFormat.CHANNEL_IN_STEREO);
    }

    // Releases the native AudioTrack resources.
    private void releaseAudioResources() {
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private int writeOnLollipop(AudioTrack audioTrack, ByteBuffer byteBuffer, int sizeInBytes) {
        return audioTrack.write(byteBuffer, sizeInBytes, AudioTrack.WRITE_BLOCKING);
    }

    private int writePreLollipop(AudioTrack audioTrack, ByteBuffer byteBuffer, int sizeInBytes) {
        return audioTrack.write(byteBuffer.array(), byteBuffer.arrayOffset(), sizeInBytes);
    }
}