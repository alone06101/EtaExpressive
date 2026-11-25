package io.github.mangi.eta.data.assistant

import io.github.mangi.eta.data.model.AssistantPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptTest {
    @Test
    fun emptyPromptUsesAssistantName() {
        val prompt = AssistantPrompt.build("Minis", "")
        assertTrue(prompt.startsWith("你是 Minis"))
        assertFalse(prompt.contains("人格设定"))
        assertFalse(prompt.contains("Eta"))
    }

    @Test
    fun blankNameFallsBackToDefaultName() {
        assertEquals(AssistantPrompt.EMPTY_PROMPT, AssistantPrompt.build("  ", ""))
        assertTrue(AssistantPrompt.build("", "").startsWith("你是 Eta"))
    }

    @Test
    fun personalityBodyIsAppended() {
        val prompt = AssistantPrompt.build("Eta", "先做事，少客套。")
        assertTrue(prompt.startsWith("你是 Eta"))
        assertTrue(prompt.contains("人格设定："))
        assertTrue(prompt.contains("先做事，少客套。"))
        assertEquals(2, prompt.split("人格设定：").size)
    }

    // ---- 服务商提示词 / 跟随开关 ----

    @Test
    fun providerPromptBecomesBaseWhenFollowing() {
        val prompt = AssistantPrompt.runtimeBase(
            name = "Eta",
            prompt = "先做事，少客套。",
            providerPrompt = "你是一个契约工程师。",
            followProvider = true,
        )
        // 服务商提示词在前、人格追加在后，人格与身份句都不丢。
        assertTrue(prompt.startsWith("你是一个契约工程师。"))
        assertTrue(prompt.contains("人格设定："))
        assertTrue(prompt.contains("先做事，少客套。"))
        assertFalse(prompt.contains("你是 Eta"))
    }

    @Test
    fun providerPromptIgnoredWhenNotFollowing() {
        val prompt = AssistantPrompt.runtimeBase(
            name = "Eta",
            prompt = "先做事，少客套。",
            providerPrompt = "你是一个契约工程师。",
            followProvider = false,
        )
        assertEquals(AssistantPrompt.build("Eta", "先做事，少客套。"), prompt)
    }

    @Test
    fun blankProviderPromptFallsBackToPersonality() {
        val expected = AssistantPrompt.build("Eta", "先做事，少客套。")
        assertEquals(expected, AssistantPrompt.runtimeBase("Eta", "先做事，少客套。", null, true))
        assertEquals(expected, AssistantPrompt.runtimeBase("Eta", "先做事，少客套。", "   ", true))
    }

    @Test
    fun providerPromptAloneWithoutPersonality() {
        assertEquals(
            "你是一个契约工程师。",
            AssistantPrompt.runtimeBase("Eta", "  ", " 你是一个契约工程师。 ", true),
        )
    }
}
