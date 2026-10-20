package cool.hin.memox.data.model

import org.junit.Assert.*
import org.junit.Test

class PaperTasksTest {
    private fun note(body: String = "☐ first\n☑ second", spans: List<SpanRepresentation> = listOf(
        SpanRepresentation(0, 1, checkbox = true),
        SpanRepresentation(8, 9, checkbox = true, checkboxChecked = true),
    )) = BaseNote(42, Type.NOTE, Folder.NOTES, BaseNote.COLOR_DEFAULT, "Title", true,
        1, 1, listOf("Work", "Home"), body, spans, emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), false, folderId = "Folder")

    @Test fun projectsRealInlineTasksAndSkipsEmptyOrInvalidSpans() {
        val tasks = PaperTasks.from(note(spans = note().spans + listOf(
            SpanRepresentation(100, 101, checkbox = true), SpanRepresentation(0, 1, checkbox = true))))
        assertEquals(listOf("first", "second"), tasks.map { it.text })
        assertEquals(listOf(false, true), tasks.map { it.checked })
        assertEquals("Work", tasks.first().label)
        assertTrue(PaperTasks.from(note("☐ ", listOf(SpanRepresentation(0, 1, checkbox = true)))).isEmpty())
    }

    @Test fun lockedNotesDoNotExposeTasksOrAcceptUpdates() {
        val original = note()
        val task = PaperTasks.from(original).first()
        assertTrue(PaperTasks.from(original.copy(locked = true)).isEmpty())
        assertNull(PaperTasks.setChecked(original.copy(locked = true), task, true))
    }

    @Test fun togglingKeepsOtherContentAndDoesNotMutateOriginal() {
        val original = note(spans = note().spans + SpanRepresentation(2, 7, bold = true))
        val task = PaperTasks.from(original).first()
        val checked = requireNotNull(PaperTasks.setChecked(original, task, true))
        assertEquals("☑ first\n☑ second", checked.body)
        assertTrue(checked.spans.any { it.strikethrough && it.start == 2 && it.end == 7 })
        assertTrue(checked.spans.any { it.bold })
        assertEquals(original.labels, checked.labels)
        assertEquals(original.folderId, checked.folderId)
        assertTrue(checked.pinned)
        assertFalse(original.spans.first().checkboxChecked)
        val unchecked = requireNotNull(PaperTasks.setChecked(checked, task, false))
        assertEquals(original.body, unchecked.body)
        assertFalse(unchecked.spans.any { it.strikethrough })
    }

    @Test fun staleTaskCannotOverwriteEditedOrDifferentNote() {
        val original = note()
        val task = PaperTasks.from(original).first()
        assertNull(PaperTasks.setChecked(original.copy(body = "☐ edited\n☑ second"), task, true))
        assertNull(PaperTasks.setChecked(original.copy(id = 43), task, true))
    }

    @Test fun legacyChildTaskUpdatesOnlyItsOwnItem() {
        val child = ListItem("child", false, true, 0, mutableListOf())
        val parent = ListItem("parent", false, false, 0, mutableListOf(child))
        val original = note("", emptyList()).copy(items = listOf(parent))
        val tasks = PaperTasks.from(original)
        assertEquals(listOf(listOf(0), listOf(0, 0)), tasks.map { it.legacyPath })
        val updated = requireNotNull(PaperTasks.setChecked(original, tasks[1], true))
        assertTrue(updated.items[0].children[0].checked)
        assertFalse(updated.items[0].checked)
        assertFalse(original.items[0].children[0].checked)
    }
}
