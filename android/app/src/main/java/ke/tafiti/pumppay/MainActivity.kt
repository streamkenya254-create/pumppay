package ke.tafiti.pumppay

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * PumpPay client app — wraps the PumpPay web portal (/app/) in a full-screen WebView.
 * Login, balance, M-Pesa payments, history and shop all come from the portal,
 * so updating the website updates the app instantly (no new APK needed).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private var loaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !loaded }

        val portal = getString(R.string.portal_url)
        val portalHost = Uri.parse(portal).host

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // Firebase auth persistence
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = settings.userAgentString + " PumpPayAndroid/1.0"
            setBackgroundColor(0xFF0B0B0B.toInt())
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val url = req.url
                    // Stay inside the app for our own site + Firebase auth pages
                    if (url.scheme == "https" && (url.host == portalHost || url.host?.endsWith("firebaseapp.com") == true)) return false
                    // tel:, WhatsApp, mailto:, maps, other sites → open the right app
                    return try { startActivity(Intent(Intent.ACTION_VIEW, url)); true }
                    catch (e: Exception) { Toast.makeText(this@MainActivity, "No app to open this link", Toast.LENGTH_SHORT).show(); true }
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { swipe.isRefreshing = loaded }
                override fun onPageFinished(view: WebView, url: String) { loaded = true; swipe.isRefreshing = false }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) {
                        loaded = true; swipe.isRefreshing = false
                        view.loadDataWithBaseURL(null, offlineHtml(), "text/html", "utf-8", null)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                // allow confirm()/alert() used by the portal
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result.confirm() }
                        .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }
            }
        }

        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(0xFF59F223.toInt())
            setProgressBackgroundColorSchemeColor(0xFF0B0B0B.toInt())
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { if (web.url?.startsWith("http") == true) web.reload() else web.loadUrl(portal) }
            // only pull-to-refresh when the page is scrolled to the top
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        }
        setContentView(swipe)

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(portal)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    private fun offlineHtml() = """
        <html><body style="margin:0;background:#0b0b0b;color:#fff;font-family:sans-serif;display:flex;
        align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px">
        <div><div style="width:72px;height:72px;border-radius:22px;background:#59F223;color:#000;font-size:36px;
        line-height:72px;margin:0 auto 18px">⚡</div><h2>No connection</h2>
        <p style="color:#aaa">${getString(R.string.offline)}</p></div></body></html>
    """.trimIndent()
}
