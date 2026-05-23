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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.atomic.AtomicBoolean

class FeedlyWebLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCESS_TOKEN = "feedly_access_token"
        private const val FEEDLY_HOME = "https://feedly.com"

        // Injected before any page script runs (when the feature is supported).
        // Hooks XHR and fetch so we capture the Authorization header no matter
        // which HTTP library feedly.com's React bundle uses.
        private val EARLY_HOOK_JS = """
            (function() {
                if (window.__readyouHooked__) return;
                window.__readyouHooked__ = true;

                function tryDeliver(value) {
                    if (value && value.indexOf('OAuth ') === 0) {
                        ReadYouAndroid.onAccessTokenFound(value.substring(6));
                    }
                }

                // Hook XHR
                var _origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                    if (header.toLowerCase() === 'authorization') tryDeliver(value);
                    return _origSetHeader.apply(this, arguments);
                };

                // Hook fetch — also handles Request-object style calls
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
                            tryDeliver(auth);
                        } catch (e) {}
                        return _origFetch.apply(this, arguments);
                    };
                }

                // Hook Headers so we catch tokens set via new Headers({Authorization:…})
                var _origHeadersSet = Headers.prototype.set;
                Headers.prototype.set = function(name, value) {
                    if (name.toLowerCase() === 'authorization') tryDeliver(value);
                    return _origHeadersSet.apply(this, arguments);
                };
                var _origHeadersAppend = Headers.prototype.append;
                Headers.prototype.append = function(name, value) {
                    if (name.toLowerCase() === 'authorization') tryDeliver(value);
                    return _origHeadersAppend.apply(this, arguments);
                };
            })();
        """.trimIndent()

        // Scans localStorage for anything that looks like a Feedly OAuth token.
        // Called after page navigation so we catch tokens stored at login time.
        private val LOCALSTORAGE_SCAN_JS = """
            (function() {
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        var raw = localStorage.getItem(key);
                        if (!raw) continue;
                        // Try JSON first
                        try {
                            var obj = JSON.parse(raw);
                            var candidates = [obj.access_token, obj.accessToken,
                                              obj.token, obj.oauth_token];
                            for (var j = 0; j < candidates.length; j++) {
                                var t = candidates[j];
                                if (t && typeof t === 'string' && t.length > 20) {
                                    ReadYouAndroid.onAccessTokenFound(t);
                                    return;
                                }
                            }
                        } catch (e) {
                            // raw string — heuristic: long token-shaped value
                            if (raw.length > 30 && /^[A-Za-z0-9._\-]+${'$'}/.test(raw)) {
                                ReadYouAndroid.onAccessTokenFound(raw);
                                return;
                            }
                        }
                    }
                } catch (e) {}
                return null;
            })();
        """.trimIndent()
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
                // Google blocks OAuth in WebViews via user-agent detection.
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            // Inject our hook before ANY page script executes — this is the key fix.
            // Without this, feedly.com's React bundle initialises its HTTP client
            // (capturing the original fetch reference) before onPageFinished fires.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, EARLY_HOOK_JS, setOf("*"))
            }

            wv.addJavascriptInterface(TokenExtractor(), "ReadYouAndroid")

            wv.webViewClient = object : WebViewClient() {

                // Network-layer interception — catches Authorization headers without
                // relying on JS timing. Note: not all WebView versions forward custom
                // headers here, so this is one of several capture paths.
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    request.requestHeaders["Authorization"]
                        ?.takeIf { it.startsWith("OAuth ") }
                        ?.let { deliverToken(it.removePrefix("OAuth ")) }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // Fallback hook injection for devices that don't support
                    // DOCUMENT_START_SCRIPT (fires after page load, may be too late
                    // for the first API call, but catches subsequent ones).
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        view.evaluateJavascript(EARLY_HOOK_JS, null)
                    }
                    // Scan localStorage — feedly.com stores the token there at login time.
                    view.evaluateJavascript(LOCALSTORAGE_SCAN_JS, null)
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

    inner class TokenExtractor {
        @JavascriptInterface
        fun onAccessTokenFound(token: String) {
            deliverToken(token)
        }
    }
}
