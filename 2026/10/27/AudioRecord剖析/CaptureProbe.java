// Application-side observation helper, not Android Framework source.
// Compile with API 29+; call run() on a dedicated worker thread AFTER RECORD_AUDIO is granted.
// Keep the test application in a state that is permitted to capture the microphone.

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CaptureProbe {
    private static final String TAG = "CaptureProbe";
    private static final int SAMPLE_RATE = 16_000;
    private CaptureProbe() {}

    /**
     * Observe microphone capture for durationMs. This method blocks its worker thread.
     * A negative stallAfterMs disables the optional, one-time consumer stall.
     * This is an observation helper, not a production recorder or permission/UI implementation.
     */
    public static void run(long durationMs, long stallAfterMs, long stallMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Use a dedicated worker thread");
        }
        if (durationMs <= 0 || stallMs < 0) {
            throw new IllegalArgumentException("durationMs must be positive; stallMs nonnegative");
        }
        final ExecutorService callbacks = Executors.newSingleThreadExecutor();
        final AudioManager.AudioRecordingCallback callback =
                new AudioManager.AudioRecordingCallback() {
                    @Override
                    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                        for (AudioRecordingConfiguration config : configs) {
                            logConfig("callback", config);
                        }
                    }
                };
        AudioRecord record = null;
        boolean registered = false;
        try {
            int minimumBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimumBytes <= 0) {
                Log.e(TAG, "getMinBufferSize failed: " + minimumBytes);
                return;
            }
            // Mono PCM16: one frame = one short = two bytes.
            // Round to a whole frame, then ask for at least the queried minimum.
            int requestedBytes = ((Math.max(minimumBytes, 640) + 1) / 2) * 2;
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(requestedBytes)
                    .build();
            record.registerAudioRecordingCallback(callbacks, callback);
            registered = true;
            Log.i(TAG, "created state=" + record.getState()
                    + " session=" + record.getAudioSessionId()
                    + " minBytes=" + minimumBytes + " requestBytes=" + requestedBytes
                    + " actualFrames=" + record.getBufferSizeInFrames()
                    + " sampleRate=" + record.getSampleRate());
            record.startRecording();
            Log.i(TAG, "afterStart recordingState=" + record.getRecordingState());
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "Native start did not enter RECORDING");
                return;
            }
            short[] samples = new short[320];
            AudioTimestamp timestamp = new AudioTimestamp();
            long startMs = SystemClock.elapsedRealtime();
            long lastLogMs = startMs;
            long totalFrames = 0;
            long zeroLengthReads = 0;
            long windowSamples = 0;
            long windowZeroSamples = 0;
            double windowSquareSum = 0;
            long maxReadNs = 0;
            boolean stalled = false;
            while (SystemClock.elapsedRealtime() - startMs < durationMs
                    && !Thread.currentThread().isInterrupted()) {
                long elapsedMs = SystemClock.elapsedRealtime() - startMs;
                if (!stalled && stallAfterMs >= 0 && elapsedMs >= stallAfterMs) {
                    stalled = true;
                    Log.i(TAG, "consumerStall begin elapsedMs=" + elapsedMs
                            + " requestedMs=" + stallMs);
                    SystemClock.sleep(stallMs);
                    Log.i(TAG, "consumerStall end elapsedMs="
                            + (SystemClock.elapsedRealtime() - startMs));
                    continue;
                }
                long beforeNs = System.nanoTime();
                int count = record.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                maxReadNs = Math.max(maxReadNs, System.nanoTime() - beforeNs);
                if (count < 0) {
                    Log.e(TAG, "read error=" + count + " totalFrames=" + totalFrames);
                    // Do not silently turn a failed or discontinuous recording into a success.
                    break;
                }
                if (count == 0) {
                    zeroLengthReads++;
                    SystemClock.sleep(2); // Avoid a busy loop; this is an observation tradeoff.
                } else {
                    totalFrames += count; // Valid ONLY because this probe is mono.
                    windowSamples += count;
                    for (int i = 0; i < count; i++) {
                        int value = samples[i];
                        if (value == 0) windowZeroSamples++;
                        windowSquareSum += (double) value * value;
                    }
                }
                long nowMs = SystemClock.elapsedRealtime();
                if (nowMs - lastLogMs >= 500) {
                    double rms = windowSamples == 0 ? Double.NaN
                            : Math.sqrt(windowSquareSum / windowSamples);
                    double zeroRatio = windowSamples == 0 ? Double.NaN
                            : (double) windowZeroSamples / windowSamples;
                    int tsStatus = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
                    String ts = tsStatus == AudioRecord.SUCCESS
                            ? " tsFrame=" + timestamp.framePosition + " tsNs=" + timestamp.nanoTime
                            : " tsUnavailable=" + tsStatus;
                    AudioDeviceInfo device = record.getRoutedDevice();
                    Log.i(TAG, "elapsedMs=" + (nowMs - startMs) + " totalFrames=" + totalFrames
                            + " zeroLengthReads=" + zeroLengthReads + " rmsWindow=" + rms
                            + " zeroRatioWindow=" + zeroRatio + " maxReadNsWindow=" + maxReadNs
                            + " routedDevice=" + (device == null ? "null" : device.getId()) + ts);
                    logConfig("snapshot", record.getActiveRecordingConfiguration());
                    lastLogMs = nowMs;
                    windowSamples = windowZeroSamples = maxReadNs = 0;
                    windowSquareSum = 0;
                }
            }
            Log.i(TAG, "end totalFrames=" + totalFrames
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - startMs));
        } catch (RuntimeException error) {
            Log.e(TAG, "Probe failed; inspect permission, configuration and device state", error);
        } finally {
            if (record != null) {
                if (registered) {
                    try { record.unregisterAudioRecordingCallback(callback); }
                    catch (RuntimeException error) { Log.w(TAG, "unregister failed", error); }
                }
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "stop failed", error);
                } finally {
                    try { record.release(); }
                    finally { callbacks.shutdown(); }
                }
            } else {
                callbacks.shutdown();
            }
        }
    }

    private static void logConfig(String origin, AudioRecordingConfiguration config) {
        if (config == null) {
            Log.i(TAG, origin + " activeConfig=null");
            return;
        }
        Log.i(TAG, origin + " silenced=" + config.isClientSilenced()
                + " session=" + config.getClientAudioSessionId()
                + " clientFormat=" + config.getClientFormat()
                + " deviceFormat=" + config.getFormat());
    }
}
