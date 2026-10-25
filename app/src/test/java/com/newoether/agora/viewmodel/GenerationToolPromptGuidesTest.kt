package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.resolveRequest
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GenerationToolPromptGuidesTest {
    @Test
    fun actualRequestToolsControlGuidesOnInitialContinuationAndRetry() = runTest {
        val fixture = fixture()
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val config = ProviderConfig(
            apiKey = "key", modelId = "model", requestResolver = snapshot.config.requestResolver,
        )
        val empty = config.resolveRequest(emptyList()).systemPrompt.orEmpty()
        assertFalse(empty.contains("## Memory"))
        assertFalse(empty.contains("## Web"))
        assertTrue(empty.contains("<active_memory>\nactive memory\n</active_memory>"))
        assertTrue(empty.contains("<skill_catalog>\nskill catalog\n</skill_catalog>"))
        // Configured settings are all enabled, but only these definitions entered this request.
        val webConfig = config.copy(tools = listOf(tool("web_search")))
        val first = webConfig.resolveRequest(emptyList()).systemPrompt
        assertTrue(first.orEmpty().contains("## Web"))
        assertFalse(first.orEmpty().contains("## Devices"))
        every { fixture.memoryManager.getActiveMemory() } returns "edited during run"
        every { fixture.skillManager.catalog() } returns "edited during run"
        repeat(2) { assertEquals(first, webConfig.resolveRequest(emptyList()).systemPrompt) }
        val memory = config.copy(tools = listOf(tool("active_memory_update")))
            .resolveRequest(emptyList()).systemPrompt.orEmpty()
        assertTrue(memory.contains("## Memory"))
        assertFalse(memory.contains("## Web"))
        assertTrue(memory.contains("active memory"))
        assertFalse(memory.contains("edited during run"))
    }

    @Test
    fun previewAndDispatchUseIdenticalPromptAndFixedCostForTheSameToolDefinitions() = runTest {
        val fixture = fixture()
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val preview = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)
        val tools = listOf(tool("memory_read"), tool("skill_read"), tool("file_read"), tool("ask_user"))
        val accounting = GenerationContextAccounting { tools }
        val previewConfig = accounting.resolveContextPrompt(preview.config, preview.context)
        val dispatched = ProviderConfig(
            apiKey = "key", modelId = "model", tools = tools,
            requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList())
        assertEquals(dispatched.systemPrompt, previewConfig.effectiveSystemPrompt)
        assertEquals(
            ContextTokenEstimator.forModel("model").estimateFixed(
                systemPrompt = dispatched.systemPrompt,
                tools = tools,
                codeExecutionEnabled = previewConfig.codeExecutionEnabled,
                googleSearchEnabled = previewConfig.googleSearchEnabled,
                openAiWebSearchEnabled = previewConfig.openAiWebSearchEnabled,
            ),
            accounting.fixedContextTokenCost(previewConfig, preview.context),
        )
        assertEquals(
            accounting.fixedContextTokenCost(previewConfig, preview.context),
            accounting.resolvedFixedContextTokenCost(snapshot.config, snapshot.context),
        )
        val emptyAccounting = GenerationContextAccounting { emptyList() }
        val disabled = emptyAccounting.resolveContextPrompt(preview.config, preview.context)
        assertFalse(disabled.effectiveSystemPrompt.orEmpty().contains("## Memory"))
        assertTrue(accounting.fixedContextTokenCost(previewConfig, preview.context) >
            emptyAccounting.fixedContextTokenCost(disabled, preview.context))
    }

    @Test
    fun contextProjectorResolvesToolGuidesBeforePricingItsCapturedTemplate() = runTest {
        val fixture = fixture()
        val tools = listOf(tool("web_fetch"), tool("shell_exec"))
        val accounting = GenerationContextAccounting { tools }
        val manager = mockk<GenerationManager>()
        coEvery { manager.resolveContextPrompt(any(), any()) } coAnswers {
            accounting.resolveContextPrompt(firstArg(), secondArg())
        }
        every { manager.fixedContextTokenCost(any(), any()) } answers {
            accounting.fixedContextTokenCost(firstArg(), secondArg())
        }
        every { manager.fixedContextComposition(any(), any()) } answers {
            accounting.fixedContextComposition(firstArg(), secondArg())
        }
        every { manager.includesAssistantReasoning(any(), any()) } returns false
        val projector = ConversationContextProjector(
            conversations = fixture.conversations,
            requestBuilder = fixture.builder,
            generationManager = { manager },
            generationErrorFormatter = { it },
            newChatSystemPromptId = { "prompt-id" },
        )
        val projection = projector.project(null, null, fixture.modelId, 32_768)
        assertFalse(projection.failed)
        assertTrue(projection.completed)
        val captured = fixture.builder.captureContextProjectionSnapshot(
            "context-preview-conversation", fixture.modelId, "prompt-id",
        )
        val resolved = accounting.resolveContextPrompt(captured.config, captured.context)
        assertTrue(resolved.effectiveSystemPrompt.orEmpty().contains("## Web"))
        assertTrue(resolved.effectiveSystemPrompt.orEmpty().contains("## Devices"))
        assertEquals(
            accounting.fixedContextTokenCost(resolved, captured.context),
            requireNotNull(projection.usage).estimatedTokenCount,
        )
        coVerify(exactly = 1) { manager.resolveContextPrompt(any(), any()) }
    }

    @Test
    fun toolsNeverInjectGuidanceOutsideAnExplicitTemplateVariable() = runTest {
        val fixture = fixture()
        val custom = "custom\n\n\nexact text"
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH).copy(
            id = "prompt-id",
            systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = custom)),
        )
        every { fixture.settings.systemPrompts } returns MutableStateFlow(listOf(entry))
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val result = ProviderConfig(
            apiKey = "key", modelId = "model", tools = listOf(tool("memory_read"), tool("web_search")),
            requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList())
        assertEquals(custom, result.systemPrompt)
    }

    @Test
    fun preservedCompactHasNoToolGuidesAndKeepsTheOrdinaryPromptContent() = runTest {
        val fixture = fixture(compactPreserveSystemPrompt = true)
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val prompt = snapshot.automaticCompact.generationConfig.effectiveSystemPrompt.orEmpty()
        assertTrue(prompt.contains("## Response"))
        assertTrue(prompt.contains("<active_memory>\nactive memory\n</active_memory>"))
        assertFalse(prompt.contains("## Memory"))
        assertFalse(prompt.contains("## Devices"))
    }

    private fun fixture(compactPreserveSystemPrompt: Boolean = false): RequestBuilderFixture {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false, compactPreserveSystemPrompt)
        every { fixture.settings.systemPrompts } returns MutableStateFlow(
            listOf(DefaultSystemPrompt.create(Locale.ENGLISH).copy(id = "prompt-id")),
        )
        return fixture
    }

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(name, "test tool", ToolParameters(properties = emptyMap())),
    )
}
