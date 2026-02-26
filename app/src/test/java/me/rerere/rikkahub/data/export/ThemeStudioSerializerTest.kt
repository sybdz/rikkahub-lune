package me.rerere.rikkahub.data.export

import me.rerere.rikkahub.data.datastore.ThemeProfile
import me.rerere.rikkahub.data.datastore.ThemeStudioConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ThemeStudioSerializerTest {

    @Test
    fun themeStudioConfigRoundTripKeepsProfilesAndRegeneratesIds() {
        val profile = ThemeProfile(
            id = Uuid.random(),
            name = "Custom",
            basePresetId = "sakura",
            colorBlend = 0.42f,
        )
        val config = ThemeStudioConfig(
            activeProfileId = profile.id,
            profiles = listOf(profile),
        )
        val exportData = ThemeStudioConfigSerializer.export(config)
        val json = ExportSerializer.DefaultJson.encodeToString(ExportData.serializer(), exportData)

        val imported = ThemeStudioConfigSerializer.tryImportThemeStudio(json)

        assertNotNull(imported)
        assertEquals(1, imported!!.profiles.size)
        assertEquals("Custom", imported.profiles.first().name)
        assertNotEquals(profile.id, imported.profiles.first().id)
    }

    @Test
    fun themeProfileImportRegeneratesProfileId() {
        val profile = ThemeProfile(
            id = Uuid.random(),
            name = "One",
            basePresetId = "ocean",
        )
        val exportData = ThemeProfileSerializer.export(profile)
        val json = ExportSerializer.DefaultJson.encodeToString(ExportData.serializer(), exportData)

        val imported = ThemeStudioConfigSerializer.tryImportThemeProfile(json)

        assertNotNull(imported)
        assertEquals("One", imported!!.name)
        assertNotEquals(profile.id, imported.id)
    }

    @Test
    fun rawThemeStudioImportRejectsInvalidPayloadShape() {
        assertNull(ThemeStudioConfigSerializer.tryImportThemeStudio("{}"))
        assertNull(ThemeStudioConfigSerializer.tryImportThemeProfile("{}"))
    }

    @Test
    fun rawThemeProfileJsonFallsBackToProfileImport() {
        val rawProfileJson = ExportSerializer.DefaultJson.encodeToString(
            ThemeProfile.serializer(),
            ThemeProfile(
                id = Uuid.random(),
                name = "Raw profile",
                basePresetId = "sakura",
            )
        )

        val studio = ThemeStudioConfigSerializer.tryImportThemeStudio(rawProfileJson)
        val profile = ThemeStudioConfigSerializer.tryImportThemeProfile(rawProfileJson)

        assertNull(studio)
        assertNotNull(profile)
        assertEquals("Raw profile", profile!!.name)
    }

    @Test
    fun rawThemeStudioJsonCanStillImport() {
        val profile = ThemeProfile(
            id = Uuid.random(),
            name = "Raw studio",
            basePresetId = "ocean",
        )
        val rawStudioJson = ExportSerializer.DefaultJson.encodeToString(
            ThemeStudioConfig.serializer(),
            ThemeStudioConfig(
                activeProfileId = profile.id,
                profiles = listOf(profile),
            )
        )

        val imported = ThemeStudioConfigSerializer.tryImportThemeStudio(rawStudioJson)

        assertNotNull(imported)
        assertEquals(1, imported!!.profiles.size)
        assertEquals("Raw studio", imported.profiles.first().name)
        assertNotEquals(profile.id, imported.profiles.first().id)
    }
}
