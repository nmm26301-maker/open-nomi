package ai.opennomi.app.web;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.WindowInsets;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.speech.SpeechRecognizer;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.function.Consumer;

/** Hosted real-time voice stages, with microphone access restricted to the configured origin. */
public final class AiriActivity extends Activity {
    static final String DEFAULT_URL = "https://airi.moeru.ai/";
    private static final int MICROPHONE_REQUEST = 71;
    private static final int NATIVE_MICROPHONE_REQUEST = 72;
    private WebView web;
    private PermissionRequest pendingMicrophone;
    private Uri allowedOrigin;
    private LinearLayout root;
    private boolean genericPage;
    private Uri stageTarget;
    private TextView pageStatus;
    private TextView addressBar;
    private FrameLayout browserPane;
    private LinearLayout hearingPanel;
    private TextView hearingStatus;
    private Button hearingToggle;
    private AiriNativeHearing nativeHearing;
    private String voiceScript;
    private boolean foreground;
    private boolean nativePermissionPending;
    private boolean resumeNativeAfterPermission;
    private final java.util.ArrayList<WebView> childWindows = new java.util.ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        genericPage = getIntent().hasExtra("entry_url");
        nativeHearing = new AiriNativeHearing(this, this::callVoicePage, this::showHearingStatus);
        try (java.io.InputStream script = getAssets().open("airi-native-voice.js")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096]; int count;
            while ((count = script.read(chunk)) >= 0) bytes.write(chunk, 0, count);
            voiceScript = bytes.toString("UTF-8");
        } catch (java.io.IOException unavailable) { voiceScript = null; }
        String address = getSharedPreferences("open_nomi", MODE_PRIVATE)
            .getString("airi_url", DEFAULT_URL);
        if (genericPage) address = getIntent().getStringExtra("entry_url");
        getWindow().setStatusBarColor(0xff0b1012);
        getWindow().setNavigationBarColor(0xff0b1012);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b1012);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(0, top, 0, bottom);
            return insets;
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16), dp(12), dp(16), dp(4));
        Button back = toolbarButton("NOMI", false); back.setOnClickListener(view -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button title = toolbarButton(genericPage ? getIntent().getStringExtra("entry_title") : "AIRI", !genericPage);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        titleParams.setMargins(dp(6), 0, dp(6), 0); bar.addView(title, titleParams);
        Button help = toolbarButton("设置", false); help.setOnClickListener(view -> showPageMenu());
        bar.addView(help, new LinearLayout.LayoutParams(dp(64), dp(46))); root.addView(bar);
        addressBar = new TextView(this); addressBar.setTextColor(Color.LTGRAY);
        addressBar.setTextSize(12); addressBar.setPadding(16, 4, 16, 4);
        root.addView(addressBar);
        setContentView(root);
        if (!validEntry(address)) address = DEFAULT_URL;
        stageTarget = Uri.parse(address);
        loadStage(stageTarget);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button toolbarButton(String label, boolean selected) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setTextSize(14); button.setTextColor(selected ? 0xff8ae3c1 : 0xff869598);
        button.setPadding(dp(8), 0, dp(8), 0); button.setMinWidth(0); button.setMinimumWidth(0);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(selected ? 0xff263d37 : 0xff172124); shape.setCornerRadius(dp(24));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x338ae3c1), shape, null));
        return button;
    }
    private void showPageMenu() {
        String[] actions = genericPage ? new String[]{"刷新网页"}
            : new String[]{"手机听觉", "对话记忆", "听觉与麦克风检查", "AIRI 听觉设置", "AIRI 服务设置", "回到 AIRI 角色", "刷新网页", "配置说明", "申请指南", "修改网址"};
        new AlertDialog.Builder(this).setTitle("应用内网页")
            .setItems(actions, (dialog, which) -> {
                String action = actions[which];
                if ("手机听觉".equals(action)) { showNativeHearing(); }
                else if ("对话记忆".equals(action)) showMemory();
                else if ("听觉与麦克风检查".equals(action)) diagnoseHearing();
                else if ("配置说明".equals(action)) showConfigurationHelp();
                else if ("申请指南".equals(action)) { nativeHearing.stop(); startActivity(new android.content.Intent(this, ai.opennomi.app.screen.WorkspaceActivity.class).putExtra("tab", "help")); }
                else if ("修改网址".equals(action)) editAddress();
                else if ("刷新网页".equals(action)) { if (activeWeb() != null) activeWeb().reload(); }
                else {
                    nativeHearing.stop();
                    closeChildWindows();
                    if (web != null) web.loadUrl("AIRI 听觉设置".equals(action) ? hearingTarget().toString()
                        : "AIRI 服务设置".equals(action) ? settingsTarget().toString() : stageTarget.toString());
                }
            }).show();
    }

    private void editAddress() {
        EditText input = new EditText(this); input.setSingleLine(true);
        input.setText(stageTarget == null ? "" : stageTarget.toString());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("在应用内打开网址")
            .setView(input).setPositiveButton("打开", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = input.getText().toString().trim();
            if (!validEntry(address)) { input.setError("请填写固定的 HTTPS 入口网址，不能用一次性登录回调"); return; }
            getSharedPreferences("open_nomi", MODE_PRIVATE).edit()
                .putString("airi_url", address).apply();
            stageTarget = Uri.parse(address);
            nativeHearing.stop();
            // Remove the initial empty-server setup, retaining the toolbar and address.
            while (root.getChildCount() > 2) root.removeViewAt(2);
            pageStatus = null; browserPane = null; hearingPanel = null; hearingStatus = null; hearingToggle = null;
            loadStage(stageTarget); dialog.dismiss();
        }));
        dialog.show();
    }

    private static final String LEGACY_AIRI_GUIDE = "AIRI 需要先连接 AI 服务才能回答。出现“没有配置聊天服务或模型”时，按以下顺序设置：\n\n1. 服务来源（Providers）：添加聊天服务，填写该服务的 API Key 并验证。\n2. 机体模块 → 思维（Consciousness）：选择刚才的服务和聊天模型。\n3. 要语音对话，还需在听觉（Hearing）和言语（Speech）里选择语音识别与合成服务。\n\n密钥只在手机的 AIRI 设置中填写。没有服务账号时，可以返回 NOMI 继续使用已绑定的球球语音。";

    private static final String AIRI_GUIDE = LEGACY_AIRI_GUIDE;

    private Uri settingsTarget() {
        // The official Stage Web exposes /settings. Custom deployments retain their own base path.
        if ("airi.moeru.ai".equalsIgnoreCase(stageTarget.getHost()))
            return stageTarget.buildUpon().path("/settings").clearQuery().fragment(null).build();
        return stageTarget;
    }
    private Uri hearingTarget() {
        if ("airi.moeru.ai".equalsIgnoreCase(stageTarget.getHost()))
            return stageTarget.buildUpon().path("/settings/modules/hearing").clearQuery().fragment(null).build();
        return settingsTarget();
    }
    private void showNativeHearing() {
        if (web == null) return;
        if (hearingPanel == null) {
            hearingPanel = new LinearLayout(this); hearingPanel.setOrientation(LinearLayout.VERTICAL);
            hearingPanel.setPadding(16, 6, 16, 6); hearingPanel.setBackgroundColor(0xff171717);
            LinearLayout controls = new LinearLayout(this);
            hearingToggle = new Button(this); hearingToggle.setText("开启手机听觉");
            hearingToggle.setOnClickListener(v -> toggleNativeHearing());
            controls.addView(hearingToggle, new LinearLayout.LayoutParams(0, -2, 1));
            Button config = new Button(this); config.setText("听觉设置");
            config.setOnClickListener(v -> {
                nativeHearing.stop(); closeChildWindows(); web.loadUrl(hearingTarget().toString());
            }); controls.addView(config);
            Button hide = new Button(this); hide.setText("收起");
            hide.setOnClickListener(v -> { nativeHearing.stop(); hearingPanel.setVisibility(android.view.View.GONE); });
            controls.addView(hide); hearingPanel.addView(controls);
            hearingStatus = new TextView(this); hearingStatus.setTextColor(Color.WHITE); hearingStatus.setTextSize(13);
            hearingStatus.setText("使用手机语音识别；回复结束后自动继续听。手机需启用识别服务，AIRI 仍需聊天模型。");
            hearingPanel.addView(hearingStatus); root.addView(hearingPanel, 2);
        }
        hearingToggle.setOnClickListener(v -> toggleNativeHearing());
        hearingPanel.setVisibility(android.view.View.VISIBLE);
    }
    private void showHearingStatus(String message) {
        if (hearingStatus != null) hearingStatus.setText(message);
        if (hearingToggle != null) hearingToggle.setText(nativeHearing.isEnabled() ? "暂停手机听觉" : "开启手机听觉");
    }
    private void toggleNativeHearing() {
        if (nativeHearing.isEnabled()) { nativeHearing.stop(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            nativePermissionPending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, NATIVE_MICROPHONE_REQUEST); return;
        }
        nativeHearing.start();
        if (hearingToggle != null) hearingToggle.setText(nativeHearing.isEnabled() ? "暂停手机听觉" : "开启手机听觉");
    }
    private void callVoicePage(String method, String text, String id, Consumer<JSONObject> callback) {
        WebView target = activeWeb();
        if (!foreground || target == null || target.getUrl() == null || !sameOrigin(Uri.parse(target.getUrl()))) {
            callback.accept(voiceError("请先回到 AIRI 角色页面，再开启手机听觉")); return;
        }
        if (voiceScript == null) { callback.accept(voiceError("手机听觉组件无法加载，请重新安装")); return; }
        String command = voiceScript + "\n(() => { try { return JSON.stringify(window.__nomiAiriVoice["
            + JSONObject.quote(method) + "](" + JSONObject.quote(text) + "," + JSONObject.quote(id)
            + ")); } catch(e) { return JSON.stringify({state:'error',message:String(e.message || e)}); } })();";
        target.evaluateJavascript(command, encoded -> {
            if (target != activeWeb() || !foreground || target.getUrl() == null || !sameOrigin(Uri.parse(target.getUrl()))) {
                callback.accept(voiceError("网页已切换，手机听觉已停止")); return;
            }
            try { callback.accept(new JSONObject(new JSONArray("[" + encoded + "]").getString(0))); }
            catch (Exception error) { callback.accept(voiceError("AIRI 网页暂不支持手机听觉，请刷新或使用网页听觉设置")); }
        });
    }
    private static JSONObject voiceError(String message) {
        JSONObject result = new JSONObject();
        try { result.put("state", "error").put("message", message); } catch (Exception ignored) {}
        return result;
    }
    private void showMemory() {
        callVoicePage("memoryInfo", "", "", result -> {
            if ("error".equals(result.optString("state"))) { Toast.makeText(this,result.optString("message"),Toast.LENGTH_LONG).show();return; }
            boolean enabled = result.optBoolean("enabled",true);
            StringBuilder message = new StringBuilder("AIRI 记忆保存于本机，与 NOMI 分开。下次交流会带上最近聊天。\n已保存 " + result.optInt("count") + " 段\n");
            if(result.optBoolean("storageError"))message.append("当前存储失败，关闭网页后可能丢失，请检查存储空间。\n");
            JSONArray recent = result.optJSONArray("recent");
            if(recent != null)for(int i=recent.length()-1;i>=0;i--) {
                JSONObject turn=recent.optJSONObject(i);
                if(turn!=null)message.append("\n你：").append(turn.optString("u")).append("\nAIRI：").append(turn.optString("a")).append("\n");
            }
            new AlertDialog.Builder(this).setTitle("AIRI 本机对话记忆").setMessage(message.toString())
                .setPositiveButton(enabled ? "关闭记忆" : "开启记忆",(d,w)->{
                    nativeHearing.stop();callVoicePage("setMemory",String.valueOf(!enabled),"",r->showMemory());
                }).setNeutralButton("清空",(d,w)->new AlertDialog.Builder(this).setTitle("清空 AIRI 本机记忆？")
                    .setMessage("删除本机保存的 AIRI 聊天。网页当前会话的历史请在 AIRI 内另行新建会话。")
                    .setPositiveButton("确认清空",(confirm,button)->{
                        nativeHearing.stop();callVoicePage("clearMemory","","",r->showMemory());
                    }).setNegativeButton("取消",null).show())
                .setNegativeButton("完成",null).show();
        });
    }
    private void diagnoseHearing() {
        callVoicePage("diagnose", "", "", result -> {
            String message = "手机麦克风权限：" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED ? "已允许" : "未开启")
                + "\n手机语音识别：" + (SpeechRecognizer.isRecognitionAvailable(this) ? "可用" : "未启用")
                + "\n网页内置识别：" + (result.optBoolean("webSpeech") ? "支持" : "当前 WebView 未提供")
                + "\nAIRI 识别服务：" + (result.optBoolean("providerSelected") ? "已选择" : "未选择")
                + "\nAIRI 聊天模型：" + (result.optBoolean("chatConfigured") ? "已选择" : "请在思维设置中配置")
                + "\n\n网页识别不支持时，可用手机听觉；手机识别不可用时，需要配置网页识别服务。";
            if ("error".equals(result.optString("state"))) message += "\n" + result.optString("message");
            new AlertDialog.Builder(this).setTitle("听觉检查").setMessage(message)
                .setPositiveButton("手机听觉", (d, w) -> showNativeHearing())
                .setNegativeButton("关闭", null).show();
        });
    }

    private void showConfigurationHelp() {
        new AlertDialog.Builder(this).setTitle("AIRI 配置")
            .setMessage(AIRI_GUIDE)
            .setPositiveButton("知道了", null).setNegativeButton("返回 NOMI", (dialog, which) -> finish())
            .show();
    }

    private static boolean validHttps(String address) {
        Uri uri = Uri.parse(address);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
            && !uri.getHost().isEmpty() && uri.getUserInfo() == null;
    }

    public static boolean validEntry(String address) {
        if (!validHttps(address)) return false;
        Uri uri = Uri.parse(address);
        return !((uri.getPath() != null && uri.getPath().contains("/auth/callback"))
            || (uri.getQueryParameter("code") != null && uri.getQueryParameter("state") != null));
    }

    private void loadStage(Uri target) {
        nativeHearing.stop();
        closeChildWindows();
        allowedOrigin = target;
        if (pendingMicrophone != null) { pendingMicrophone.deny(); pendingMicrophone = null; }
        if (web != null) { detachAndDestroy(web); web = null; }
        if (pageStatus == null) {
            pageStatus = new TextView(this); pageStatus.setTextColor(Color.WHITE);
            pageStatus.setPadding(16, 8, 16, 8); root.addView(pageStatus);
        }
        pageStatus.setVisibility(android.view.View.VISIBLE); pageStatus.setText("正在加载…");
        if (browserPane == null) {
            browserPane = new FrameLayout(this);
            root.addView(browserPane, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        web = createWeb();
        browserPane.addView(web, new FrameLayout.LayoutParams(-1, -1));
        updateAddress(target.toString());
        web.loadUrl(target.toString());
    }

    private WebView createWeb() {
        WebView page = new WebView(this);
        page.setBackgroundColor(0xff0b1012);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        page.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message result) {
                if (!userGesture || childWindows.size() >= 3) return false;
                WebView child = createWeb(); childWindows.add(child);
                browserPane.addView(child, new FrameLayout.LayoutParams(-1, -1));
                addressBar.setText("正在打开应用内新页面…");
                WebView.WebViewTransport transport = (WebView.WebViewTransport) result.obj;
                transport.setWebView(child); result.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) { closeChildWindow(window); }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> grantMicrophone(request));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMicrophone == request) pendingMicrophone = null;
            }
        });
        page.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (view == activeWeb()) { nativeHearing.stop(); }
                if (view == activeWeb()) updateAddress(url);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (view == activeWeb()) updateAddress(url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view == activeWeb()) {
                    updateAddress(url);
                    if ("正在加载…".contentEquals(pageStatus.getText())) pageStatus.setVisibility(android.view.View.GONE);
                    if (voiceScript != null && sameOrigin(Uri.parse(url)))
                        if (!genericPage) view.evaluateJavascript(voiceScript, null);
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && view == activeWeb()) {
                    pageStatus.setVisibility(android.view.View.VISIBLE);
                    pageStatus.setText("网页加载失败，请检查网络或服务地址。点“返回 NOMI”可回到球球。");
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri destination = request.getUrl();
                // HTTPS pages, including provider pages and redirects, stay in this app.
                if (validHttps(destination.toString()) || "about:blank".equals(destination.toString())) return false;
                Toast.makeText(AiriActivity.this, "该链接无法在应用内打开，请使用 HTTPS 网页地址", Toast.LENGTH_LONG).show();
                return true;
            }
        });
        return page;
    }

    private WebView activeWeb() { return childWindows.isEmpty() ? web : childWindows.get(childWindows.size() - 1); }
    private void updateAddress(String address) {
        Uri uri = Uri.parse(address);
        // Do not display sign-in query values or fragments in the native toolbar.
        addressBar.setText(uri.getHost() == null ? "应用内网页" : uri.buildUpon().clearQuery().fragment(null).build().toString());
    }
    private void detachAndDestroy(WebView page) {
        if (page.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) page.getParent()).removeView(page);
        page.stopLoading(); page.destroy();
    }
    private void closeChildWindow(WebView child) {
        if (childWindows.remove(child)) {
            if (pendingMicrophone != null) { pendingMicrophone.deny(); pendingMicrophone = null; }
            detachAndDestroy(child);
            if (activeWeb() != null) updateAddress(activeWeb().getUrl() == null ? "about:blank" : activeWeb().getUrl());
        }
    }
    private void closeChildWindows() {
        while (!childWindows.isEmpty()) closeChildWindow(childWindows.get(childWindows.size() - 1));
    }
    @Override public void onBackPressed() {
        WebView active = activeWeb();
        if (active != null && active.canGoBack()) active.goBack();
        else if (!childWindows.isEmpty()) closeChildWindow(active);
        else super.onBackPressed();
    }

    private boolean sameOrigin(Uri url) {
        return allowedOrigin != null && "https".equalsIgnoreCase(url.getScheme())
            && allowedOrigin.getHost().equalsIgnoreCase(url.getHost())
            && effectivePort(url) == effectivePort(allowedOrigin);
    }
    private static int effectivePort(Uri url) { return url.getPort() < 0 ? 443 : url.getPort(); }
    private void grantMicrophone(PermissionRequest request) {
        if (nativeHearing.isEnabled()) {
            request.deny();
            Toast.makeText(this, "请先暂停手机听觉，再启用网页麦克风", Toast.LENGTH_LONG).show(); return;
        }
        if (!sameOrigin(request.getOrigin())) { request.deny(); return; }
        boolean audio = false;
        for (String resource : request.getResources())
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
        if (!audio) { request.deny(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            if (pendingMicrophone != null) pendingMicrophone.deny();
            pendingMicrophone = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == NATIVE_MICROPHONE_REQUEST) {
            boolean pending = nativePermissionPending; nativePermissionPending = false;
            if (pending && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                if (foreground) nativeHearing.start();
                else resumeNativeAfterPermission = true;
            }
            else showHearingStatus("麦克风权限未开启，请在手机应用权限中允许后重试");
            return;
        }

        if (code != MICROPHONE_REQUEST || pendingMicrophone == null) return;
        PermissionRequest request = pendingMicrophone; pendingMicrophone = null;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && sameOrigin(request.getOrigin()))
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else {
            request.deny();
            Toast.makeText(this, "麦克风权限未开启，请在手机应用权限中允许后重试", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onPause() {
        foreground = false; nativeHearing.stop();
        if (web != null) web.onPause();
        for (WebView child : childWindows) child.onPause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true; if (web != null) web.onResume();
        for (WebView child : childWindows) child.onResume();
        if (resumeNativeAfterPermission) { resumeNativeAfterPermission = false; nativeHearing.start(); }
    }
    @Override protected void onDestroy() {
        nativeHearing.stop();
        if (pendingMicrophone != null) { pendingMicrophone.deny(); pendingMicrophone = null; }
        closeChildWindows();
        if (web != null) { detachAndDestroy(web); web = null; }
        super.onDestroy();
    }
}
