package me.rerere.rikkahub.data.ai.tools.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxOutputSanitizerTest {
    @Test
    fun `sanitize should extract image data uri and replace output`() {
        val base64Png = TINY_PNG_BASE64
        val sanitized = sanitizeTermuxOutputForModel(
            output = "preview=data:image/png;base64,$base64Png"
        )

        assertEquals(1, sanitized.images.size)
        assertEquals("image/png", sanitized.images.first().mimeType)
        assertTrue(sanitized.text.contains("base64 image omitted for model context"))
        assertTrue(sanitized.text.contains("image/png"))
    }

    @Test
    fun `sanitize should detect raw image base64`() {
        val sanitized = sanitizeTermuxOutputForModel(output = TINY_PNG_BASE64)

        assertEquals(1, sanitized.images.size)
        assertEquals("image/png", sanitized.images.first().mimeType)
        assertTrue(sanitized.text.contains("rendered in chat"))
    }

    @Test
    fun `sanitize should truncate large non image base64 for model context`() {
        val largeBase64 = "QUJD".repeat(300)
        val sanitized = sanitizeTermuxOutputForModel(output = largeBase64)

        assertTrue(sanitized.images.isEmpty())
        assertEquals("[base64 omitted for model context: ${largeBase64.length} chars, 900 bytes]", sanitized.text)
    }

    private companion object {
        const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMioAAAAASUVORK5CYII="
    }
}
