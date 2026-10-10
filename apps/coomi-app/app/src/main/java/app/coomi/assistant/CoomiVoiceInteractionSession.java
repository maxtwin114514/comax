package app.coomi.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.coomi.CoomiService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** System-invoked voice session: listen, transcribe through the local engine, then run control mode. */
public final class CoomiVoiceInteractionSession extends VoiceInteractionSession {
    private static final int SAMPLE_RATE = 16_000;
    private static final long MAX_RECORDING_MS = 15_000L;
    private static final long SILENCE_AFTER_SPEECH_MS = 1_200L;
    private static final int SILENCE_AMPLITUDE = 420;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AssistantSettingsStore settings;
    private TextView statusView;
    private LinearLayout capsule;
    private volatile AudioRecord audioRecord;
    private volatile Thread audioThread;
    private final AtomicBoolean sessionClosed = new AtomicBoolean(false);
    private SpeechRecognizer systemRecognizer;
    private final AtomicBoolean captureCancelled = new AtomicBoolean(true);

    public CoomiVoiceInteractionSession(Context context) {
        super(context);
        settings = new AssistantSettingsStore(context);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getContext().getResources().getDisplayMetrics());
    }

    @Override public void onCreate() {
        super.onCreate();
        setUiEnabled(true);
        setKeepAwake(true);
        Window window = getWindow() == null ? null : getWindow().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0f);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        }
    }

    @Override public View onCreateContentView() {
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.TRANSPARENT);
        capsule = new LinearLayout(getContext());
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setPadding(dp(14), dp(9), dp(14), dp(9));
        capsule.setBackgroundResource(com.termux.R.drawable.coomi_assistant_capsule);
        capsule.setElevation(dp(12));

        TextView orb = new TextView(getContext());
        orb.setText("✦");
        orb.setGravity(Gravity.CENTER);
        orb.setTextColor(0xFF2F6BD8);
        orb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        orb.setBackgroundResource(com.termux.R.drawable.coomi_bg_blue_soft);
        capsule.addView(orb, new LinearLayout.LayoutParams(dp(32), dp(32)));

        statusView = new TextView(getContext());
        statusView.setTextColor(0xFF1D2939);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        statusView.setSingleLine(true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        statusParams.leftMargin = dp(10);
        capsule.addView(statusView, statusParams);

        TextView close = new TextView(getContext());
        close.setText("×");
        close.setTextColor(0xFF667085);
        close.setGravity(Gravity.CENTER);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        close.setOnClickListener(view -> hide());
        capsule.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));

        FrameLayout.LayoutParams capsuleParams = new FrameLayout.LayoutParams(
            Math.min(dp(330), getContext().getResources().getDisplayMetrics().widthPixels - dp(28)),
            dp(58), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        capsuleParams.topMargin = dp(8);
        root.addView(capsule, capsuleParams);
        return root;
    }

    @Override public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        sessionClosed.set(false);
        if (!settings.isEnabled()) {
            setStatus("手机助手尚未开启，请先在 Comax 控制台配置");
            main.postDelayed(this::hide, 2600L);
            return;
        }
        animateIn();
        startListening();
    }

    @Override public void onHide() {
        sessionClosed.set(true);
        stopListening();
        animateOut();
        super.onHide();
    }

    @Override public void onDestroy() {
        sessionClosed.set(true);
        stopListening();
        if (systemRecognizer != null) {
            systemRecognizer.destroy();
            systemRecognizer = null;
        }
        super.onDestroy();
    }

    private void animateIn() {
        if (capsule == null) return;
        capsule.animate().cancel();
        capsule.setPivotX(capsule.getWidth() / 2f);
        capsule.setPivotY(0f);
        capsule.setScaleX(.08f);
        capsule.setScaleY(.45f);
        capsule.setTranslationY(-dp(12));
        capsule.setAlpha(0f);
        capsule.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
            .setDuration(280L).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void animateOut() {
        if (capsule == null) return;
        capsule.animate().cancel();
        capsule.animate().scaleX(.12f).scaleY(.5f).translationY(-dp(12)).alpha(0f)
            .setDuration(170L).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void setStatus(String text) {
        main.post(() -> { if (statusView != null) statusView.setText(text); });
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("需要麦克风权限，请回到手机助手设置授权");
            return;
        }
        if (!settings.speechProvider().isEmpty() && !settings.speechModel().isEmpty()
            && CoomiService.current() != null) {
            startLocalRecording();
        } else if (settings.useSystemFallback()) {
            startSystemRecognition();
        } else {
            setStatus("请先配置可用于语音识别的模型");
        }
    }

    private void startLocalRecording() {
        stopListening();
        captureCancelled.set(false);
        int minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minimum, SAMPLE_RATE / 2);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("录音设备不可用");
            audioRecord.startRecording();
        } catch (Exception error) {
            releaseAudioRecord();
            if (settings.useSystemFallback()) startSystemRecognition();
            else setStatus("无法开始录音：" + error.getMessage());
            return;
        }
        setStatus("正在聆听…");
        audioThread = new Thread(() -> captureAudio(bufferSize), "coomi-assistant-record");
        audioThread.start();
    }

    private void captureAudio(int bufferSize) {
        short[] samples = new short[Math.max(1024, bufferSize / 2)];
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long startedAt = System.currentTimeMillis();
        long lastSoundAt = startedAt;
        boolean heardSpeech = false;
        try {
            while (!captureCancelled.get() && System.currentTimeMillis() - startedAt < MAX_RECORDING_MS) {
                AudioRecord recorder = audioRecord;
                if (recorder == null) break;
                int count = recorder.read(samples, 0, samples.length);
                if (count <= 0) continue;
                int peak = 0;
                for (int index = 0; index < count; index++) {
                    short sample = samples[index];
                    peak = Math.max(peak, Math.abs((int) sample));
                    pcm.write(sample & 0xff);
                    pcm.write((sample >> 8) & 0xff);
                }
                long now = System.currentTimeMillis();
                if (peak >= SILENCE_AMPLITUDE) {
                    heardSpeech = true;
                    lastSoundAt = now;
                } else if (heardSpeech && now - lastSoundAt >= SILENCE_AFTER_SPEECH_MS) {
                    break;
                }
            }
        } finally {
            releaseAudioRecord();
        }
        if (captureCancelled.get() || sessionClosed.get()) return;
        if (pcm.size() < SAMPLE_RATE / 4) {
            if (settings.useSystemFallback()) main.post(this::startSystemRecognition);
            else setStatus("没有听清，请再试一次");
            return;
        }
        File wave = new File(getContext().getCacheDir(), "assistant-" + System.nanoTime() + ".wav");
        try {
            writeWave(wave, pcm.toByteArray());
        } catch (Exception error) {
            setStatus("语音准备失败");
            return;
        }
        setStatus("正在识别…");
        new Thread(() -> transcribeAndRun(wave), "coomi-assistant-transcribe").start();
    }

    private void transcribeAndRun(File wave) {
        try {
            CoomiService service = CoomiService.current();
            if (service == null) throw new IllegalStateException("Comax 引擎未运行");
            String[] result = service.transcribeAssistant(
                wave, settings.speechProvider(), settings.speechModel(), settings.language());
            if (result[0] == null || result[0].trim().isEmpty()) throw new IllegalStateException(result[1]);
            String text = result[0].trim();
            setStatus("正在执行：" + (text.length() > 24 ? text.substring(0, 24) + "…" : text));
            AssistantSettingsStore.ControlModel control = settings.activeControlModel();
            if (!control.isReady()) throw new IllegalStateException("请先配置并启用一个执行模型");
            String error = service.submitControlTask(
                settings.controlSessionId(), control.providerId, control.model, text);
            if (error != null) throw new IllegalStateException(error);
            setStatus("任务已交给 Comax");
            main.postDelayed(this::hide, 1400L);
        } catch (Exception error) {
            if (settings.useSystemFallback() && !sessionClosed.get()) {
                main.post(this::startSystemRecognition);
            } else {
                setStatus(error.getMessage() == null ? "语音任务失败" : error.getMessage());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            wave.delete();
        }
    }

    private void startSystemRecognition() {
        stopListening();
        captureCancelled.set(false);
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            setStatus("系统语音识别不可用");
            return;
        }
        if (systemRecognizer != null) systemRecognizer.destroy();
        systemRecognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
        systemRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { setStatus("正在聆听…"); }
            @Override public void onBeginningOfSpeech() { setStatus("正在识别…"); }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { setStatus("正在整理语音…"); }
            @Override public void onError(int error) { setStatus("没有听清，请再唤醒一次"); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (values == null || values.isEmpty()) { setStatus("没有听清"); return; }
                runRecognizedText(values.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.language())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        systemRecognizer.startListening(intent);
    }

    private void runRecognizedText(String text) {
        setStatus("正在执行：" + (text.length() > 24 ? text.substring(0, 24) + "…" : text));
        new Thread(() -> {
            CoomiService service = CoomiService.current();
            AssistantSettingsStore.ControlModel control = settings.activeControlModel();
            String error = service == null ? "Comax 引擎未运行"
                : !control.isReady() ? "请先配置并启用一个执行模型"
                : service.submitControlTask(settings.controlSessionId(), control.providerId, control.model, text);
            if (error == null) {
                setStatus("任务已交给 Comax");
                main.postDelayed(this::hide, 1400L);
            } else setStatus(error);
        }, "coomi-assistant-control").start();
    }

    private void stopListening() {
        captureCancelled.set(true);
        releaseAudioRecord();
        if (systemRecognizer != null) {
            try { systemRecognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void releaseAudioRecord() {
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder == null) return;
        try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
        catch (Exception ignored) {}
        recorder.release();
    }

    private static void writeWave(File target, byte[] pcm) throws Exception {
        int dataLength = pcm.length;
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(new byte[]{'R','I','F','F'});
            writeLe32(output, 36 + dataLength);
            output.write(new byte[]{'W','A','V','E','f','m','t',' '});
            writeLe32(output, 16);
            writeLe16(output, 1);
            writeLe16(output, 1);
            writeLe32(output, SAMPLE_RATE);
            writeLe32(output, SAMPLE_RATE * 2);
            writeLe16(output, 2);
            writeLe16(output, 16);
            output.write(new byte[]{'d','a','t','a'});
            writeLe32(output, dataLength);
            output.write(pcm);
            output.getFD().sync();
        }
    }

    private static void writeLe16(FileOutputStream output, int value) throws Exception {
        output.write(value & 0xff); output.write((value >> 8) & 0xff);
    }

    private static void writeLe32(FileOutputStream output, int value) throws Exception {
        output.write(value & 0xff); output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff); output.write((value >> 24) & 0xff);
    }
}
