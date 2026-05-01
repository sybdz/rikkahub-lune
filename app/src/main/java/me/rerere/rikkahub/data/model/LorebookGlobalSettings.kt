package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LorebookGlobalSettings(
    val scanDepth: Int = 4,
    val includeNames: Boolean = true,
    val overflowAlert: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
) {
    fun normalized(): LorebookGlobalSettings {
        return copy(
            scanDepth = scanDepth.coerceAtLeast(0),
        )
    }
}
