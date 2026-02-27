package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlCodeBlockRenderDeciderTest {
    @Test
    fun `render when language is html`() {
        assertTrue(
            shouldRenderHtmlCodeBlock(
                language = "html",
                code = "<div>Hello</div>",
                completeCodeBlock = true,
            )
        )
    }

    @Test
    fun `render when language is svg compatible`() {
        assertTrue(
            shouldRenderHtmlCodeBlock(
                language = "xml",
                code = "<svg viewBox='0 0 100 100'></svg>",
                completeCodeBlock = true,
            )
        )
    }

    @Test
    fun `render when code body contains html tags`() {
        assertTrue(
            shouldRenderHtmlCodeBlock(
                language = "",
                code = "<body><h1>Preview</h1></body>",
                completeCodeBlock = true,
            )
        )
    }

    @Test
    fun `do not render plain code block`() {
        assertFalse(
            shouldRenderHtmlCodeBlock(
                language = "kotlin",
                code = "println(\"hello\")",
                completeCodeBlock = true,
            )
        )
    }

    @Test
    fun `do not render incomplete code block`() {
        assertFalse(
            shouldRenderHtmlCodeBlock(
                language = "html",
                code = "<div>streaming</div>",
                completeCodeBlock = false,
            )
        )
    }
}
