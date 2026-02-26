package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHtmlBlockDocumentTest {
    @Test
    fun htmlFragmentInjectsBridgeAssets() {
        val rendered = buildBrowserHtmlDocument(
            """
                <div style="min-height:100vh">
                  <a href="https://example.com">Example</a>
                </div>
            """.trimIndent()
        )

        assertTrue(rendered.contains("id=\"rikkahub-html-style\""))
        assertTrue(rendered.contains("id=\"rikkahub-html-third-party\""))
        assertTrue(rendered.contains("id=\"rikkahub-html-bridge\""))
        assertTrue(rendered.contains("window.__rikkahubReportHeight"))
        assertTrue(rendered.contains("__TH_UPDATE_VIEWPORT_HEIGHT"))
        assertTrue(rendered.contains("<body>"))
    }

    @Test
    fun fullDocumentKeepsOriginalContentAndInjectsAssets() {
        val rendered = buildBrowserHtmlDocument(
            """
                <!doctype html>
                <html>
                  <head>
                    <title>Hello</title>
                  </head>
                  <body>
                    <main>content</main>
                  </body>
                </html>
            """.trimIndent()
        )

        assertTrue(rendered.contains("<title>Hello</title>"))
        assertTrue(rendered.contains("<main>content</main>"))
        assertTrue(rendered.contains("id=\"rikkahub-html-style\""))
        assertTrue(rendered.contains("id=\"rikkahub-html-third-party\""))
        assertTrue(rendered.contains("id=\"rikkahub-html-bridge\""))
    }

    @Test
    fun wrappedHtmlDoesNotDuplicateBridgeAssets() {
        val firstPass = buildBrowserHtmlDocument("<section>hello</section>")
        val secondPass = buildBrowserHtmlDocument(firstPass)

        assertEquals(1, "id=\"rikkahub-html-style\"".toRegex().findAll(secondPass).count())
        assertEquals(1, "id=\"rikkahub-html-third-party\"".toRegex().findAll(secondPass).count())
        assertEquals(1, "id=\"rikkahub-html-bridge\"".toRegex().findAll(secondPass).count())
    }

    @Test
    fun minHeightVhIsConvertedToViewportVariable() {
        val replaced = replaceMinHeightVhForViewport(
            """
                <style>.a{min-height:100vh}.b{min-height:50vh}</style>
                <div style="min-height:75vh"></div>
                <script>
                  el.style.minHeight = "60vh";
                  el.style.setProperty('min-height', '25vh');
                </script>
            """.trimIndent()
        )

        assertTrue(replaced.contains("min-height:var(--TH-viewport-height)"))
        assertTrue(replaced.contains("min-height:calc(var(--TH-viewport-height) * 0.5)"))
        assertTrue(replaced.contains("min-height:calc(var(--TH-viewport-height) * 0.75)"))
        assertTrue(replaced.contains("minHeight = \"calc(var(--TH-viewport-height) * 0.6)\""))
        assertTrue(replaced.contains("setProperty('min-height', 'calc(var(--TH-viewport-height) * 0.25)')"))
    }

    @Test
    fun blobBootstrapHtmlContainsBlobRenderer() {
        val bootstrap = buildBlobBootstrapHtml("<html><body>Hello</body></html>")

        assertTrue(bootstrap.contains("URL.createObjectURL"))
        assertTrue(bootstrap.contains("window.location.replace(blobUrl)"))
        assertTrue(bootstrap.contains("atob('"))
        assertTrue(bootstrap.contains("new Uint8Array(binary.length)"))
        assertTrue(bootstrap.contains("new Blob([bytes], { type: 'text/html;charset=utf-8' })"))
        assertTrue(bootstrap.contains("new TextDecoder('utf-8').decode(bytes)"))
    }

    @Test
    fun bridgeHeightScriptDoesNotUseViewportClientHeightAsBaseline() {
        val rendered = buildBrowserHtmlDocument("<span>hello</span>")

        assertFalse(rendered.contains("doc.clientHeight"))
        assertFalse(rendered.contains("body.clientHeight"))
        assertTrue(rendered.contains("docHeight <= viewportHeight + 1"))
    }

    @Test
    fun internalRenderSchemeAllowsBlobDataAndAbout() {
        assertTrue(isInternalRenderScheme("blob"))
        assertTrue(isInternalRenderScheme("data"))
        assertTrue(isInternalRenderScheme("about"))
        assertTrue(isInternalRenderScheme("BLOB"))
        assertTrue(!isInternalRenderScheme("http"))
        assertTrue(!isInternalRenderScheme("https"))
        assertTrue(!isInternalRenderScheme("intent"))
        assertTrue(!isInternalRenderScheme(null))
    }

    @Test
    fun trustedExternalSchemeAllowsOnlyHttpAndHttps() {
        assertTrue(isTrustedExternalScheme("http"))
        assertTrue(isTrustedExternalScheme("https"))
        assertTrue(isTrustedExternalScheme("HTTP"))
        assertTrue(isTrustedExternalScheme("HtTpS"))
        assertTrue(!isTrustedExternalScheme("intent"))
        assertTrue(!isTrustedExternalScheme("market"))
        assertTrue(!isTrustedExternalScheme("tel"))
        assertTrue(!isTrustedExternalScheme("javascript"))
        assertTrue(!isTrustedExternalScheme(null))
    }
}
