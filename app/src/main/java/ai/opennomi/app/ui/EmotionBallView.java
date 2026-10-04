package ai.opennomi.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/** Bundled Emotion Ball renderer with a native ball shown until the engine is ready. */
public final class EmotionBallView extends FrameLayout {
    private static final String BASE = "https://appassets.androidplatform.net/emotion-ball/";
    private static final String PAGE = BASE + "nomi.html";
    private final WebView web;
    private final FallbackFace fallback;
    private String emotion = "02";
    private boolean ready;
    private boolean disposed;
    private boolean tapping;
    private float downX, downY;
    private final float touchSlopSquared;
    private String moodKey;
    private String displayedEmotion;
    private boolean motionEnabled = true;
    private boolean foreground = true;

    public EmotionBallView(Context context) {
        super(context);
        setContentDescription("NOMI 的表情球，点击开始或暂停对话");
        setFocusable(true);
        float slop = ViewConfiguration.get(context).getScaledTouchSlop();
        touchSlopSquared = slop * slop;
        fallback = new FallbackFace(context);
        WebView candidate = null;
        try {
            candidate = new WebView(context);
            candidate.setBackgroundColor(Color.TRANSPARENT);
            candidate.getSettings().setJavaScriptEnabled(true);
            candidate.getSettings().setAllowFileAccess(false);
            candidate.getSettings().setAllowContentAccess(false);
            candidate.getSettings().setAllowFileAccessFromFileURLs(false);
            candidate.getSettings().setAllowUniversalAccessFromFileURLs(false);
            candidate.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri url = request.getUrl();
                    String address = url.toString();
                    if (!address.startsWith(BASE)) return emptyResponse();
                    String name = address.substring(BASE.length());
                    if (!name.equals("nomi.html") && !name.equals("rings.js") && !name.equals("emotions.js")
                        && !name.equals("ball.js") && !name.equals("engine.js")) return emptyResponse();
                    try {
                        String mime = name.endsWith(".html") ? "text/html" : "application/javascript";
                        return new WebResourceResponse(mime, "UTF-8",
                            getContext().getAssets().open("emotion-ball/" + name));
                    } catch (IOException missing) { return emptyResponse(); }
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !PAGE.equals(request.getUrl().toString());
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (disposed || !PAGE.equals(url)) return;
                    view.evaluateJavascript("typeof window.setNomiEmotion === 'function'", result -> {
                        if (disposed || !"true".equals(result)) return;
                        ready = true;
                        showEmotion();
                        syncMotion();
                        fallback.setVisibility(GONE);
                    });
                }
            });
            candidate.setOnTouchListener(this::handleTouch);
        } catch (RuntimeException unavailable) {
            if (candidate != null) candidate.destroy();
            candidate = null;
        }
        web = candidate;
        if (web != null) addView(web, new FrameLayout.LayoutParams(-1, -1));
        fallback.setOnTouchListener(this::handleTouch);
        addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        if (web != null) web.loadUrl(PAGE);
    }

    private boolean handleTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tapping = true; downX = event.getX(); downY = event.getY(); break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (dx * dx + dy * dy > touchSlopSquared) tapping = false;
                break;
            case MotionEvent.ACTION_UP:
                boolean click = tapping && event.getX() >= 0 && event.getY() >= 0
                    && event.getX() < view.getWidth() && event.getY() < view.getHeight();
                tapping = false;
                if (click && !disposed) performClick();
                break;
            case MotionEvent.ACTION_CANCEL: case MotionEvent.ACTION_POINTER_DOWN:
                tapping = false; break;
            default: break;
        }
        return true;
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    public void setMood(String mood) {
        String value = mood == null ? "" : mood.toLowerCase(Locale.ROOT);
        if (disposed || value.equals(moodKey)) return;
        moodKey = value;
        if (value.length() == 2 && Character.isDigit(value.charAt(0)) && Character.isDigit(value.charAt(1))) {
            emotion = value;
            fallback.invalidate(); showEmotion(); return;
        }
        switch (value) {
            case "sleep": case "dream": case "睡眠": emotion = "00"; break;
            case "curious": case "好奇": emotion = "03"; break;
            case "loading": emotion = "36"; break;
            case "drowsy": case "tired": emotion = "15"; break;
            case "happy": case "joy": case "开心": emotion = "10"; break;
            case "puzzled": emotion = "11"; break;
            case "sad": case "难过": emotion = "12"; break;
            case "surprise": case "surprised": case "惊讶": emotion = "13"; break;
            case "shy": emotion = "14"; break;
            case "panic": case "fear": emotion = "17"; break;
            case "resigned": emotion = "18"; break;
            case "satisfied": case "calm": emotion = "19"; break;
            case "strive": case "angry": case "生气": emotion = "21"; break;
            case "confused": case "困惑": emotion = "20"; break;
            case "focus": emotion = "16"; break;
            case "processing": case "thinking": emotion = "30"; break;
            case "receiving": emotion = "31"; break;
            case "busy": emotion = "32"; break;
            case "done": case "excited": case "celebrate": emotion = "33"; break;
            case "listening": emotion = "35"; break;
            case "recalling": emotion = "37"; break;
            case "refusing": emotion = "38"; break;
            case "talking": emotion = "39"; break;
            case "scan": case "searching": emotion = "40"; break;
            case "stop": emotion = "41"; break;
            case "error": emotion = "34"; break;
            case "wink": emotion = "14"; break;
            case "lookleft": case "lookright": emotion = "18"; break;
            default: emotion = "02"; break;
        }
        fallback.invalidate();
        showEmotion();
    }

    private void showEmotion() {
        if (ready && web != null && !emotion.equals(displayedEmotion)) {
            displayedEmotion = emotion;
            web.evaluateJavascript("window.setNomiEmotion('" + emotion + "')", null);
        }
    }

    public void setMotionEnabled(boolean enabled) {
        if (motionEnabled == enabled) return;
        motionEnabled = enabled; syncMotion();
    }
    private void syncMotion() {
        if (ready && web != null) web.evaluateJavascript("window.setNomiMotion(" + motionEnabled + "," + foreground + ")", null);
    }
    public void resumeAnimation() {
        if (!foreground) { foreground = true; if (web != null) web.onResume(); syncMotion(); }
    }
    public void pauseAnimation() {
        if (foreground) { foreground = false; syncMotion(); if (web != null) web.onPause(); }
    }
    public void dispose() {
        disposed = true; ready = false; tapping = false;
        if (web != null) { removeView(web); web.destroy(); }
    }

    private final class FallbackFace extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF eyeBounds = new RectF();
        FallbackFace(Context context) { super(context); setBackgroundColor(Color.TRANSPARENT); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = Math.min(getWidth(), getHeight()) * 0.435f;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setColor(0xfff3f0ea); paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, radius, paint);
            paint.setColor(0xff1a1a1a);
            float eyeW = radius * 0.11f;
            float eyeH = emotion.equals("00") ? radius * 0.025f : radius * 0.24f;
            for (int side = -1; side <= 1; side += 2) {
                canvas.save();
                canvas.rotate(-18, x + side * radius * 0.26f, y - radius * 0.22f);
                float cx = x + side * radius * 0.26f;
                float cy = y - radius * 0.22f;
                eyeBounds.set(cx - eyeW / 2, cy - eyeH / 2, cx + eyeW / 2, cy + eyeH / 2);
                canvas.drawRoundRect(eyeBounds, eyeW / 2, eyeW / 2, paint);
                canvas.restore();
            }
        }
    }
}
