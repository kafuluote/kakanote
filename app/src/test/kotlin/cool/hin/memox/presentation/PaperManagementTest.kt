package cool.hin.memox.presentation

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.NavHostFragment
import androidx.room.Room
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.MemoXApplication
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.*
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.view.misc.NotNullLiveData
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.utils.LATEST_DATA_SCHEMA
import cool.hin.memox.utils.UpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi", application = MemoXApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperManagementTest {
    @Test fun categoryEditsAndTrashActionsPreserveNotesAndProtection() {
        val app = RuntimeEnvironment.getApplication() as MemoXApplication
        val prefs = MemoXPreferences.getInstance(app)
        ReflectionHelpers.setField(app, "preferences", prefs)
        prefs.setDataSchemaId(LATEST_DATA_SCHEMA)
        prefs.autoRemoveDeletedNotesAfterDays.save(0)
        val db = Room.inMemoryDatabaseBuilder(app, MemoXDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(MemoXDatabase::class.java, "instance", NotNullLiveData(db))
        UpdateChecker.state.value = UpdateChecker.UpdateInfo("test", 1, "", null)
        val dao = db.getBaseNoteDao()
        val ids = runBlocking {
            db.getLabelDao().insert(listOf(Label("工作", 3), Label("生活", 2), Label("灵感", 1)))
            db.getFolderDao().insert(NoteFolder("项目资料", 1))
            listOf(dao.insert(note("项目计划", Folder.NOTES, 0)),
                dao.insert(note("工作复盘手稿", Folder.DELETED, 12)),
                dao.insert(note("咖啡风味记录", Folder.DELETED, 25)),
                dao.insert(note("保护中的便签", Folder.DELETED, 2).copy(locked = true, body = "不能泄露的秘密正文")))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        val root = activity.findViewById<View>(R.id.DrawerLayout)
        try {
            pump()
            val nav = (activity.supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment).navController
            nav.navigate(R.id.Labels); pump(); render(root, "paper-categories-5.png")
            val list = activity.findViewById<RecyclerView>(R.id.CategoryList)
            val search = activity.findViewById<EditText>(R.id.CategoriesSearch)
            assertEquals(3, list.adapter!!.itemCount)
            search.setText("工作"); pump(); assertEquals(1, list.adapter!!.itemCount)
            search.setText(""); pump()
            activity.findViewById<View>(R.id.CategorySort).performClick(); pump()
            assertTrue(descendants(root).any { it.contentDescription == activity.getString(R.string.paper_category_drag, "工作") && it.visibility == View.VISIBLE })
            activity.findViewById<View>(R.id.CategorySort).performClick(); pump()

            fun actions(name: String, action: Int) {
                descendants(root).first { it.contentDescription == activity.getString(R.string.paper_category_actions, name) }.performClick()
                ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(action, 0)
                pump()
            }
            actions("工作", 2)
            assertTrue("工作" in prefs.labelsHidden.value)
            actions("工作", 2)
            assertFalse("工作" in prefs.labelsHidden.value)
            actions("工作", 1)
            val rename = ShadowDialog.getLatestDialog() as AlertDialog
            rename.findViewById<EditText>(R.id.EditText)!!.setText("项目工作")
            rename.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); pump()
            assertTrue("项目工作" in dao.get(ids[0])!!.labels)
            actions("项目工作", 3)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); pump()
            assertNotNull(dao.get(ids[0])); assertEquals("记录正文", dao.get(ids[0])!!.body)
            assertFalse("项目工作" in dao.get(ids[0])!!.labels)

            activity.findViewById<View>(R.id.CategoryAdd).performClick(); pump()
            val add = ShadowDialog.getLatestDialog() as AlertDialog
            descendants(add.window!!.decorView).filterIsInstance<EditText>().first().setText("新标签")
            add.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); pump()
            assertTrue(runBlocking { db.getLabelDao().exists("新标签") })
            nav.navigate(R.id.Folders); pump(); render(root, "paper-folders-5.png")
            assertEquals(1, activity.findViewById<RecyclerView>(R.id.CategoryList).adapter!!.itemCount)

            nav.navigate(R.id.Deleted); pump(); render(root, "paper-trash-manual-6.png")
            assertTrue(texts(root).contains(activity.getString(R.string.paper_trash_policy_off)))
            assertFalse(texts(root).contains("不能泄露的秘密正文"))
            prefs.autoRemoveDeletedNotesAfterDays.save(30); pump()
            render(root, "paper-trash-6.png")
            activity.findViewById<View>(R.id.TrashSort).performClick(); pump(); render(root, "paper-trash-oldest-6.png")
            activity.findViewById<View>(R.id.TrashSelectAll).performClick(); pump()
            assertEquals(3, activity.baseModel.actionMode.selectedIds.size)
            activity.findViewById<View>(R.id.TrashSelectAll).performClick(); pump()
            assertTrue(activity.baseModel.actionMode.isEmpty())
            descendants(root).first { it.contentDescription == activity.getString(R.string.paper_trash_select_note, "咖啡风味记录") }.performClick(); pump()
            render(root, "paper-trash-selected-6.png")
            activity.findViewById<View>(R.id.TrashRestoreSelection).performClick(); pump()
            assertEquals(Folder.NOTES, dao.get(ids[2])!!.folder)
            assertEquals("项目资料", dao.get(ids[2])!!.folderId)
            assertEquals(listOf("生活"), dao.get(ids[2])!!.labels)
            assertEquals("记录正文", dao.get(ids[2])!!.body)

            descendants(root).first { it.contentDescription == activity.getString(R.string.paper_trash_select_note, "工作复盘手稿") }.performClick(); pump()
            activity.findViewById<View>(R.id.TrashDeleteSelection).performClick(); pump()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); pump()
            assertNotNull(dao.get(ids[1]))
            activity.findViewById<View>(R.id.TrashDeleteSelection).performClick(); pump()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); pump()
            assertNull(dao.get(ids[1])); assertNotNull(dao.get(ids[0])); assertNotNull(dao.get(ids[2]))
            activity.findViewById<View>(R.id.TrashClear).performClick(); pump()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); pump()
            assertNull(dao.get(ids[3])); assertNotNull(dao.get(ids[0])); assertNotNull(dao.get(ids[2]))
            render(root, "paper-trash-empty-6.png")
        } finally {
            controller.pause().stop().destroy()
            UpdateChecker.state.value = null
            ReflectionHelpers.setStaticField(MemoXDatabase::class.java, "instance", null)
            db.close()
        }
    }

    private fun note(title: String, folder: Folder, age: Int): BaseNote {
        val now = System.currentTimeMillis()
        return BaseNote(0, Type.NOTE, folder, BaseNote.COLOR_DEFAULT, title, false, now - TimeUnit.DAYS.toMillis(40),
            now - TimeUnit.DAYS.toMillis(age.toLong()), if (title == "项目计划") listOf("工作", "生活") else listOf("生活"),
            "记录正文", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, folderId = "项目资料")
    }
    private fun pump() { repeat(15) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }; ShadowLooper.idleMainLooper() }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().joinToString("\n") { it.text }
    private fun render(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/ui", name).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
