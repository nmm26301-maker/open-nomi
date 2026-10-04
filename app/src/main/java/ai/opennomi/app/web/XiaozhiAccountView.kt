package ai.opennomi.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView

/** Keep the user's console/login inside the app; never launch an external browser. */
@SuppressLint("SetJavaScriptEnabled")
class XiaozhiAccountView(context: Context) : FrameLayout(context) {
    private val status = TextView(context).apply { setTextColor(android.graphics.Color.WHITE); text = "正在加载小智控制台…" }
    private val web = WebView(context)
    init {
        setBackgroundColor(android.graphics.Color.rgb(10, 12, 14))
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.scheme !in setOf("https", "http")
            }
            override fun onPageFinished(view: WebView, url: String) {
                status.text = ""
                view.evaluateJavascript("window.open=function(u){if(u)location.href=u;return window};document.querySelectorAll('a[target]').forEach(function(a){a.removeAttribute('target')});", null)
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status.text = "控制台加载失败，请检查网络后重新打开"
            }
        }
        web.webChromeClient = WebChromeClient()
        web.loadUrl("https://xiaozhi.me/")
    }
    fun dispose() { web.stopLoading(); removeAllViews(); web.destroy() }
}
