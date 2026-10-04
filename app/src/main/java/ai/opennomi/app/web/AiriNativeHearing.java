package ai.opennomi.app.web;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Foreground Android ASR input for the embedded AIRI page; pauses while AIRI replies. */
final class AiriNativeHearing {
    interface Page { void call(String method, String text, String id, Consumer<JSONObject> result); }
    private final Activity activity;
    private final Page page;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean enabled, recognizing;
    private int generation, silentRetries, busyRetries;
    private int listeningGeneration;
    private long recognitionDeadline;
    private String recognitionTimeout;
    private boolean awaitingFinalResult;

    AiriNativeHearing(Activity activity, Page page, Consumer<String> status) {
        this.activity = activity; this.page = page; this.status = status;
    }
    boolean isEnabled() { return enabled; }
    void start() {
        if (enabled) return;
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            status.accept("手机未启用语音识别服务，请使用“听觉设置”配置网页识别服务"); return;
        }
        enabled = true; silentRetries = 0; busyRetries = 0;
        status.accept("正在开启手机听觉…");
        prepare(++generation, 0);
    }
    void stop() {
        enabled = false; generation++;
        releaseRecognizer(); status.accept("手机听觉已暂停");
    }
    private void prepare(int run, int waits) {
        if (!enabled || run != generation) return;
        callPage("prepare", "", "", result -> {
            if (!enabled || run != generation) return;
            String state = result.optString("state");
            if ("ready".equals(state)) {
                main.postDelayed(() -> { if (enabled && run == generation) listen(run); }, 250);
            } else if ("busy".equals(state) && waits < 240) {
                status.accept("等待 AIRI 回复结束…");
                main.postDelayed(() -> prepare(run, waits + 1), 500);
            } else fail(result.optString("message", "AIRI 长时间没有结束回复，请暂停后重试"));
        });
    }
    private void listen(int run) {
        if (!enabled || run != generation || recognizing) return;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizing = true;
            awaitingFinalResult = false;
            int listenRun = ++listeningGeneration;
            recognitionDeadline = SystemClock.elapsedRealtime() + 10000;
            recognitionTimeout = "手机识别服务没有启动，请检查系统识别服务或改用网页听觉";
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean current() { return enabled && recognizing && run == generation
                    && listenRun == listeningGeneration; }
                public void onReadyForSpeech(Bundle b) {
                    if (!current()) return;
                    recognitionDeadline = SystemClock.elapsedRealtime() + 90000;
                    recognitionTimeout = "手机识别服务长时间没有返回结果，请暂停后重试";
                    status.accept("手机听觉已开启，请说话");
                }
                public void onBeginningOfSpeech() { if (current()) { silentRetries = 0; busyRetries = 0; } }
                public void onRmsChanged(float value) {}
                public void onBufferReceived(byte[] bytes) {}
                public void onEndOfSpeech() {
                    if (!current()) return;
                    awaitingFinalResult = true;
                    recognitionDeadline = SystemClock.elapsedRealtime() + 20000;
                    recognitionTimeout = "语音识别结果超时，请检查识别网络或改用网页听觉";
                    status.accept("正在识别…");
                }
                public void onPartialResults(Bundle b) {
                    if (!current()) return;
                    ArrayList<String> words = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (words != null && !words.isEmpty()) {
                        if (!awaitingFinalResult) recognitionDeadline = SystemClock.elapsedRealtime() + 90000;
                        status.accept("听到：" + words.get(0));
                    }
                }
                public void onEvent(int event, Bundle b) {}
                public void onError(int error) {
                    if (!current()) return;
                    releaseRecognizer();
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        retrySilence(run);
                    } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && ++busyRetries <= 3) {
                        status.accept("识别服务忙，稍后继续倾听…");
                        main.postDelayed(() -> prepare(run, 0), 1800);
                    } else fail(errorMessage(error));
                }
                public void onResults(Bundle b) {
                    if (!current()) return;
                    ArrayList<String> words = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    releaseRecognizer();
                    if (words == null || words.isEmpty() || words.get(0).trim().isEmpty()) {
                        retrySilence(run); return;
                    }
                    silentRetries = 0; busyRetries = 0;
                    String text = words.get(0).trim(), id = UUID.randomUUID().toString();
                    status.accept("你：" + text + "\n等待 AIRI 回复…");
                    callPage("send", text, id, result -> {
                        if (!enabled || run != generation) return;
                        if ("accepted".equals(result.optString("state"))) waitReply(run, id, 0, 0);
                        else fail(result.optString("message", "语音未发送，请检查 AIRI 设置"));
                    });
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            recognizer.startListening(intent);
            monitorPlayback(run, listenRun);
        } catch (RuntimeException error) { fail("手机语音识别无法启动，请检查麦克风权限和系统识别服务"); }
    }
    private void monitorPlayback(int run, int listenRun) {
        main.postDelayed(() -> {
            if (!enabled || !recognizing || run != generation || listenRun != listeningGeneration) return;
            if (SystemClock.elapsedRealtime() >= recognitionDeadline) { fail(recognitionTimeout); return; }
            callPage("activity", "", "", result -> {
                if (!enabled || !recognizing || run != generation || listenRun != listeningGeneration) return;
                String state = result.optString("state");
                if ("busy".equals(state)) {
                    releaseRecognizer(); prepare(run, 0);
                } else if ("error".equals(state)) fail(result.optString("message", "网页听觉状态不可用"));
                else monitorPlayback(run, listenRun);
            });
        }, 300);
    }
    private void waitReply(int run, String id, int attempts, int idlePolls) {
        if (!enabled || run != generation) return;
        if (attempts >= 360) { fail("AIRI 回复超时，请检查服务或网页错误提示"); return; }
        callPage("poll", "", id, result -> {
            if (!enabled || run != generation) return;
            String state = result.optString("state");
            if ("error".equals(state)) { fail(result.optString("message", "AIRI 回复失败")); return; }
            int idle = "done".equals(state) ? idlePolls + 1 : 0;
            if (idle >= 4) { prepare(run, 0); return; }
            main.postDelayed(() -> waitReply(run, id, attempts + 1, idle), 500);
        });
    }
    private void retrySilence(int run) {
        busyRetries = 0;
        silentRetries = Math.min(5, silentRetries + 1);
        status.accept("暂未听到清晰语音，继续等待你说话…");
        main.postDelayed(() -> prepare(run, 0), Math.min(4000, 800L * silentRetries));
    }
    private void callPage(String method, String text, String id, Consumer<JSONObject> callback) {
        int run = generation;
        AtomicBoolean completed = new AtomicBoolean();
        Runnable timeout = () -> {
            if (completed.compareAndSet(false, true) && enabled && run == generation)
                fail("AIRI 网页没有响应，手机听觉已暂停，请刷新后重试");
        };
        main.postDelayed(timeout, 8000);
        try {
            page.call(method, text, id, result -> {
                if (!completed.compareAndSet(false, true)) return;
                main.removeCallbacks(timeout);
                if (enabled && run == generation) callback.accept(result);
            });
        } catch (RuntimeException unavailable) {
            main.removeCallbacks(timeout);
            if (completed.compareAndSet(false, true) && enabled && run == generation)
                fail("AIRI 网页暂不可用，请刷新后重试");
        }
    }
    private void fail(String reason) {
        enabled = false; generation++; releaseRecognizer(); status.accept(reason);
    }
    private void releaseRecognizer() {
        recognizing = false; listeningGeneration++;
        SpeechRecognizer previous = recognizer; recognizer = null;
        if (previous != null) {
            try { previous.cancel(); } catch (RuntimeException ignored) {}
            try { previous.destroy(); } catch (RuntimeException ignored) {}
        }
    }
    private static String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "麦克风权限未开启，请在应用权限中允许";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "手机语音识别网络不可用，请检查网络或配置网页识别服务";
            case SpeechRecognizer.ERROR_AUDIO: return "麦克风被占用或无法录音，请关闭其他录音后重试";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "手机识别服务持续忙，请关闭其他录音或改用网页听觉";
            default: return "手机语音识别失败（" + error + "），可改用网页听觉设置中的识别服务";
        }
    }
}
