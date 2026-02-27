package me.rerere.rikkahub.ui.components.richtext

import android.webkit.JavascriptInterface
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit

private val htmlHeightCache = SimpleCache.builder<String, Int>()
    .expireAfterWrite(1, TimeUnit.DAYS)
    .maximumSize(100)
    .build()

/**
 * Checks if the given code content looks like a full HTML document.
 * Similar to JS-Slash-Runner's isFrontend() detection.
 */
fun isFrontendHtml(content: String): Boolean {
    return listOf("html>", "<head>", "<body").any { tag ->
        content.contains(tag, ignoreCase = true)
    }
}

/**
 * Renders HTML content inline using a WebView with auto-height adjustment.
 */
@Composable
fun InlineHtmlRenderer(
    code: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    var contentHeight by remember {
        mutableIntStateOf(htmlHeightCache.getIfPresent(code) ?: 200)
    }
    val height = with(density) { contentHeight.toDp() }

    val jsInterface = remember {
        HtmlRendererInterface(
            onHeightChanged = { newHeight ->
                contentHeight = (newHeight * density.density).toInt()
                htmlHeightCache.put(code, contentHeight)
            }
        )
    }

    val processedHtml = remember(code) {
        injectHeightReportingScript(code)
    }

    val webViewState = rememberWebViewState(
        data = processedHtml,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf("AndroidHtmlInterface" to jsInterface),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
        }
    )

    WebView(
        state = webViewState,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .animateContentSize()
            .height(height.coerceIn(50.dp, 800.dp)),
        onUpdated = {
            it.evaluateJavascript("reportHeightToAndroid();", null)
        }
    )
}

private class HtmlRendererInterface(
    private val onHeightChanged: (Int) -> Unit,
) {
    @JavascriptInterface
    fun updateHeight(height: Int) {
        onHeightChanged(height)
    }
}

private fun injectHeightReportingScript(html: String): String {
    val script = """
        <script>
        (function() {
            window.reportHeightToAndroid = function() {
                var height = Math.max(
                    document.body.scrollHeight || 0,
                    document.body.offsetHeight || 0,
                    document.documentElement.scrollHeight || 0,
                    document.documentElement.offsetHeight || 0
                );
                if (height > 0 && window.AndroidHtmlInterface) {
                    AndroidHtmlInterface.updateHeight(height);
                }
            };

            window.addEventListener('load', function() {
                reportHeightToAndroid();
            });

            if (window.ResizeObserver) {
                new ResizeObserver(function() {
                    reportHeightToAndroid();
                }).observe(document.documentElement);
            }

            window.addEventListener('resize', reportHeightToAndroid);

            setTimeout(reportHeightToAndroid, 300);
            setTimeout(reportHeightToAndroid, 1000);
            setTimeout(reportHeightToAndroid, 3000);
        })();
        </script>
    """.trimIndent()

    val lowerHtml = html.lowercase()
    return when {
        lowerHtml.contains("</body>") -> {
            val idx = lowerHtml.lastIndexOf("</body>")
            html.substring(0, idx) + script + html.substring(idx)
        }

        lowerHtml.contains("</html>") -> {
            val idx = lowerHtml.lastIndexOf("</html>")
            html.substring(0, idx) + script + html.substring(idx)
        }

        else -> html + script
    }
}
