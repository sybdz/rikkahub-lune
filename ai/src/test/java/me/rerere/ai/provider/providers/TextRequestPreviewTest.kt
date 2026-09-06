package me.rerere.ai.provider.providers

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRequestPreviewTest {
    private val model = Model(modelId = "test-model", displayName = "Test Model")
    private val params = TextGenerationParams(
        model = model,
        customHeaders = listOf(
            CustomHeader("Authorization", "Bearer custom-secret"),
            CustomHeader("X-Trace-Id", "trace-123"),
        ),
    )
    private val messages = listOf(UIMessage.user("Hello"))

    @Test
    fun `openai preview uses actual request builder and exposes configured credentials`() {
        val preview = OpenAIProvider(OkHttpClient()).previewTextRequest(
            providerSetting = ProviderSetting.OpenAI(apiKey = "provider-secret"),
            messages = messages,
            params = params,
            stream = true,
        )

        assertEquals("OpenAI Chat Completions", preview.apiName)
        assertEquals("test-model", preview.body["model"]?.jsonPrimitive?.content)
        assertTrue(preview.body["stream"]?.jsonPrimitive?.content == "true")
        assertTrue(preview.headers.any { it.name == "X-Trace-Id" && it.value == "trace-123" })
        assertTrue(
            preview.headers.filter { it.name.equals("Authorization", ignoreCase = true) }
                .map { it.value }
                .containsAll(listOf("Bearer custom-secret", "Bearer provider-secret"))
        )
    }

    @Test
    fun `openai responses preview uses configured endpoint in both modes`() {
        val provider = OpenAIProvider(OkHttpClient())
        val setting = ProviderSetting.OpenAI(
            baseUrl = "https://example.com/v1",
            responsesPath = "/custom/responses",
            useResponseApi = true,
        )

        listOf(false, true).forEach { stream ->
            val preview = provider.previewTextRequest(
                providerSetting = setting,
                messages = messages,
                params = params,
                stream = stream,
            )

            assertEquals("OpenAI Responses API", preview.apiName)
            assertEquals("https://example.com/v1/custom/responses", preview.url)
            assertEquals(stream.toString(), preview.body["stream"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `google stream preview includes sse URL and configured key`() {
        val preview = GoogleProvider(OkHttpClient()).previewTextRequest(
            providerSetting = ProviderSetting.Google(apiKey = "google-secret"),
            messages = messages,
            params = params,
            stream = true,
        )

        assertTrue(preview.url.contains(":streamGenerateContent"))
        assertTrue(preview.url.contains("alt=sse"))
        assertTrue(preview.headers.any { it.name == "x-goog-api-key" && it.value == "google-secret" })
    }

    @Test
    fun `claude preview reflects stream mode and configured api key`() {
        val preview = ClaudeProvider(OkHttpClient()).previewTextRequest(
            providerSetting = ProviderSetting.Claude(apiKey = "claude-secret"),
            messages = messages,
            params = params,
            stream = true,
        )

        assertEquals("Claude Messages API", preview.apiName)
        assertEquals("test-model", preview.body["model"]?.jsonPrimitive?.content)
        assertTrue(preview.body["stream"]?.jsonPrimitive?.content == "true")
        assertTrue(preview.headers.any { it.name == "x-api-key" && it.value == "claude-secret" })
    }
}
