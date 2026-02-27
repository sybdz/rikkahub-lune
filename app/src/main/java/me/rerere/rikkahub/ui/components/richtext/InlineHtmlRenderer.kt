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

// Matches <svg followed by whitespace, > or / — avoids matching hypothetical tags like <svgIcon>
private val SVG_TAG_REGEX = Regex("<svg[\\s>/]", RegexOption.IGNORE_CASE)

private val COMPLEX_CSS_INDICATORS = listOf(
    "gradient(", "box-shadow", "text-shadow",
    "border-radius", "transform:", "animation:",
    "transition:", "display:", "position:",
    "backdrop-filter", "filter:",
)

/**
 * Checks if the given code content looks like a full HTML document,
 * SVG content, or HTML with complex CSS styling that needs WebView rendering.
 */
fun isFrontendHtml(content: String): Boolean {
    val lower = content.lowercase()
    if (listOf("html>", "<head>", "<body").any { lower.contains(it) }) return true
    if (SVG_TAG_REGEX.containsMatchIn(lower)) return true
    if (isRichStyledHtml(lower)) return true
    return false
}

/**
 * Checks if HTML content contains complex CSS that needs WebView rendering
 * (gradients, shadows, transforms, etc. that SimpleHtmlBlock cannot handle).
 */
fun isRichStyledHtml(content: String): Boolean {
    val lower = content.lowercase()
    if (!lower.contains("style=") && !lower.contains("<style")) return false
    return COMPLEX_CSS_INDICATORS.any { lower.contains(it) }
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
        mutableIntStateOf(htmlHeightCache.getIfPresent(code) ?: 100)
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
        val wrapped = wrapContentForWebView(code)
        injectHeightReportingScript(wrapped)
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
            .height(height.coerceIn(10.dp, 800.dp)),
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

private fun wrapContentForWebView(content: String): String {
    val lower = content.lowercase().trimStart()

    // Already a full HTML document
    if (listOf("<!doctype", "<html").any { lower.startsWith(it) } ||
        listOf("html>", "<head>", "<body").any { lower.contains(it) }
    ) {
        return content
    }

    // SVG content (raw or XML-wrapped like <?xml ...><svg ...>)
    if (SVG_TAG_REGEX.containsMatchIn(lower)) {
        return buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            append("<style>html,body{margin:0;padding:0;height:auto;background:transparent}body{display:flex;justify-content:center}svg{max-width:100%;height:auto}</style>")
            append("</head><body>")
            append(content)
            append("</body></html>")
        }
    }

    // HTML fragment
    return buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        append("<style>html,body{margin:0;height:auto;background:transparent}body{padding:8px;word-wrap:break-word}</style>")
        append("</head><body>")
        append(content)
        append("</body></html>")
    }
}

private fun injectHeightReportingScript(html: String): String {
    val script = """
        <script>
        (function() {
            window.reportHeightToAndroid = function() {
                // Only measure body height, NOT documentElement.
                // documentElement.scrollHeight includes viewport height,
                // which creates a circular dependency: Compose sets WebView height →
                // viewport grows → JS reports inflated height → Compose keeps it large.
                // body.scrollHeight correctly reports only the content height.
                var height = Math.max(
                    document.body.scrollHeight || 0,
                    document.body.offsetHeight || 0
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
                }).observe(document.body);
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
