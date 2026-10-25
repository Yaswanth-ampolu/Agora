package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteToolFileChangesTest {
    private fun change(kind: JsonElement, diff: String, path: String = "a.kt", move: JsonElement? = null) = buildJsonObject {
        put("path", path)
        put("kind", kind)
        put("diff", diff)
        move?.let { put("move_path", it) }
    }
    private fun arguments(vararg changes: JsonObject) = buildJsonObject {
        put("changes", JsonArray(changes.toList()))
    }.toString()
    private fun mapped(input: String, source: String = "codex-cli", result: String? = input) =
        mapRemoteToolDetail(source, "file_edit", input, result, "succeeded")
    private fun lines(body: ToolDetailBody.Edit, kind: ToolDetailDiffKind) =
        body.hunks.flatMap { it.lines }.filter { it.kind == kind }.map { it.text }

    @Test fun codexAddedFileWithLiteralContentsUsesAddedLines() {
        val content = "import pathlib\n+literal_prefix\n\nprint(\"<value>\")\n"
        val input = arguments(change(buildJsonObject { put("type", "add") }, content, "F:\\Deploy\\example\\inspect_run.py"))
        for (source in listOf("codex-cli", "codex-desktop")) {
            val detail = mapped(input, source)
            assertNotNull("Complete native add contents must not fall back to JSON: $source", detail)
            assertEquals(listOf("import pathlib", "+literal_prefix", "", "print(\"<value>\")"),
                lines(detail!!.body as ToolDetailBody.Edit, ToolDetailDiffKind.ADDED))
            assertNull(detail.arguments)
            assertTrue(detail.supplemental.isEmpty())
        }
    }

    @Test fun stringAndObjectKindsKeepLiteralPrefixesAndTrailingBlankLines() {
        for (operation in listOf("add", "delete")) {
            val expectedKind = if (operation == "add") ToolDetailDiffKind.ADDED else ToolDetailDiffKind.REMOVED
            for (kind in listOf(JsonPrimitive(operation), buildJsonObject { put("type", operation) })) {
                val input = arguments(change(kind, "--- literal\r\n+++ literal\r\n@@ literal\r\n\r\n"))
                val body = mapped(input)!!.body as ToolDetailBody.Edit
                assertEquals(listOf("--- literal", "+++ literal", "@@ literal", ""), lines(body, expectedKind))
                assertTrue(lines(body, if (operation == "add") ToolDetailDiffKind.REMOVED else ToolDetailDiffKind.ADDED).isEmpty())
                assertTrue(body.hunks.single().lines.first().text.contains(if (operation == "add") "Add File" else "Delete File"))
            }
        }
    }

    @Test fun emptyAddedAndDeletedFilesStillHaveAnOperationAndPath() {
        for (operation in listOf("add", "delete")) {
            val body = mapped(arguments(change(JsonPrimitive(operation), "", "empty.kt")))!!.body as ToolDetailBody.Edit
            assertEquals(1, body.hunks.single().lines.size)
            assertTrue(body.hunks.single().lines.single().text.endsWith("empty.kt"))
        }
    }

    @Test fun updatesAcceptBothKindShapesAndBothMoveLocations() {
        val diff = "--- a/old.kt\n+++ b/new.kt\n@@ -1,2 +1,2 @@\n kept\n-old\n+new\n\\ No newline at end of file\n"
        val variants = listOf(
            change(JsonPrimitive("update"), diff, "old.kt", JsonPrimitive("new.kt")),
            change(buildJsonObject { put("type", "update"); put("move_path", "new.kt") }, diff, "old.kt"),
            change(buildJsonObject { put("type", "update"); put("move_path", JsonNull) }, diff, "old.kt"),
        )
        for ((index, value) in variants.withIndex()) {
            val body = mapped(arguments(value))!!.body as ToolDetailBody.Edit
            assertEquals(listOf("old"), lines(body, ToolDetailDiffKind.REMOVED))
            assertEquals(listOf("new"), lines(body, ToolDetailDiffKind.ADDED))
            val context = lines(body, ToolDetailDiffKind.CONTEXT)
            assertTrue(context.contains("kept"))
            assertTrue(context.contains("--- a/old.kt"))
            assertEquals(index < 2, context.contains("*** Move to: new.kt"))
        }
    }

    @Test fun multipleChangesKeepOrderOperationsAndReceiptMetadata() {
        val input = arguments(
            change(JsonPrimitive("add"), "created", "new.kt"),
            change(buildJsonObject { put("type", "delete") }, "removed", "gone.kt"),
            change(JsonPrimitive("update"), "@@\n-before\n+after", "updated.kt"),
        )
        val result = JsonObject(Json.parseToJsonElement(input).jsonObject + ("receipt" to JsonPrimitive("retained"))).toString()
        val detail = mapped(input, result = result)!!
        val body = detail.body as ToolDetailBody.Edit
        assertNull(body.path)
        assertEquals(listOf("*** Add File: new.kt", "*** Delete File: gone.kt", "*** Update File: updated.kt"),
            body.hunks.map { it.lines.first().text })
        assertEquals(listOf("created", "after"), lines(body, ToolDetailDiffKind.ADDED))
        assertEquals(listOf("removed", "before"), lines(body, ToolDetailDiffKind.REMOVED))
        assertEquals("{\"receipt\":\"retained\"}", detail.supplemental.single())
    }

    @Test fun invalidChangesKeepTheEntireOriginalFallback() {
        val valid = change(JsonPrimitive("add"), "kept")
        val invalid = listOf(
            buildJsonObject { put("path", "a"); put("diff", "text") },
            change(JsonPrimitive("unknown"), "text"),
            change(JsonPrimitive("update"), "not a diff"),
            change(JsonPrimitive("add"), "text", move = JsonPrimitive("bad.kt")),
            change(JsonPrimitive("update"), "@@\n+x", move = JsonPrimitive(5)),
            change(buildJsonObject { put("type", "update"); put("move_path", "one") }, "@@\n+x", move = JsonPrimitive("two")),
            JsonObject(valid + ("unknown" to JsonPrimitive(true))),
            change(buildJsonObject { put("type", "add"); put("unknown", true) }, "text"),
            change(JsonPrimitive("add"), "text", " "),
            JsonObject(valid + ("diff" to JsonPrimitive(5))),
        )
        for (value in invalid) assertNull(value.toString(), mapped(arguments(valid, value)))
    }

    @Test fun claudeWriteEditAndPatchAddPreserveTheSameLiteralContents() {
        val content = "first\n+literal\n\n"
        val input = buildJsonObject { put("file_path", "new.kt"); put("content", content) }.toString()
        val added = lines(mapped(arguments(change(JsonPrimitive("add"), content)))!!.body as ToolDetailBody.Edit, ToolDetailDiffKind.ADDED)
        for (source in listOf("claude-cli", "claude-desktop")) {
            val write = mapRemoteToolDetail(source, "Write", input, "written", "succeeded")!!.body as ToolDetailBody.FileContent
            assertEquals(content, write.content)
            val edit = mapRemoteToolDetail(source, "Edit",
                """{"file_path":"new.kt","old_string":"old","new_string":"first\n+literal\n\n"}""", "done", "succeeded")!!.body as ToolDetailBody.Edit
            assertEquals(added, numberedCodeLines(write.content).map { it.text })
            assertEquals(content, lines(edit, ToolDetailDiffKind.ADDED).joinToString("\n"))
        }
        val patch = "*** Begin Patch\n*** Add File: new.kt\n+first\n++literal\n+\n*** End Patch"
        val patched = mapRemoteToolDetail("codex-cli", "apply_patch", patch, "Success", "succeeded")!!.body as ToolDetailBody.Edit
        assertEquals(added, lines(patched, ToolDetailDiffKind.ADDED))
    }
}
