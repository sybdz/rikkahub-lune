package me.rerere.rikkahub.ui.components.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

private const val TAG = "WebView"

internal class MyWebChromeClient(private val state: WebViewState) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        state.loadingProgress = newProgress / 100f
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        state.pageTitle = title
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        state.pushConsoleMessage(consoleMessage)
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR || consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
            Log.e(
                TAG,
                "onConsoleMessage:  ${consoleMessage.message()}  ${consoleMessage.lineNumber()}  ${consoleMessage.sourceId()}"
            )
        }
        return super.onConsoleMessage(consoleMessage);
    }
}

internal class MyWebViewClient(private val state: WebViewState) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        state.isLoading = true
        state.currentUrl = url // Update current URL
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        state.isLoading = false
        state.loadingProgress = 0f // Reset progress when finished
        state.pageTitle = view?.title // Update title
        state.canGoBack = view?.canGoBack() == true
        state.canGoForward = view?.canGoForward() == true
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    preferInnerScrollWhenScrollable: Boolean = false,
    showScrollBars: Boolean = true,
    onCreated: (WebView) -> Unit = {},
    onUpdated: (WebView) -> Unit = {},
) {
    // Remember the clients based on the state
    val webChromeClient = remember { MyWebChromeClient(state) }
    val webViewClient = remember { MyWebViewClient(state) }
    val touchListener = remember(preferInnerScrollWhenScrollable) {
        createScrollPriorityTouchListener(preferInnerScrollWhenScrollable)
    }
    var lastLoadedDataKey by remember(state) { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier.clipToBounds()
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                    )

                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    state.webView = this // Assign the WebView instance to the state

                    onCreated(this)

                    settings.javaScriptEnabled = true // Enable JavaScript
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.apply(state.settings)

                    isVerticalScrollBarEnabled = showScrollBars
                    isHorizontalScrollBarEnabled = showScrollBars
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipChildren = true
                    clipToPadding = true
                    setOnTouchListener(touchListener)

                    // Use the created clients
                    this.webChromeClient = webChromeClient
                    this.webViewClient = webViewClient

                    state.interfaces.forEach { (name, obj) ->
                        addJavascriptInterface(obj, name)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onReset = {
                state.interfaces.forEach { (name, _) ->
                    it.removeJavascriptInterface(name)
                }
                Log.d(TAG, "AndroidView: Resetting WebView")
            },
            update = { webView ->
                state.webView = webView
                state.interfaces.forEach { (name, obj) ->
                    webView.addJavascriptInterface(obj, name)
                }
                Log.d(TAG, "AndroidView: Updating WebView")
                // Ensure clients are updated if state changes (though unlikely here)
                // webView.webChromeClient = webChromeClient
                // webView.webViewClient = webViewClient

                // Update settings that might change
                webView.settings.javaScriptEnabled = state.javaScriptEnabled
                webView.isVerticalScrollBarEnabled = showScrollBars
                webView.isHorizontalScrollBarEnabled = showScrollBars
                webView.overScrollMode = View.OVER_SCROLL_NEVER
                webView.clipChildren = true
                webView.clipToPadding = true
                webView.setOnTouchListener(touchListener)

                when (val content = state.content) {
                    is WebContent.Url -> {
                        lastLoadedDataKey = null
                        val url = content.url
                        // Only load new URL if it's different from the current one or if the state forces reload
                        // Also check if the webView's url is null or blank, which might happen initially
                        val currentWebViewUrl = webView.url
                        if (url.isNotEmpty() && (currentWebViewUrl.isNullOrBlank() || url != currentWebViewUrl || state.forceReload)) {
                            webView.loadUrl(content.url, content.additionalHttpHeaders)
                            state.forceReload = false // Reset force reload flag
                        }
                    }

                    is WebContent.Data -> {
                        val dataKey = content.reloadKey()
                        if (state.forceReload || dataKey != lastLoadedDataKey) {
                            webView.loadDataWithBaseURL(
                                content.baseUrl,
                                content.data,
                                content.mimeType,
                                content.encoding,
                                content.historyUrl
                            )
                            lastLoadedDataKey = dataKey
                            state.forceReload = false
                        }
                    }

                    WebContent.NavigatorOnly -> {
                        // NO-OP: State changes related to navigation are handled by the methods in WebViewState
                    }
                }
                onUpdated(webView)
            }
        )

        // Loading Progress Indicator
        if (state.isLoading) {
            LinearProgressIndicator(
                progress = { state.loadingProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun createScrollPriorityTouchListener(
    preferInnerScrollWhenScrollable: Boolean,
): View.OnTouchListener? {
    if (!preferInnerScrollWhenScrollable) return null

    var lastTouchY = 0f
    var hasLastTouchY = false
    var touchSlop = -1

    return View.OnTouchListener { view, motionEvent ->
        val webView = view as WebView
        if (touchSlop < 0) {
            touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        }

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = motionEvent.y
                hasLastTouchY = true
                // Let parent keep intercept ability initially.
                // We only lock to WebView after we detect a directional move that WebView can consume.
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasLastTouchY) {
                    lastTouchY = motionEvent.y
                    hasLastTouchY = true
                }

                val deltaY = motionEvent.y - lastTouchY
                if (abs(deltaY) >= touchSlop) {
                    // Finger drag direction decides the intended scroll direction:
                    // drag down -> scroll up (-1), drag up -> scroll down (1)
                    val intendedScrollDirection = if (deltaY > 0) -1 else 1
                    val canScrollInCurrentDirection = webView.canScrollVertically(intendedScrollDirection)
                    view.parent?.requestDisallowInterceptTouchEvent(canScrollInCurrentDirection)
                    lastTouchY = motionEvent.y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hasLastTouchY = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        false
    }
}

private fun WebContent.Data.reloadKey(): Int {
    var result = data.hashCode()
    result = 31 * result + (baseUrl?.hashCode() ?: 0)
    result = 31 * result + encoding.hashCode()
    result = 31 * result + (mimeType?.hashCode() ?: 0)
    result = 31 * result + (historyUrl?.hashCode() ?: 0)
    return result
}

// --- State and Content Definition ---
sealed class WebContent {
    data class Url(
        val url: String,
        val additionalHttpHeaders: Map<String, String> = emptyMap(),
        val clearHistory: Boolean = false
    ) : WebContent()

    data class Data(
        val data: String,
        val baseUrl: String? = null,
        val encoding: String = "utf-8",
        val mimeType: String? = null,
        val historyUrl: String? = null
    ) : WebContent()

    data object NavigatorOnly : WebContent()
}

@Stable // Mark as Stable for better Compose performance
class WebViewState(
    initialContent: WebContent = WebContent.NavigatorOnly,
    val interfaces: Map<String, Any> = emptyMap(),
    val settings: WebSettings.() -> Unit = {}
) {
    // --- Content State ---
    var content: WebContent by mutableStateOf(initialContent)
    internal var forceReload: Boolean by mutableStateOf(false) // Internal state to force URL reload if needed

    // --- Loading State ---
    var isLoading: Boolean by mutableStateOf(false)
        internal set // Only WebViewClients should modify this
    var loadingProgress: Float by mutableFloatStateOf(0f)
        internal set

    // --- Page Information ---
    var pageTitle: String? by mutableStateOf(null)
        internal set
    var currentUrl: String? by mutableStateOf(null)
        internal set

    // --- Navigation State ---
    var canGoBack: Boolean by mutableStateOf(false)
        internal set
    var canGoForward: Boolean by mutableStateOf(false)
        internal set

    // --- Console Message ---
    var consoleMessages: List<ConsoleMessage> by mutableStateOf(emptyList())
        internal set

    // --- Settings ---
    var javaScriptEnabled: Boolean by mutableStateOf(true) // Example setting

    // --- WebView Instance ---
    // Hold the WebView instance internally to perform actions.
    // Be cautious with this reference, ensure it doesn't leak context.
    internal var webView: WebView? by mutableStateOf(null)

    // --- Public Actions ---

    fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String> = emptyMap()
    ) {
        // Determine if reload is needed: same URL or explicit force flag set elsewhere
        forceReload =
            (content is WebContent.Url && (content as WebContent.Url).url == url) || forceReload
        content = WebContent.Url(url, additionalHttpHeaders)
    }

    fun loadData(
        data: String,
        baseUrl: String? = null,
        encoding: String = "utf-8",
        mimeType: String? = null,
        historyUrl: String? = null
    ) {
        content = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl)
    }

    // --- Navigation Methods ---
    fun goBack() {
        webView?.goBack()
    }

    fun goForward() {
        webView?.goForward()
    }

    fun reload() {
        // Set forceReload flag for URL content type to ensure `update` block reloads
        forceReload = true
        // Trigger recomposition/update by changing the content reference slightly,
        // even if the URL is the same. Assigning the same Url object might not trigger update.
        // Or simply call webView?.reload() directly.
        webView?.reload()
        // If content is Data, reloading might mean re-setting the data.
        if (content is WebContent.Data) {
            // Re-assign to trigger update block if necessary
            content = (content as WebContent.Data).copy()
        }
    }

    fun stopLoading() {
        webView?.stopLoading()
    }

    fun clearHistory() {
        webView?.clearHistory()
    }

    fun pushConsoleMessage(message: ConsoleMessage) {
        consoleMessages = consoleMessages + message
        if (consoleMessages.size > 64) { // Limit to 64 messages
            consoleMessages = consoleMessages.takeLast(64)
        }
    }
}

@Composable
fun rememberWebViewState(
    url: String = "about:blank",
    additionalHttpHeaders: Map<String, String> = emptyMap(),
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(url, additionalHttpHeaders) { // Use keys for better recomposition control
    WebViewState(
        initialContent = WebContent.Url(url, additionalHttpHeaders),
        interfaces = interfaces,
        settings = settings
    )
}

@Composable
fun rememberWebViewState(
    data: String,
    baseUrl: String? = null,
    encoding: String = "utf-8",
    mimeType: String? = null,
    historyUrl: String? = null,
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(data, baseUrl, encoding, mimeType, historyUrl) { // Use keys
    WebViewState(
        initialContent = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl),
        interfaces = interfaces,
        settings = settings
    )
}
