package me.rerere.rikkahub.data.export

import me.rerere.rikkahub.data.model.stExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LorebookSerializerTest {
    @Test
    fun `standalone sillytavern lorebook import should preserve supported regex metadata`() {
        val lorebook = LorebookSerializer.tryImportSillyTavern(
            json = """
                {
                  "recursive_scanning": true,
                  "token_budget": 128,
                  "entries": {
                    "0": {
                      "uid": 7,
                      "key": ["hero"],
                      "keysecondary": ["/forest/i"],
                      "content": "Standalone content",
                      "comment": "Standalone Entry",
                      "position": 4,
                      "order": 200,
                      "group": "facts",
                      "groupOverride": true,
                      "groupWeight": 75,
                      "useGroupScoring": true,
                      "useRegex": true,
                      "probability": 40,
                      "useProbability": false,
                      "depth": 2,
                      "role": 2,
                      "triggers": ["continue"],
                      "ignoreBudget": true,
                      "outletName": "memory"
                    }
                  }
                }
            """.trimIndent(),
            fileName = "Standalone",
        )

        assertNotNull(lorebook)
        val parsed = lorebook!!
        val entry = parsed.entries.single()
        assertEquals(true, entry.useRegex)
        assertNull(entry.probability)
        assertEquals(listOf("continue"), entry.stExtension().triggers)
        assertEquals("memory", entry.stExtension().outletName)
        assertEquals("false", entry.stMetadata["useProbability"])
        assertEquals("[\"continue\"]", entry.stMetadata["triggers"])
    }

    @Test
    fun `standalone sillytavern lorebook import should preserve non boolean recursion delay metadata`() {
        val lorebook = LorebookSerializer.tryImportSillyTavern(
            json = """
                {
                  "entries": {
                    "0": {
                      "key": ["hero"],
                      "content": "Standalone content",
                      "delayUntilRecursion": 2,
                      "probability": 40,
                      "useProbability": false
                    }
                  }
                }
            """.trimIndent(),
            fileName = "Standalone Delay",
        )

        assertNotNull(lorebook)
        val entry = lorebook!!.entries.single()
        assertNull(entry.probability)
        assertEquals("2", entry.stExtension().delayUntilRecursion)
        assertEquals(2, entry.stExtension().recursionDelayLevel())
        assertEquals("40", entry.stMetadata["probability"])
        assertEquals("false", entry.stMetadata["useProbability"])
    }
}
