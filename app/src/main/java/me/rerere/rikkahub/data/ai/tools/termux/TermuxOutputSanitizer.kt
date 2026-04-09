package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val IMAGE_BASE64_BLOCK_MIN_CHARS = 64
private const val GENERIC_BASE64_BLOCK_MIN_CHARS = 512
private const val MAX_RENDERABLE_IMAGES = 4

internal data class TermuxSanitizedOutput(
    val text: String,
    val images: List<TermuxInlineImage> = emptyList(),
)

internal data class TermuxInlineImage(
    val mimeType: String,
    val bytes: ByteArray,
    val originalBase64Chars: Int,
)

private data class Base64Replacement(
    val range: IntRange,
    val replacement: String,
    val image: TermuxInlineImage? = null,
)

private val imageDataUriRegex = Regex(
    pattern = """data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)""",
    options = setOf(RegexOption.IGNORE_CASE)
)

private val longBase64BlockRegex = Regex(
    pattern = """(?<![A-Za-z0-9+/=])[A-Za-z0-9+/=\s]{64,}(?![A-Za-z0-9+/=])"""
)

@OptIn(ExperimentalEncodingApi::class)
internal fun sanitizeTermuxOutputForModel(
    output: String,
): TermuxSanitizedOutput {
    if (output.isBlank()) return TermuxSanitizedOutput(text = output)

    val replacements = mutableListOf<Base64Replacement>()

    imageDataUriRegex.findAll(output).forEach { match ->
        val mimeType = match.groupValues.getOrNull(1)?.lowercase() ?: return@forEach
        val cleanedBase64 = normalizeBase64(match.groupValues.getOrNull(2).orEmpty())
        if (cleanedBase64.length < IMAGE_BASE64_BLOCK_MIN_CHARS) return@forEach
        val bytes = decodeBase64(cleanedBase64) ?: return@forEach
        val image = TermuxInlineImage(
            mimeType = mimeType,
            bytes = bytes,
            originalBase64Chars = cleanedBase64.length,
        )
        replacements += Base64Replacement(
            range = match.range,
            replacement = buildImagePlaceholder(image),
            image = image,
        )
    }

    val occupiedRanges = replacements.map { it.range }
    longBase64BlockRegex.findAll(output).forEach { match ->
        if (occupiedRanges.any { rangesOverlap(it, match.range) }) return@forEach
        val rawBlock = match.value
        val cleanedBase64 = normalizeBase64(rawBlock)
        if (cleanedBase64.length < IMAGE_BASE64_BLOCK_MIN_CHARS) return@forEach
        val bytes = decodeBase64(cleanedBase64) ?: return@forEach
        val detectedMimeType = detectImageMimeType(bytes)
        if (detectedMimeType != null) {
            val image = TermuxInlineImage(
                mimeType = detectedMimeType,
                bytes = bytes,
                originalBase64Chars = cleanedBase64.length,
            )
            replacements += Base64Replacement(
                range = match.range,
                replacement = buildImagePlaceholder(image),
                image = image,
            )
        } else if (cleanedBase64.length >= GENERIC_BASE64_BLOCK_MIN_CHARS) {
            replacements += Base64Replacement(
                range = match.range,
                replacement = buildGenericBase64Placeholder(
                    originalChars = cleanedBase64.length,
                    decodedBytes = bytes.size,
                ),
            )
        }
    }

    if (replacements.isEmpty()) return TermuxSanitizedOutput(text = output)

    val sortedReplacements = replacements
        .sortedBy { it.range.first }

    val sanitizedText = buildString(output.length) {
        var cursor = 0
        sortedReplacements.forEach { replacement ->
            if (replacement.range.first < cursor) return@forEach
            append(output.substring(cursor, replacement.range.first))
            append(replacement.replacement)
            cursor = replacement.range.last + 1
        }
        append(output.substring(cursor))
    }.trim()

    return TermuxSanitizedOutput(
        text = sanitizedText.ifBlank {
            sortedReplacements.firstOrNull()?.replacement ?: output
        },
        images = sortedReplacements.mapNotNull { it.image }.take(MAX_RENDERABLE_IMAGES),
    )
}

internal suspend fun TermuxCommandToolResponse.toMessageParts(
    json: Json,
    filesManager: FilesManager,
): List<UIMessagePart> {
    val sanitizedOutput = sanitizeTermuxOutputForModel(output)
    return buildList {
        add(copy(output = sanitizedOutput.text).toTextPart(json))
        sanitizedOutput.images.forEachIndexed { index, image ->
            add(
                filesManager.createChatImagePartFromBytes(
                    bytes = image.bytes,
                    mimeType = image.mimeType,
                    displayName = "termux-output-${index + 1}.${extensionFromMimeType(image.mimeType)}",
                )
            )
        }
    }
}

internal suspend fun TermuxPtyToolResponse.toMessageParts(
    json: Json,
    filesManager: FilesManager,
): List<UIMessagePart> {
    val sanitizedOutput = sanitizeTermuxOutputForModel(output)
    return buildList {
        add(copy(output = sanitizedOutput.text).toTextPart(json))
        sanitizedOutput.images.forEachIndexed { index, image ->
            add(
                filesManager.createChatImagePartFromBytes(
                    bytes = image.bytes,
                    mimeType = image.mimeType,
                    displayName = "termux-pty-output-${index + 1}.${extensionFromMimeType(image.mimeType)}",
                )
            )
        }
    }
}

internal fun TermuxCommandToolResponse.toTextPart(json: Json): UIMessagePart.Text {
    return UIMessagePart.Text(encode(json))
}

internal fun TermuxPtyToolResponse.toTextPart(json: Json): UIMessagePart.Text {
    return UIMessagePart.Text(encode(json))
}

private fun buildImagePlaceholder(
    image: TermuxInlineImage,
): String {
    return "[base64 image omitted for model context: ${image.mimeType}, ${image.originalBase64Chars} chars, rendered in chat]"
}

private fun buildGenericBase64Placeholder(
    originalChars: Int,
    decodedBytes: Int,
): String {
    return "[base64 omitted for model context: $originalChars chars, $decodedBytes bytes]"
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(value: String): ByteArray? {
    return runCatching {
        Base64.Mime.decode(value)
    }.getOrNull()
}

private fun normalizeBase64(value: String): String {
    return buildString(value.length) {
        value.forEach { char ->
            if (!char.isWhitespace()) append(char)
        }
    }
}

private fun rangesOverlap(first: IntRange, second: IntRange): Boolean {
    return first.first <= second.last && second.first <= first.last
}

private fun detectImageMimeType(bytes: ByteArray): String? {
    if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
    ) {
        return "image/gif"
    }
    if (
        bytes.size >= 12 &&
        bytes[0] == 0x52.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() &&
        bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() &&
        bytes[11] == 0x50.toByte()
    ) {
        return "image/webp"
    }

    val textSample = bytes
        .copyOfRange(0, minOf(bytes.size, 256))
        .toString(Charsets.UTF_8)
        .trimStart()
        .lowercase()
    if (textSample.startsWith("<svg") || textSample.contains("<svg")) {
        return "image/svg+xml"
    }

    return null
}

private fun extensionFromMimeType(mimeType: String): String {
    return when (mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "png"
    }
}
