package me.rerere.rikkahub.ui.components.richtext

private val HTML_CODE_BLOCK_LANGUAGES = setOf(
    "html",
    "htm",
    "xhtml",
    "xml",
    "svg",
)

private val HTML_DOCUMENT_TAG_REGEX = Regex(
    pattern = "<\\s*(html|head|body|svg)(\\s|>|/)",
    option = RegexOption.IGNORE_CASE
)

fun shouldRenderHtmlCodeBlock(
    language: String,
    code: String,
    completeCodeBlock: Boolean = true,
): Boolean {
    if (!completeCodeBlock) {
        return false
    }

    val normalizedLanguage = language.trim().lowercase()
    if (normalizedLanguage in HTML_CODE_BLOCK_LANGUAGES) {
        return true
    }

    return HTML_DOCUMENT_TAG_REGEX.containsMatchIn(code)
}
