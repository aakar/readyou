package me.ash.reader.ui.page.settings.accounts.addition

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.util.concurrent.atomic.AtomicBoolean

class FeedlyWebLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCESS_TOKEN = "feedly_access_token"
        private const val FEEDLY_HOME = "https://feedly.com"
    }

    private lateinit var webView: WebView
    private val tokenDelivered = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = Toolbar(this)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView = WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Google blocks OAuth in embedded WebViews via user-agent detection.
                // A Chrome user agent bypasses the disallowed_useragent error.
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            wv.addJavascriptInterface(TokenExtractor(), "ReadYouAndroid")
            wv.webViewClient = object : WebViewClient() {

                // Primary: intercept at the network layer — no JS timing issues,
                // works regardless of how feedly.com's React app makes requests.
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    request.requestHeaders["Authorization"]
                        ?.takeIf { it.startsWith("OAuth ") }
                        ?.let { deliverToken(it.removePrefix("OAuth ")) }
                    return null
                }

                // Secondary: JS hook as fallback for cases the network layer misses.
                override fun onPageFinished(view: WebView, url: String) {
                    injectTokenHook(view)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false
            }
            wv.loadUrl(FEEDLY_HOME)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    toolbar,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    webView,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Overriding to handle WebView back navigation", replaceWith = ReplaceWith("super.onBackPressed()"))
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun deliverToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isNotBlank() && tokenDelivered.compareAndSet(false, true)) {
            runOnUiThread {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACCESS_TOKEN, trimmed))
                finish()
            }
        }
    }

    private fun injectTokenHook(view: WebView) {
        // language=JavaScript
        view.evaluateJavascript(
            """
            (function() {
                if (window.__readyouHooked__) return;
                window.__readyouHooked__ = true;

                var _origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                    if (header.toLowerCase() === 'authorization' && value.indexOf('OAuth ') === 0) {
                        ReadYouAndroid.onAccessTokenFound(value.substring(6));
                    }
                    return _origSetHeader.apply(this, arguments);
                };

                var _origFetch = window.fetch;
                if (_origFetch) {
                    window.fetch = function(resource, options) {
                        try {
                            var auth = null;
                            var h = options && options.headers;
                            if (h) {
                                auth = (typeof h.get === 'function')
                                    ? h.get('Authorization')
                                    : (h['Authorization'] || h['authorization']);
                            }
                            if (!auth && resource && typeof resource === 'object' && resource.headers) {
                                auth = (typeof resource.headers.get === 'function')
                                    ? resource.headers.get('Authorization') : null;
                            }
                            if (auth && auth.indexOf('OAuth ') === 0) {
                                ReadYouAndroid.onAccessTokenFound(auth.substring(6));
                            }
                        } catch(e) {}
                        return _origFetch.apply(this, arguments);
                    };
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    inner class TokenExtractor {
        @JavascriptInterface
        fun onAccessTokenFound(token: String) {
            deliverToken(token)
        }
    }
}
