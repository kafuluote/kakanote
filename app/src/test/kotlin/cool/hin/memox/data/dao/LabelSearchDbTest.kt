package cool.hin.memox.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.Type
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LabelSearchDbTest {
    private lateinit var database: MemoXDatabase
    private lateinit var dao: BaseNoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, MemoXDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.getBaseNoteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun searchesLabelWithoutMatchingTitleOrBody() = runBlocking {
        val id = dao.insert(note(labels = listOf("工作", "计划")))
        assertEquals(listOf(id), search("工作").map { it.id })
    }

    @Test
    fun searchesLabelWithinSelectedCategory() = runBlocking {
        val id = dao.insert(note(labels = listOf("工作", "紧急")))
        dao.insert(note(labels = listOf("个人", "紧急")))
        assertEquals(listOf(id), search("紧急", "工作").map { it.id })
        assertEquals(listOf(id), search("工作", "工作").map { it.id })
    }

    @Test
    fun categoryMatchesWholeLabelEvenWithSqlWildcards() = runBlocking {
        val id = dao.insert(note(labels = listOf("工作")))
        dao.insert(note(labels = listOf("工作计划")))
        assertEquals(listOf(id), search("", "工作").map { it.id })
        val literalId = dao.insert(note(labels = listOf("100%_")))
        dao.insert(note(labels = listOf("100AB")))
        assertEquals(listOf(literalId), search("", "100%_").map { it.id })
        assertEquals(listOf(literalId), search("%_").map { it.id })
    }

    @Test
    fun uncategorizedOnlyReturnsNotesWithoutAnyLabel() = runBlocking {
        val id = dao.insert(note(body = "meeting"))
        val labeledId = dao.insert(note(body = "meeting", labels = listOf("工作")))
        assertEquals(listOf(id), search("meeting", null).map { it.id })
        assertEquals(setOf(id, labeledId), search("meeting").map { it.id }.toSet())
    }

    @Test
    fun labelSearchExcludesLockedNotesAndRespectsFolder() = runBlocking {
        val id = dao.insert(note(labels = listOf("工作")))
        dao.insert(note(labels = listOf("工作"), locked = true))
        val deletedId = dao.insert(note(labels = listOf("工作"), folder = Folder.DELETED))
        val archivedId = dao.insert(note(labels = listOf("工作"), folder = Folder.ARCHIVED))
        assertEquals(listOf(id), search("工作").map { it.id })
        assertEquals(listOf(deletedId), search("工作", folder = Folder.DELETED).map { it.id })
        assertEquals(listOf(archivedId), search("工作", folder = Folder.ARCHIVED).map { it.id })
    }

    @Test
    fun handlesLabelsWithQuotesBackslashesAndNonAsciiText() = runBlocking {
        val label = "项目\"A\"\\备份"
        val id = dao.insert(note(labels = listOf(label)))
        assertEquals(listOf(id), search(label, label).map { it.id })
        assertEquals(listOf(id), dao.getBaseNotesByLabel(label).first().map { it.id })
    }

    @Test
    fun keywordWithinCategoryStillMatchesTitleBodyAndChecklist() = runBlocking {
        val titleId = dao.insert(note(title = "MEETING", labels = listOf("工作")))
        val bodyId = dao.insert(note(body = "meeting", labels = listOf("工作")))
        val listId =
            dao.insert(
                note(
                    labels = listOf("工作"),
                    items = listOf(ListItem("meeting", false, false, null, mutableListOf())),
                )
            )
        dao.insert(note(body = "meeting", labels = listOf("个人")))
        assertEquals(setOf(titleId, bodyId, listId), search("meeting", "工作").map { it.id }.toSet())
    }

    private suspend fun search(
        keyword: String,
        label: String? = "",
        folder: Folder = Folder.NOTES,
    ) = dao.getBaseNotesByKeyword(keyword, folder, label).first()

    private fun note(
        title: String = "便签",
        body: String = "正文",
        labels: List<String> = emptyList(),
        locked: Boolean = false,
        folder: Folder = Folder.NOTES,
        items: List<ListItem> = emptyList(),
    ) =
        BaseNote(
            id = 0,
            type = Type.NOTE,
            folder = folder,
            color = BaseNote.COLOR_DEFAULT,
            title = title,
            pinned = false,
            timestamp = 0,
            modifiedTimestamp = 0,
            labels = labels,
            body = body,
            spans = emptyList(),
            items = items,
            images = emptyList(),
            files = emptyList(),
            audios = emptyList(),
            reminders = emptyList(),
            isPinnedToStatus = false,
            locked = locked,
        )
}
