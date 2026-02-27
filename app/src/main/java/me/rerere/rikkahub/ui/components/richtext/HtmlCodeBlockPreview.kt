package me.rerere.rikkahub.ui.components.richtext

import android.webkit.JavascriptInterface
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import kotlin.math.max

private val FULL_HTML_DOCUMENT_TAG_REGEX = Regex(
    pattern = "<\\s*(html|head|body)(\\s|>|/)",
    option = RegexOption.IGNORE_CASE
)

private const val HTML_PREVIEW_BRIDGE_NAME = "RikkaHtmlPreview"
private const val DEFAULT_PREVIEW_HEIGHT_DP = 220
private const val MIN_PREVIEW_HEIGHT_DP = 120
private const val MAX_PREVIEW_HEIGHT_DP = 900

private const val INSTALL_HEIGHT_OBSERVER_SCRIPT = """
(function () {
  if (window.__rikkaHtmlPreviewObserverInstalled) {
    if (window.__rikkaReportHtmlPreviewHeight) {
      window.__rikkaReportHtmlPreviewHeight();
    }
    return;
  }
  window.__rikkaHtmlPreviewObserverInstalled = true;
  const bridge = window.RikkaHtmlPreview;
  if (!bridge || typeof bridge.updateHeight !== 'function') {
    return;
  }
  const schedule = (function () {
    let frameId = 0;
    return function () {
      if (frameId !== 0) {
        return;
      }
      frameId = requestAnimationFrame(function () {
        frameId = 0;
        if (window.__rikkaReportHtmlPreviewHeight) {
          window.__rikkaReportHtmlPreviewHeight();
        }
      });
    };
  })();
  window.__rikkaReportHtmlPreviewHeight = function () {
    const body = document.body;
    const html = document.documentElement;
    if (!body || !html) {
      return;
    }
    const height = Math.max(
      body.scrollHeight || 0,
      body.offsetHeight || 0,
      html.clientHeight || 0,
      html.scrollHeight || 0,
      html.offsetHeight || 0
    );
    bridge.updateHeight(Math.ceil(height));
  };
  if (typeof MutationObserver !== 'undefined') {
    const target = document.documentElement || document.body;
    if (target) {
      const observer = new MutationObserver(function () {
        schedule();
      });
      observer.observe(target, {
        childList: true,
        subtree: true,
        attributes: true,
        characterData: true,
      });
    }
  }
  window.addEventListener('load', schedule);
  window.addEventListener('resize', schedule);
  setTimeout(schedule, 0);
  setTimeout(schedule, 200);
  setTimeout(schedule, 1000);
})();
"""

private class HtmlCodeBlockPreviewBridge(
    private val onHeightChanged: (Int) -> Unit
) {
    @JavascriptInterface
    fun updateHeight(height: Int) {
        onHeightChanged(height)
    }
}

@Composable
fun HtmlCodeBlockPreview(
    htmlCode: String,
    modifier: Modifier = Modifier,
) {
    var contentHeightDp by remember(htmlCode) { mutableIntStateOf(DEFAULT_PREVIEW_HEIGHT_DP) }

    val jsInterface = remember(htmlCode) {
        HtmlCodeBlockPreviewBridge(
            onHeightChanged = { height ->
                contentHeightDp = height.coerceIn(MIN_PREVIEW_HEIGHT_DP, MAX_PREVIEW_HEIGHT_DP)
            }
        )
    }

    val htmlDocument = remember(htmlCode) { buildHtmlPreviewDocument(htmlCode) }
    val previewHeight = max(contentHeightDp, MIN_PREVIEW_HEIGHT_DP).dp

    WebView(
        state = rememberWebViewState(
            data = htmlDocument,
            baseUrl = "https://rikkahub.local/",
            mimeType = "text/html",
            encoding = "UTF-8",
            interfaces = mapOf(HTML_PREVIEW_BRIDGE_NAME to jsInterface),
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
        ),
        modifier = modifier
            .height(previewHeight),
        onUpdated = {
            it.evaluateJavascript(INSTALL_HEIGHT_OBSERVER_SCRIPT, null)
        }
    )
}

private fun buildHtmlPreviewDocument(code: String): String {
    if (FULL_HTML_DOCUMENT_TAG_REGEX.containsMatchIn(code)) {
        return code
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                }
                body {
                    box-sizing: border-box;
                    padding: 8px;
                }
            </style>
        </head>
        <body>
        $code
        </body>
        </html>
    """.trimIndent()
}
