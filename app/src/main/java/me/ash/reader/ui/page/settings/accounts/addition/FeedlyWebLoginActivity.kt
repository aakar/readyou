package me.ash.reader.ui.page.settings.accounts.addition

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class FeedlyWebLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCESS_TOKEN = "feedly_access_token"
        private const val FEEDLY_HOME = "https://feedly.com"
    }

    private lateinit var webView: WebView
    private var tokenDelivered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
            }
            wv.addJavascriptInterface(TokenExtractor(), "ReadYouAndroid")
            wv.webViewClient = object : WebViewClient() {
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
        setContentView(webView)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Overriding to handle WebView history", replaceWith = ReplaceWith("super.onBackPressed()"))
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
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
                            var h = options && options.headers;
                            if (h) {
                                var auth = (typeof h.get === 'function')
                                    ? h.get('Authorization')
                                    : (h['Authorization'] || h['authorization']);
                                if (auth && auth.indexOf('OAuth ') === 0) {
                                    ReadYouAndroid.onAccessTokenFound(auth.substring(6));
                                }
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
            if (tokenDelivered || token.isBlank()) return
            tokenDelivered = true
            runOnUiThread {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_ACCESS_TOKEN, token.trim()),
                )
                finish()
            }
        }
    }
}
