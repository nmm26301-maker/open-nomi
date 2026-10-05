package ai.opennomi.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import ai.opennomi.app.voice.FishPortalPolicy

/** Account, signup, API-key application and popup login windows all stay inside OpenNomi. */
@SuppressLint("SetJavaScriptEnabled")
class FishAccountView(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onHost: (String) -> Unit,
    private val chooseFile: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
) : FrameLayout(context) {
    private val pages = mutableListOf<WebView>()
    private var disposed = false
    init { newPage().loadUrl(FishPortalPolicy.KEYS) }
    private fun current() = pages.lastOrNull()
    private fun newPage(): WebView {
        current()?.visibility = View.GONE
        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (FishPortalPolicy.allows(request.url.toString())) return false
                onStatus("这个链接无法在内部打开，请在网页中选择邮箱登录或其它站内方式")
                return true
            }
            override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
                if (view === current()) { onStatus("正在加载…"); onHost(FishPortalPolicy.host(url)) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view === current()) { onStatus(""); onHost(FishPortalPolicy.host(url)) }
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && view === current()) onStatus("页面加载失败，请检查网络后点刷新")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && view === current()) onStatus("页面暂不可用（HTTP ${response.statusCode}），可点刷新重试")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, message: Message): Boolean {
                if (!userGesture || disposed || pages.size >= 4) return false
                val child = newPage()
                (message.obj as WebView.WebViewTransport).webView = child
                message.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { if (window === current() && pages.size > 1) closePopup() }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooseFile(callback, params)
                return true
            }
        }
        pages += web
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return web
    }
    fun open(url: String) {
        if (disposed || !FishPortalPolicy.allows(url)) return
        while (pages.size > 1) closePopup()
        current()?.loadUrl(url)
    }
    fun reload() { current()?.reload() }
    fun back(): Boolean {
        val web = current() ?: return false
        if (web.canGoBack()) { web.goBack(); return true }
        if (pages.size > 1) { closePopup(); return true }
        return false
    }
    private fun closePopup() {
        val web = pages.removeAt(pages.lastIndex)
        removeView(web); web.stopLoading(); web.destroy()
        current()?.let { it.visibility = View.VISIBLE; onHost(FishPortalPolicy.host(it.url.orEmpty())); onStatus("") }
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        CookieManager.getInstance().flush()
        pages.forEach { removeView(it); it.stopLoading(); it.destroy() }; pages.clear()
    }
}
