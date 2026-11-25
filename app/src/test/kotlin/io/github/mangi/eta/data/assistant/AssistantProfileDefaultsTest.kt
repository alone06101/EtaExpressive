package io.github.mangi.eta.data.assistant

import io.github.mangi.eta.data.model.AssistantDefaults
import io.github.mangi.eta.data.model.AssistantProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProfileDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun missingFieldsDefaultToMemoryOnAndBuiltinSkills() {
        val decoded = json.decodeFromString<AssistantProfile>(
            """{"id":"default","name":"Eta","prompt":""}""",
        )
        assertTrue(decoded.memoryEnabled)
        assertEquals(AssistantDefaults.ENABLED_SKILL_IDS, decoded.enabledSkillIds)
        // 老档案没有该字段：默认跟随服务商提示词，配合「服务商留空回落人格」不改变老行为。
        assertTrue(decoded.followProviderSystemPrompt)
    }

    @Test
    fun emptySkillListIsPreserved() {
        val encoded = json.encodeToString(
            AssistantProfile.serializer(),
            AssistantProfile(
                id = "a",
                name = "A",
                prompt = "",
                memoryEnabled = false,
                enabledSkillIds = emptyList(),
            ),
        )
        val decoded = json.decodeFromString<AssistantProfile>(encoded)
        assertEquals(false, decoded.memoryEnabled)
        assertEquals(emptyList<String>(), decoded.enabledSkillIds)
    }
}
