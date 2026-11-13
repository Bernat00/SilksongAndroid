// GogLoginActivity — a WebView around GOG's login page, closed by its
// redirect.
//
// The OAuth flow needs a browser to render GOG's login and any 2FA prompts,
// and a place to catch the redirect. Desktop clients open a real browser and
// poll a local endpoint; on a phone the honest equivalent is an in-app
// WebView that watches for the redirect URL itself (the flow's redirect_uri,
// embed.gog.com/on_login_success, is a GOG page the SDK client redirects to
// with the code in the query). Loading that URL outside the WebView would
// leave the browser at a GOG endpoint holding the user's token — so this
// intercepts it first, strips the code, hands it to [GogAuth], and closes.
//
// Nothing is rendered after login; the activity finishes back to Settings.

package dev.silksong.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class GogLoginActivity : Activity() {

    companion object {
        const val EXTRA_GOG_ID = "gogId"
        const val RESULT_SIGNED_IN = 7
        const val RESULT_FAILED = 8
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gogId = intent.getStringExtra(EXTRA_GOG_ID) ?: ""

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        setContentView(web)

        // The token exchange reaches GOG over the network, and Android
        // forbids network I/O on the thread that owns the WebView: the
        // first build died with NetworkOnMainThreadException the moment
        // the redirect was caught. The exchange runs on its own thread and
        // only the finishing hop hops back.
        val exchangeGuard = java.util.concurrent.atomic.AtomicBoolean(false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return super.shouldOverrideUrlLoading(view, request)
                if (exchangeGuard.get()) return true   // exchanging; stop navigating
                if (url.host?.endsWith("embed.gog.com") == true &&
                    url.path?.contains("on_login_success") == true
                ) {
                    val code = url.getQueryParameter("code")
                    if (code != null && exchangeGuard.compareAndSet(false, true)) {
                        Thread {
                            val ok = GogAuth.exchangeCode(this@GogLoginActivity, code, gogId)
                            runOnUiThread {
                                setResult(if (ok) RESULT_SIGNED_IN else RESULT_FAILED)
                                finish()
                            }
                        }.start()
                        return true   // the login page's redirect is ours; never navigate there
                    }
                }
                return super.shouldOverrideUrlLoading(view, request) // web handles navigation
            }
        }
        web.loadUrl(GogAuth.loginUrl())
    }
}
