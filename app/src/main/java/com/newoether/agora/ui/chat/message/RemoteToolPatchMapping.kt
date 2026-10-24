package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*

/** Complete patches only: malformed, truncated or unsupported sections keep the JSON fallback. */
internal fun remotePatchBody(patch: String): ToolDetailBody.Edit? {
    val lines = patch.replace("\r\n", "\n").trimEnd('\n').split('\n')
    if (lines.firstOrNull() != "*** Begin Patch" || lines.lastOrNull() != "*** End Patch") return null
    var index = 1
    val hunks = mutableListOf<ToolDetailDiffHunk>()
    val paths = mutableListOf<String>()
    while (index < lines.lastIndex) {
        val header = lines[index++]
        val operation = listOf("Update", "Add", "Delete").firstOrNull { header.startsWith("*** $it File: ") } ?: return null
        val path = header.substringAfter(" File: ").takeIf(String::isNotBlank) ?: return null
        paths += path
        val diff = mutableListOf(ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, header))
        if (lines.getOrNull(index)?.startsWith("*** Move to: ") == true) {
            if (operation != "Update" || lines[index].substringAfter("*** Move to: ").isBlank()) return null
            diff += ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, lines[index++])
        }
        val headerCount = diff.size
        while (index < lines.lastIndex && !lines[index].startsWith("*** ")) {
            val line = lines[index++]
            val kind = when {
                line.startsWith("@@") -> ToolDetailDiffKind.CONTEXT
                line.startsWith("+") -> ToolDetailDiffKind.ADDED
                line.startsWith("-") -> ToolDetailDiffKind.REMOVED
                line.startsWith(" ") -> ToolDetailDiffKind.CONTEXT
                else -> return null
            }
            if (operation == "Add" && kind != ToolDetailDiffKind.ADDED) return null
            diff += ToolDetailDiffLine(kind, if (line.startsWith("@@")) line else line.drop(1))
        }
        if (lines.getOrNull(index) == "*** End of File") index++
        if (operation != "Delete" && diff.size == headerCount) return null
        hunks += ToolDetailDiffHunk(null, false, diff)
    }
    if (hunks.isEmpty()) return null
    return ToolDetailBody.Edit(paths.singleOrNull()?.let { toolDetailPath(it) }, null, "", hunks, "")
}

/** Codex fileChange carries one unified diff per path; no native transcript is rewritten. */
internal fun remoteFileChanges(changes: JsonArray): ToolDetailBody.Edit? {
    if (changes.isEmpty()) return null
    val paths = mutableListOf<String>()
    val hunks = changes.map { value ->
        val change = value as? JsonObject ?: return null
        if (change.keys.any { it !in setOf("path", "kind", "diff") }) return null
        val path = change.remoteString("path") ?: return null
        paths += path
        val diff = change.remoteString("diff") ?: return null
        val rows = diff.replace("\r\n", "\n").trimEnd('\n').split('\n')
        if (rows.none { it.startsWith("@@") || it.startsWith("+") || it.startsWith("-") }) return null
        val lines = mutableListOf(ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, path))
        change["kind"]?.let { lines += ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, it.toString()) }
        for (row in rows) {
            val kind = when {
                row.startsWith("+++") || row.startsWith("---") || row.startsWith("@@") ||
                    row.startsWith("diff ") || row.startsWith("index ") || row == "\\ No newline at end of file" -> ToolDetailDiffKind.CONTEXT
                row.startsWith("+") -> ToolDetailDiffKind.ADDED
                row.startsWith("-") -> ToolDetailDiffKind.REMOVED
                row.startsWith(" ") -> ToolDetailDiffKind.CONTEXT
                else -> return null
            }
            lines += ToolDetailDiffLine(kind, if (kind == ToolDetailDiffKind.CONTEXT && !row.startsWith(" ")) row else row.drop(1))
        }
        ToolDetailDiffHunk(null, false, lines)
    }
    return ToolDetailBody.Edit(paths.singleOrNull()?.let { toolDetailPath(it) }, null, "", hunks, "")
}
