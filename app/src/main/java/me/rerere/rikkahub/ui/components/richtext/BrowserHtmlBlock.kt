package me.rerere.rikkahub.ui.components.richtext

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.utils.base64Encode
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.util.Locale

private const val MIN_HTML_BLOCK_HEIGHT = 24
private const val DEFAULT_HTML_BLOCK_HEIGHT = 120
private const val VIEWPORT_HEIGHT_VARIABLE = "var(--TH-viewport-height)"
private val VH_VALUE_REGEX = Regex("(\\d+(?:\\.\\d+)?)vh\\b", RegexOption.IGNORE_CASE)
private val MIN_HEIGHT_DECLARATION_REGEX =
    Regex("(min-height\\s*:\\s*)([^;{}]*?\\d+(?:\\.\\d+)?vh)(?=\\s*[;}])", RegexOption.IGNORE_CASE)
private val INLINE_STYLE_REGEX = Regex(
    "(style\\s*=\\s*([\"']))([\\s\\S]*?)(\\2)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val INLINE_MIN_HEIGHT_REGEX =
    Regex("(min-height\\s*:\\s*)([^;]*?\\d+(?:\\.\\d+)?vh)", RegexOption.IGNORE_CASE)
private val JS_STYLE_MIN_HEIGHT_REGEX = Regex(
    "(\\.style\\.minHeight\\s*=\\s*([\"']))([\\s\\S]*?)(\\2)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val JS_SET_PROPERTY_MIN_HEIGHT_REGEX = Regex(
    "(setProperty\\s*\\(\\s*([\"'])min-height\\2\\s*,\\s*([\"']))([\\s\\S]*?)(\\3\\s*\\))",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private const val HTML_HELPER_STYLE_ID = "rikkahub-html-style"
private const val HTML_HELPER_SCRIPT_ID = "rikkahub-html-bridge"
private const val HTML_HELPER_THIRD_PARTY_ID = "rikkahub-html-third-party"

private const val HTML_HELPER_STYLE = """
    :root {
      --TH-viewport-height: 100vh;
    }

    *,
    *::before,
    *::after {
      box-sizing: border-box;
    }

    html,
    body {
      margin: 0 !important;
      padding: 0;
      overflow: hidden !important;
      max-width: 100% !important;
    }

    img,
    svg,
    video,
    canvas,
    iframe,
    pre,
    table {
      max-width: 100%;
    }
"""

private const val HTML_HELPER_THIRD_PARTY_SCRIPT = """
    (function() {
      var resources = [
        { type: 'style', url: 'https://testingcf.jsdelivr.net/npm/@fortawesome/fontawesome-free/css/all.min.css' },
        { type: 'script', url: 'https://testingcf.jsdelivr.net/npm/jquery/dist/jquery.min.js' },
        { type: 'script', url: 'https://testingcf.jsdelivr.net/npm/jquery-ui/dist/jquery-ui.min.js' },
        { type: 'style', url: 'https://testingcf.jsdelivr.net/npm/jquery-ui/themes/base/theme.min.css' },
        { type: 'script', url: 'https://testingcf.jsdelivr.net/npm/jquery-ui-touch-punch' },
        { type: 'script', url: 'https://testingcf.jsdelivr.net/npm/vue/dist/vue.runtime.global.prod.min.js' },
        { type: 'script', url: 'https://testingcf.jsdelivr.net/npm/vue-router/dist/vue-router.global.prod.min.js' }
      ];

      var head = document.head || document.getElementsByTagName('head')[0];
      if (!head) {
        return;
      }

      resources.forEach(function(resource) {
        if (head.querySelector('[data-rikkahub-resource="' + resource.url + '"]')) {
          return;
        }

        var node;
        if (resource.type === 'style') {
          node = document.createElement('link');
          node.rel = 'stylesheet';
          node.href = resource.url;
        } else {
          node = document.createElement('script');
          node.src = resource.url;
          node.async = false;
        }

        node.setAttribute('data-rikkahub-resource', resource.url);
        head.appendChild(node);
      });
    })();
"""

private const val HTML_HELPER_SCRIPT = """
    (function() {
      function getDocumentHeight() {
        var body = document.body;
        var doc = document.documentElement;
        if (!doc) {
          return 0;
        }

        var bodyRectHeight = 0;
        if (body && typeof body.getBoundingClientRect === 'function') {
          bodyRectHeight = Math.ceil(body.getBoundingClientRect().height || 0);
        }

        var bodyHeight = Math.max(
          body ? body.scrollHeight || 0 : 0,
          body ? body.offsetHeight || 0 : 0,
          bodyRectHeight
        );

        var docHeight = Math.max(
          doc.scrollHeight || 0,
          doc.offsetHeight || 0
        );

        var viewportHeight = Math.ceil(
          (window.visualViewport && window.visualViewport.height) || window.innerHeight || 0
        );

        if (bodyHeight > 0 && viewportHeight > 0 && docHeight <= viewportHeight + 1) {
          return bodyHeight;
        }

        return Math.max(bodyHeight, docHeight);
      }

      function updateViewportHeightVariable() {
        var viewportHeight = window.innerHeight || 0;
        if (window.visualViewport && window.visualViewport.height) {
          viewportHeight = window.visualViewport.height;
        }

        if (viewportHeight > 0) {
          document.documentElement.style.setProperty('--TH-viewport-height', Math.ceil(viewportHeight) + 'px');
        }
      }

      var lastReportedHeight = 0;
      var reportScheduled = false;

      function reportHeight() {
        reportScheduled = false;
        updateViewportHeightVariable();

        var height = Math.ceil(getDocumentHeight());
        if (!isFinite(height) || height <= 0) return;
        if (height === lastReportedHeight) return;
        lastReportedHeight = height;

        if (window.AndroidInterface && typeof window.AndroidInterface.updateHeight === 'function') {
          window.AndroidInterface.updateHeight(height);
        }
      }

      function scheduleReport() {
        if (reportScheduled) {
          return;
        }

        reportScheduled = true;
        if (typeof window.requestAnimationFrame === 'function') {
          window.requestAnimationFrame(reportHeight);
        } else {
          setTimeout(reportHeight, 16);
        }
      }

      if (!window.__rikkahubObserverInstalled) {
        window.__rikkahubObserverInstalled = true;

        window.addEventListener('load', function() {
          reportHeight();
          setTimeout(reportHeight, 80);
          setTimeout(reportHeight, 300);
          setTimeout(reportHeight, 1000);
        });

        window.addEventListener('resize', scheduleReport);

        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', scheduleReport);
        }

        window.addEventListener('message', function(event) {
          if (event && event.data && event.data.type === 'TH_UPDATE_VIEWPORT_HEIGHT') {
            updateViewportHeightVariable();
            scheduleReport();
          }
        });

        if (typeof ResizeObserver !== 'undefined') {
          var resizeObserver = new ResizeObserver(function() {
            scheduleReport();
          });
          resizeObserver.observe(document.documentElement);
          if (document.body) {
            resizeObserver.observe(document.body);
          }
          window.__rikkahubResizeObserver = resizeObserver;
        }

        var mutationObserver = new MutationObserver(function() {
          scheduleReport();
        });
        mutationObserver.observe(document.documentElement || document, {
          childList: true,
          subtree: true,
          attributes: true,
          characterData: true
        });
        window.__rikkahubMutationObserver = mutationObserver;
      }

      window.__TH_UPDATE_VIEWPORT_HEIGHT = function() {
        updateViewportHeightVariable();
        scheduleReport();
      };
      window.__rikkahubReportHeight = reportHeight;
      updateViewportHeightVariable();
      scheduleReport();
    })();
"""

private fun Double.toCompactString(): String {
    return BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
}

private fun convertVhToViewportVariable(value: String): String {
    return VH_VALUE_REGEX.replace(value) { match ->
        val vhValue = match.groupValues[1].toDoubleOrNull() ?: return@replace match.value
        if (!vhValue.isFinite()) return@replace match.value
        if (vhValue == 100.0) {
            VIEWPORT_HEIGHT_VARIABLE
        } else {
            "calc($VIEWPORT_HEIGHT_VARIABLE * ${(vhValue / 100.0).toCompactString()})"
        }
    }
}

internal fun replaceMinHeightVhForViewport(content: String): String {
    if (!content.contains("vh", ignoreCase = true)) {
        return content
    }

    var replaced = MIN_HEIGHT_DECLARATION_REGEX.replace(content) { match ->
        "${match.groupValues[1]}${convertVhToViewportVariable(match.groupValues[2])}"
    }

    replaced = INLINE_STYLE_REGEX.replace(replaced) { match ->
        val styleContent = match.groupValues[3]
        if (!styleContent.contains("min-height", ignoreCase = true) ||
            !styleContent.contains("vh", ignoreCase = true)
        ) {
            return@replace match.value
        }
        val updatedStyle = INLINE_MIN_HEIGHT_REGEX.replace(styleContent) { inlineMatch ->
            "${inlineMatch.groupValues[1]}${convertVhToViewportVariable(inlineMatch.groupValues[2])}"
        }
        "${match.groupValues[1]}$updatedStyle${match.groupValues[4]}"
    }

    replaced = JS_STYLE_MIN_HEIGHT_REGEX.replace(replaced) { match ->
        val value = match.groupValues[3]
        if (!value.contains("vh", ignoreCase = true)) {
            return@replace match.value
        }
        "${match.groupValues[1]}${convertVhToViewportVariable(value)}${match.groupValues[4]}"
    }

    replaced = JS_SET_PROPERTY_MIN_HEIGHT_REGEX.replace(replaced) { match ->
        val value = match.groupValues[4]
        if (!value.contains("vh", ignoreCase = true)) {
            return@replace match.value
        }
        "${match.groupValues[1]}${convertVhToViewportVariable(value)}${match.groupValues[5]}"
    }

    return replaced
}

internal fun buildBrowserHtmlDocument(html: String): String {
    val preprocessedHtml = replaceMinHeightVhForViewport(html)
    val document = Jsoup.parse(preprocessedHtml)
    document.outputSettings().prettyPrint(false)

    val head = document.head()
    if (head.selectFirst("meta[name=viewport]") == null) {
        head.prependElement("meta")
            .attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1.0")
    }

    if (head.getElementById(HTML_HELPER_STYLE_ID) == null) {
        head.appendElement("style")
            .attr("id", HTML_HELPER_STYLE_ID)
            .appendText(HTML_HELPER_STYLE)
    }

    if (head.getElementById(HTML_HELPER_THIRD_PARTY_ID) == null) {
        head.appendElement("script")
            .attr("id", HTML_HELPER_THIRD_PARTY_ID)
            .attr("type", "text/javascript")
            .append(HTML_HELPER_THIRD_PARTY_SCRIPT)
    }

    val body = document.body()
    if (body.getElementById(HTML_HELPER_SCRIPT_ID) == null) {
        body.appendElement("script")
            .attr("id", HTML_HELPER_SCRIPT_ID)
            .attr("type", "text/javascript")
            .append(HTML_HELPER_SCRIPT)
    }

    return document.outerHtml()
}

internal fun buildBlobBootstrapHtml(contentHtml: String): String {
    val encodedHtml = contentHtml.base64Encode()
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>RikkaHub HTML Renderer</title>
        </head>
        <body>
        <script>
          (function() {
            try {
              var binary = atob('$encodedHtml');
              var bytes = new Uint8Array(binary.length);
              for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
              }
              if (window.URL && typeof URL.createObjectURL === 'function') {
                var blob = new Blob([bytes], { type: 'text/html;charset=utf-8' });
                var blobUrl = URL.createObjectURL(blob);
                window.location.replace(blobUrl);
                setTimeout(function() {
                  try {
                    URL.revokeObjectURL(blobUrl);
                  } catch (_ignored) {}
                }, 30000);
              } else {
                var html = typeof TextDecoder === 'function'
                  ? new TextDecoder('utf-8').decode(bytes)
                  : binary;
                document.open();
                document.write(html);
                document.close();
              }
            } catch (error) {
              document.body.textContent = 'Failed to render HTML: ' + error.message;
            }
          })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun BrowserHtmlBlock(
    html: String,
    modifier: Modifier = Modifier,
    useBlobUrl: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val renderedHtml = remember(html) { buildBrowserHtmlDocument(html) }
    val webContent = remember(renderedHtml, useBlobUrl) {
        if (useBlobUrl) {
            buildBlobBootstrapHtml(renderedHtml)
        } else {
            renderedHtml
        }
    }
    val minHeightPx = with(density) { MIN_HTML_BLOCK_HEIGHT.dp.toPx().toInt() }
    var contentHeightPx by remember(html, useBlobUrl, density.density) {
        mutableIntStateOf(with(density) { DEFAULT_HTML_BLOCK_HEIGHT.dp.toPx().toInt() })
    }

    val htmlBridge = remember(density.density, minHeightPx) {
        HtmlBridge(
            onHeightChanged = { cssHeight ->
                if (cssHeight <= 0) return@HtmlBridge
                val height = (cssHeight * density.density).toInt()
                contentHeightPx = height.coerceAtLeast(minHeightPx)
            }
        )
    }

    val contentHeight = with(density) { contentHeightPx.toDp() }

    val webViewState = rememberWebViewState(
        data = webContent,
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf("AndroidInterface" to htmlBridge),
        onShouldOverrideUrlLoading = { _, request ->
            handleExternalNavigation(context = context, request = request)
        },
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    WebView(
        state = webViewState,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .animateContentSize()
            .height(contentHeight),
        onUpdated = { webView ->
            webView.evaluateJavascript(
                "if (window.__rikkahubReportHeight) { window.__rikkahubReportHeight(); }",
                null
            )
        }
    )
}

private class HtmlBridge(
    private val onHeightChanged: (Int) -> Unit,
) {
    @JavascriptInterface
    fun updateHeight(height: Int) {
        onHeightChanged(height)
    }
}

private fun handleExternalNavigation(
    context: android.content.Context,
    request: WebResourceRequest
): Boolean {
    if (!request.isForMainFrame) {
        return false
    }

    val uri = request.url ?: return true
    if (isInternalRenderScheme(uri.scheme)) {
        return false
    }

    if (uri.host.equals("rikkahub.local", ignoreCase = true)) {
        return false
    }

    if (!isTrustedExternalScheme(uri.scheme)) {
        return true
    }

    val hasUserGesture = runCatching { request.hasGesture() }.getOrDefault(false)
    if (!hasUserGesture) {
        return true
    }

    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
    return true
}

internal fun isInternalRenderScheme(scheme: String?): Boolean {
    val normalized = scheme?.lowercase(Locale.ROOT)
    return normalized == "blob" || normalized == "data" || normalized == "about"
}

internal fun isTrustedExternalScheme(scheme: String?): Boolean {
    val normalized = scheme?.lowercase(Locale.ROOT)
    return normalized == "http" || normalized == "https"
}
