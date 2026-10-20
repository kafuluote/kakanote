package cool.hin.memox.data.model

data class PaperTask(
    val noteId: Long,
    val position: Int,
    val text: String,
    val checked: Boolean,
    val noteTitle: String,
    val label: String?,
    val legacyPath: List<Int> = emptyList(),
) {
    val key: String get() = "$noteId:$position:${legacyPath.joinToString(".")}"
}

/** Projects existing inline and legacy checklists without introducing a second task store. */
object PaperTasks {
    fun from(note: BaseNote): List<PaperTask> {
        if (note.locked) return emptyList()
        val result = mutableListOf<PaperTask>()
        note.spans.filter { it.checkbox && it.start in note.body.indices }
            .distinctBy { it.start }.sortedBy { it.start }.forEach { span ->
                val start = span.end.coerceIn(span.start + 1, note.body.length)
                val end = note.body.indexOf('\n', start).let { if (it < 0) note.body.length else it }
                val text = note.body.substring(start, end).trim()
                if (text.isNotEmpty()) result += PaperTask(
                    note.id, span.start, text, span.checkboxChecked, note.title,
                    note.labels.firstOrNull() ?: note.folderId.takeIf { it.isNotEmpty() },
                )
            }
        fun collect(items: List<ListItem>, path: List<Int>) {
            items.forEachIndexed { index, item ->
                val itemPath = path + index
                if (item.body.isNotBlank()) result += PaperTask(
                    note.id, -1, item.body, item.checked, note.title,
                    note.labels.firstOrNull() ?: note.folderId.takeIf { it.isNotEmpty() }, itemPath,
                )
                collect(item.children, itemPath)
            }
        }
        collect(note.items, emptyList())
        return result
    }

    /** Re-read the current note before calling; stale projections never overwrite edited text. */
    fun setChecked(note: BaseNote, task: PaperTask, checked: Boolean): BaseNote? {
        val current = from(note).firstOrNull { it.key == task.key && it.text == task.text } ?: return null
        if (current.legacyPath.isNotEmpty()) {
            val items = note.items.map { it.clone() as ListItem }
            var item = items[current.legacyPath.first()]
            current.legacyPath.drop(1).forEach { item = item.children[it] }
            item.checked = checked
            item.checkedTimestamp = if (checked) System.currentTimeMillis() else null
            return note.copy(items = items, modifiedTimestamp = System.currentTimeMillis())
        }
        val spans = note.spans.map { it.copy() }.toMutableList()
        val checkbox = spans.first { it.checkbox && it.start == current.position }
        checkbox.checkboxChecked = checked
        val body = StringBuilder(note.body)
        if (body[current.position] == '☐' || body[current.position] == '☑') {
            body.setCharAt(current.position, if (checked) '☑' else '☐')
        }
        val lineStart = (checkbox.end + 1).coerceAtMost(body.length)
        val lineEnd = body.indexOf("\n", lineStart).let { if (it < 0) body.length else it }
        spans.filter { it.strikethrough && it.start == lineStart && it.end == lineEnd }
            .forEach { it.strikethrough = false }
        if (checked && lineStart < lineEnd) spans += SpanRepresentation(lineStart, lineEnd, strikethrough = true)
        return note.copy(body = body.toString(), spans = spans.filter { it.isNotUseless() },
            modifiedTimestamp = System.currentTimeMillis())
    }
}
