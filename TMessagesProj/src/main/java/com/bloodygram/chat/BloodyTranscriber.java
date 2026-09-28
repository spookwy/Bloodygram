package com.bloodygram.chat;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice message to text without Premium: the voice file is decoded to PCM and fed to Android's speech recognizer
 * (on-device when the phone has it). The text goes into Telegram's own transcription block under the message.
 */
public class BloodyTranscriber {

    private static final int RATE = 16000;
    private static final int MAX_SECONDS = 15 * 60;

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(ApplicationLoader.applicationContext);
    }

    public static boolean canTranscribe(MessageObject message) {
        return message != null && (message.isVoice() || message.isRoundVideo()) && message.getDocument() != null;
    }

    /** Result on the UI thread: (text, null) or (null, error for the user). */
    public static void transcribe(MessageObject message, Utilities.Callback2<String, String> done) {
        if (Build.VERSION.SDK_INT < 33) {
            done.run(null, BloodyStrings.get(R.string.BloodyTranscribeOldAndroid));
            return;
        }
        if (!isSupported()) {
            done.run(null, BloodyStrings.get(R.string.BloodyTranscribeNoRecognizer));
            return;
        }
        ensureModel();
        withFile(message, file -> {
            if (file == null) {
                done.run(null, BloodyStrings.get(R.string.BloodyTranscribeNoFile));
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                byte[] pcm = decode(file);
                AndroidUtilities.runOnUIThread(() -> {
                    if (pcm == null || pcm.length == 0) {
                        done.run(null, BloodyStrings.get(R.string.BloodyTranscribeNoFile));
                    } else {
                        recognize(pcm, done, true);
                    }
                });
            });
        });
    }

    /** Shows the text under the voice message the same way Telegram shows its own transcriptions. */
    public static void apply(MessageObject message, String text) {
        TLRPC.Message owner = message.messageOwner;
        owner.voiceTranscription = text;
        owner.voiceTranscriptionOpen = true;
        owner.voiceTranscriptionFinal = true;
        owner.voiceTranscriptionForce = true;
        MessagesStorage.getInstance(message.currentAccount).updateMessageVoiceTranscription(message.getDialogId(), message.getId(), text, owner);
        NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, message, 0L, text, true, true);
    }

    // region file

    private static final int FILE_WAIT_TIMEOUT = 30_000;

    private static void withFile(MessageObject message, Utilities.Callback<File> callback) {
        int account = message.currentAccount;
        File file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        File ready = readable(file);
        if (ready != null) {
            callback.run(ready);
            return;
        }
        TLRPC.Document document = message.getDocument();
        String name = FileLoader.getAttachFileName(document);
        NotificationCenter center = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        boolean[] done = {false};
        Runnable cleanup = () -> {
            if (!done[0]) {
                done[0] = true;
                center.removeObserver(observer[0], NotificationCenter.fileLoaded);
                center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            }
        };
        observer[0] = (id, acc, args) -> {
            if (name.equals(args[0]) && !done[0]) {
                cleanup.run();
                callback.run(id == NotificationCenter.fileLoaded ? readable(FileLoader.getInstance(account).getPathToMessage(message.messageOwner)) : null);
            }
        };
        center.addObserver(observer[0], NotificationCenter.fileLoaded);
        center.addObserver(observer[0], NotificationCenter.fileLoadFailed);
        FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_HIGH, message.shouldEncryptPhotoOrVideo() ? 2 : 0);
        // the load can be silently dropped (offline, cancelled elsewhere) with neither notification ever firing
        AndroidUtilities.runOnUIThread(() -> {
            if (!done[0]) {
                cleanup.run();
                callback.run(null);
            }
        }, FILE_WAIT_TIMEOUT);
    }

    /** The plain file, or a decrypted temp copy of a view-once one (Telegram keeps those encrypted). */
    private static File readable(File file) {
        if (file == null) {
            return null;
        }
        if (file.exists()) {
            return file;
        }
        File encrypted = new File(file.getAbsolutePath() + ".enc");
        if (!encrypted.exists()) {
            return null;
        }
        try {
            File key = new File(FileLoader.getInternalCacheDir(), encrypted.getName() + ".key");
            File temp = new File(ApplicationLoader.applicationContext.getCacheDir(), "bloody_voice_" + file.getName());
            try (InputStream in = new EncryptedFileInputStream(encrypted, key); OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            temp.deleteOnExit();
            return temp;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // endregion

    // region decoding

    /** Any audio the phone can decode (ogg/opus voice, mp4 round videos) to 16 kHz mono 16-bit PCM. */
    private static byte[] decode(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) {
                return null;
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            ByteArrayOutputStream mono = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long limitUs = MAX_SECONDS * 1_000_000L;
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0 || extractor.getSampleTime() > limitUs) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        ByteBuffer samples = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int frames = info.size / 2 / channels;
                        for (int i = 0; i < frames; i++) {
                            int sum = 0;
                            for (int c = 0; c < channels; c++) {
                                sum += samples.getShort((i * channels + c) * 2);
                            }
                            short s = (short) (sum / channels);
                            mono.write(s & 0xFF);
                            mono.write((s >> 8) & 0xFF);
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            return resample(mono.toByteArray(), rate);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignore) {
            }
            extractor.release();
        }
    }

    /** Linear resampling of 16-bit mono PCM to {@link #RATE}. */
    private static byte[] resample(byte[] pcm, int rate) {
        if (rate == RATE) {
            return pcm;
        }
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int inFrames = pcm.length / 2;
        int outFrames = (int) ((long) inFrames * RATE / rate);
        ByteBuffer out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
        double step = (double) rate / RATE;
        for (int i = 0; i < outFrames; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(inFrames - 1, i0 + 1);
            double t = pos - i0;
            double v = in.getShort(i0 * 2) * (1 - t) + in.getShort(i1 * 2) * t;
            out.putShort((short) v);
        }
        return out.array();
    }

    // endregion

    // region recognition

    @RequiresApi(33)
    private static void recognize(byte[] pcm, Utilities.Callback2<String, String> done, boolean preferOnDevice) {
        Context context = ApplicationLoader.applicationContext;
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Exception e) {
            FileLog.e(e);
            done.run(null, BloodyStrings.get(R.string.BloodyTranscribeFailed));
            return;
        }
        ParcelFileDescriptor readSide = pipe[0];
        ParcelFileDescriptor writeSide = pipe[1];
        Utilities.globalQueue.postRunnable(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
                out.write(pcm);
            } catch (Exception ignore) {
                // the recognizer stopped reading
            }
        });

        boolean onDevice = preferOnDevice; // audio-from-file recognition is only supported by an on-device recognizer
        SpeechRecognizer recognizer = onDevice
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context);
        ArrayList<String> parts = new ArrayList<>();
        boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            try {
                recognizer.destroy();
                readSide.close();
            } catch (Exception ignore) {
            }
            String text = TextUtils.join(" ", parts).trim();
            if (text.isEmpty()) {
                done.run(null, BloodyStrings.get(R.string.BloodyTranscribeNothing));
            } else {
                done.run(text, null);
            }
        };
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onError(int error) {
                if (finished[0]) {
                    return;
                }
                // no on-device language pack yet: start downloading it and ask the user to retry once it is ready
                if (onDevice && parts.isEmpty()
                        && (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)) {
                    finished[0] = true;
                    try {
                        recognizer.triggerModelDownload(intentFor(null));
                    } catch (Exception ignore) {
                    }
                    try {
                        recognizer.destroy();
                        readSide.close();
                    } catch (Exception ignore) {
                    }
                    done.run(null, BloodyStrings.get(R.string.BloodyTranscribeDownloading));
                    return;
                }
                finish.run();
            }

            @Override
            public void onResults(Bundle results) {
                addBest(results, parts);
                finish.run();
            }

            @Override
            public void onSegmentResults(Bundle segmentResults) {
                addBest(segmentResults, parts);
            }

            @Override
            public void onEndOfSegmentedSession() {
                finish.run();
            }
        });

        recognizer.startListening(intentFor(readSide));
        // never hang: long voice messages get as long as they play plus a margin
        AndroidUtilities.runOnUIThread(finish, 30_000 + pcm.length / 2 * 1000L / RATE * 2);
    }

    @RequiresApi(33)
    private static Intent intentFor(ParcelFileDescriptor audio) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language());
        if (audio != null) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        }
        return intent;
    }

    private static void addBest(Bundle results, ArrayList<String> parts) {
        ArrayList<String> list = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty() && !TextUtils.isEmpty(list.get(0))) {
            parts.add(list.get(0));
        }
    }

    private static boolean modelRequested;

    /** Kick off the on-device language pack download early, so the first real transcription can succeed. */
    @RequiresApi(33)
    private static void ensureModel() {
        if (modelRequested) {
            return;
        }
        modelRequested = true;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ApplicationLoader.applicationContext);
                r.triggerModelDownload(intentFor(null));
                AndroidUtilities.runOnUIThread(r::destroy, 4000);
            } catch (Exception ignore) {
            }
        });
    }

    private static String language() {
        BloodyConfig.load();
        String lang = BloodyConfig.prefs().getString("transcribeLang", "");
        return TextUtils.isEmpty(lang) ? Locale.getDefault().toLanguageTag() : lang;
    }

    // endregion
}
