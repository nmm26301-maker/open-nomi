package ai.opennomi.app.web;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Runs the real controller with a virtual main loop and scripted Android ASR callbacks. */
public final class AiriHearingRegression {
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private static JSONObject state(String s) { return new JSONObject().put("state", s); }
    private static Bundle words(String text) {
        Bundle b = new Bundle();
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(List.of(text)));
        return b;
    }
    private static final class Page implements AiriNativeHearing.Page {
        int sends;
        boolean replyBusy = true, duplicateCallbacks, hang;
        Consumer<JSONObject> late;
        public void call(String method, String text, String id, Consumer<JSONObject> done) {
            if (hang) { late = done; return; }
            JSONObject result;
            switch (method) {
                case "send": sends++; result = state("accepted"); break;
                case "poll": result = state(replyBusy ? "busy" : "done"); break;
                default: result = state("ready");
            }
            done.accept(result);
            if (duplicateCallbacks) done.accept(result);
        }
    }
    private static AiriNativeHearing create(Page page, List<String> status) {
        Handler.reset(); SpeechRecognizer.reset();
        return new AiriNativeHearing(new Activity(), page, status::add);
    }
    private static void start(AiriNativeHearing h) { h.start(); Handler.advance(250); }
    public static void main(String[] args) {
        List<String> status = new ArrayList<>(); Page page = new Page();
        AiriNativeHearing h = create(page, status); start(h);
        RecognitionListener stale = SpeechRecognizer.latest().listener;
        for (int i = 1; i <= 7; i++) {
            SpeechRecognizer.latest().listener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            Handler.advance(Math.min(4000, 800L * i) + 250);
            check(h.isEnabled(), "silence stopped continuous hearing");
            check(SpeechRecognizer.instances.size() == i + 1, "silence did not resume one session");
        }
        SpeechRecognizer current = SpeechRecognizer.latest();
        stale.onResults(words("旧的一句话")); stale.onError(SpeechRecognizer.ERROR_AUDIO);
        check(page.sends == 0 && !current.destroyed && h.isEnabled(), "stale listener changed current session");
        page.duplicateCallbacks = true;
        current.listener.onResults(words("你好")); current.listener.onResults(words("你好"));
        check(page.sends == 1, "one result sent twice");
        int count = SpeechRecognizer.instances.size();
        Handler.advance(3000);
        check(SpeechRecognizer.instances.size() == count, "listened during a pending reply");
        page.replyBusy = false; Handler.advance(2500);
        check(SpeechRecognizer.instances.size() == count + 1, "reply completion started duplicate sessions");
        RecognitionListener stopped = SpeechRecognizer.latest().listener;
        h.stop(); Handler.advance(20000); stopped.onResults(words("不能发送"));
        check(page.sends == 1 && SpeechRecognizer.instances.size() == count + 1, "stop allowed stale send/restart");

        page = new Page(); h = create(page, status); start(h);
        SpeechRecognizer.latest().listener.onResults(words("  "));
        Handler.advance(1050);
        check(h.isEnabled() && page.sends == 0 && SpeechRecognizer.instances.size() == 2, "empty result stopped or sent");
        h.stop();

        page = new Page(); h = create(page, status); start(h);
        for (int i = 0; i < 4; i++) {
            SpeechRecognizer.latest().listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            Handler.advance(2050);
        }
        check(!h.isEnabled(), "persistent busy service retried forever");

        page = new Page(); h = create(page, status); SpeechRecognizer.ready = false; start(h);
        Handler.advance(10500);
        check(!h.isEnabled() && SpeechRecognizer.latest().destroyed, "unresponsive ASR startup held the microphone");

        page = new Page(); h = create(page, status); start(h);
        SpeechRecognizer.latest().listener.onEndOfSpeech();
        Handler.advance(10000); SpeechRecognizer.latest().listener.onPartialResults(words("迟到的中间结果"));
        Handler.advance(10500);
        check(!h.isEnabled() && page.sends == 0, "missing ASR final result never timed out");

        page = new Page(); page.hang = true; h = create(page, status); h.start();
        Handler.advance(8100); check(!h.isEnabled(), "webpage callback stall never timed out");
        page.late.accept(state("ready")); Handler.advance(1000);
        check(SpeechRecognizer.instances.isEmpty(), "late webpage callback reopened hearing after timeout");

        page = new Page(); h = create(page, status); start(h);
        SpeechRecognizer.cancelThrows = true; h.stop();
        check(SpeechRecognizer.latest().destroyed, "cancel exception prevented ASR cleanup");
        System.out.println("PASS: silence continuation, stale/duplicate callback isolation, reply gating, stop, ASR/page timeout and cleanup");
    }
}
