package ke.tafiti.pumppay

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Tafiti site, opened inside the app rather than thrown to Chrome.
 *
 * URLS LIVE HERE, one place, because they are the only thing likely to change.
 * The site has no shop page today, so Shop points at services - the nearest
 * equivalent. Replace the constant when a real one exists; nothing else needs
 * to know.
 */
object Site {
    const val BASE = "https://www.tafitirnihub.co.ke"

    const val ABOUT = "$BASE/about.html"

    // No shop.html on the site. Services is the closest thing to one, and a
    // wrong-but-useful page beats a 404 that looks like the app is broken.
    const val SHOP = "$BASE/services.html"
}

/**
 * A minimal in-app browser.
 *
 * JavaScript and DOM storage are on because the site needs them - it is a
 * Firebase app, and with them off it renders as an empty shell rather than
 * failing visibly, which is the worst of both worlds.
 *
 * Navigation stays inside: links open here, and the system back button walks
 * the page history before it leaves the screen. A browser that dumps you out
 * of the app on the first tap is not worth embedding.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(url: String) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Takes priority over the app's own back stack while there is page history
    // to unwind. Compose gives the innermost enabled handler the press, so this
    // wins over the Shell's until the page runs out of history.
    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        if (failed) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Page unavailable",
                    color = C.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This part of the app needs an internet connection. The " +
                        "board itself does not - readings and controls keep " +
                        "working over Bluetooth.",
                    color = C.Dim,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(150.dp)) {
                        WideButton("Try again") {
                            failed = false
                            loading = true
                            webView?.loadUrl(url)
                        }
                    }
                    Box(Modifier.width(150.dp)) {
                        WideButton("Open in browser", accent = C.Dim) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        }
                    }
                }
            }
            return@Box
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // MATCH_PARENT, and this is not boilerplate - it is the fix
                    // for the site's menu not appearing.
                    //
                    // A WebView created without layout params inside AndroidView
                    // is measured as wrap-content, so its viewport height is
                    // zero as far as CSS is concerned. Ordinary content still
                    // renders, because document flow does not care - but every
                    // vh unit collapses. The nav is height:100vh with
                    // padding-top:80px, so it slid in at exactly 80px tall and
                    // hid behind the header: menu open, nothing visible.
                    //
                    // Measured, not guessed: a JS probe reported h=80px before
                    // this line and h=730.667px after it.
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    // useWideViewPort and loadWithOverviewMode are deliberately
                    // NOT set. They exist for pages with no viewport meta, where
                    // WebView otherwise assumes 980px; every page on this site
                    // declares width=device-width, so they would only add a
                    // zoom-to-fit nobody asked for.
                    //
                    // Worth recording that this was NOT what broke the menu -
                    // a probe measured innerWidth at 384, so the mobile media
                    // query had been matching the whole time. Leaving them off
                    // is right, but it fixed nothing.

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false   // keep every link in here

                        override fun onPageFinished(view: WebView?, u: String?) {
                            loading = false
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            // Only the main document counts. A failed image or
                            // analytics script must not replace a page that is
                            // otherwise perfectly readable.
                            if (request?.isForMainFrame == true) {
                                failed = true
                                loading = false
                            }
                        }
                    }

                    webView = this
                    loadUrl(url)
                }
            }
        )

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = C.Ok,
                trackColor = C.Line
            )
        }
    }
}
