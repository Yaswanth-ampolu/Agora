package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ToolPromptGuidesTest {
    private val tools = linkedMapOf(
        PredefinedVariables.TOOL_GUIDE_MEMORY to "memory_read",
        PredefinedVariables.TOOL_GUIDE_SKILLS to "skill_read",
        PredefinedVariables.TOOL_GUIDE_PAST_CONVERSATIONS to "conversation_search",
        PredefinedVariables.TOOL_GUIDE_WEB to "web_search",
        PredefinedVariables.TOOL_GUIDE_DEVICES to "file_read",
        PredefinedVariables.TOOL_GUIDE_SUBAGENTS_AND_AUTOMATION to "agent_spawn",
        PredefinedVariables.TOOL_GUIDE_ASKING_THE_USER to "ask_user",
    )

    @Test
    fun everyCombinationSelectsOnlyItsGuidesWithoutBlankLineAccumulation() {
        val entries = tools.entries.toList()
        for (mask in 0 until (1 shl entries.size)) {
            val enabled = entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val values = ToolPromptGuides.runtimeValues(enabled.mapTo(linkedSetOf()) { it.value })
            entries.forEach { (key, _) ->
                assertEquals("mask=$mask key=$key", key in enabled.map { it.key }, values.getValue(key).isNotEmpty())
            }
            val prompt = compile(values)
            assertFalse("mask=$mask", prompt.contains("\n\n\n"))
            assertFalse(prompt.contains("{tool_guide_"))
            assertTrue(prompt.indexOf("## Response") < prompt.indexOf("## Tools"))
            val headings = Regex("(?m)^## .+$").findAll(prompt).map { it.value }.toList()
            val expected = listOf("## Response", "## Tools") + enabled.map {
                ToolPromptGuides.exampleValues.getValue(it.key).trim().lineSequence().first()
            }
            assertEquals(expected, headings)
            assertTrue(prompt.indexOf("<skill_catalog>") < prompt.indexOf("<active_memory>"))
        }
    }

    @Test
    fun sameToolSetHasByteStableValuesRegardlessOfOrderOrUnrelatedTools() {
        val names = tools.values.toList()
        val first = ToolPromptGuides.runtimeValues(names.toSet())
        val reversed = ToolPromptGuides.runtimeValues(names.reversed().toSet() + setOf("todo_read", "image_generate", "mcp_call_tool"))
        assertEquals(first, reversed)
        assertEquals(compile(first).toByteArray().toList(), compile(reversed).toByteArray().toList())
        assertEquals(first, ToolPromptGuides.runtimeValues(names.toSet()))
    }

    @Test
    fun activeOnlyAutomationOnlyAndLegacyAliasesResolveToTheirGroups() {
        listOf("active_memory_read", "active_memory_update").forEach {
            assertTrue(ToolPromptGuides.runtimeValues(setOf(it)).getValue(PredefinedVariables.TOOL_GUIDE_MEMORY).isNotEmpty())
        }
        listOf("agent_list", "task_create", "loop_stop").forEach {
            assertTrue(ToolPromptGuides.runtimeValues(setOf(it)).getValue(PredefinedVariables.TOOL_GUIDE_SUBAGENTS_AND_AUTOMATION).isNotEmpty())
        }
        assertEquals(ToolPromptGuides.runtimeValues(setOf("memory_read", "shell_exec")),
            ToolPromptGuides.runtimeValues(setOf("read_memory_file", "execute_shell_command")))
        assertTrue(ToolPromptGuides.runtimeValues(setOf("mcp_memory_read", "custom_shell_exec")).values.all(String::isEmpty))
    }

    @Test
    fun allNewVariablesAreAvailableInPickerAndHaveExplicitPreviewValues() {
        tools.keys.forEach {
            assertEquals(1, PredefinedVariables.ALL.count { variable -> variable == it })
            assertTrue(PredefinedVariables.EXAMPLE_VALUES.getValue(it).startsWith("\n\n## "))
        }
    }

    @Test
    fun customTextAndOmittedVariablesRemainUntouched() {
        val text = "custom\n\n\ntext {tool_guide_memory}"
        val item = PromptTemplateItem(type = PromptItemType.CUSTOM, value = text)
        assertEquals(text, PredefinedVariables.compile(listOf(item), ToolPromptGuides.runtimeValues(tools.values.toSet()), emptyMap()))
    }

    private fun compile(values: Map<String, String>) = PredefinedVariables.compile(
        DefaultSystemPrompt.create(Locale.ENGLISH).systemItems,
        values + mapOf(PredefinedVariables.ACTIVE_MEMORY to "", PredefinedVariables.SKILL_CATALOG to ""),
        emptyMap(),
    )
}
