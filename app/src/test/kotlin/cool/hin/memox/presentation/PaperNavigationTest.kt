package cool.hin.memox.presentation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.room.Room
import androidx.navigation.fragment.NavHostFragment
import cool.hin.memox.MemoXApplication
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.*
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.activity.note.SharePreviewActivity
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.activity.note.EditActivity
import android.content.Intent
import android.net.Uri
import com.google.android.material.chip.Chip
import com.google.android.material.navigation.NavigationView
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h800dp-xxhdpi", application = MemoXApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperNavigationTest {
    @Test fun newTabsAndOriginalArchiveRemainAccessible() {
        val app = RuntimeEnvironment.getApplication() as MemoXApplication
        val preferences = MemoXPreferences.getInstance(app)
        // The application deliberately skips production startup services in Robolectric.
        ReflectionHelpers.setField(app, "preferences", preferences)
        preferences.setDataSchemaId(LATEST_DATA_SCHEMA)
        val database = Room.inMemoryDatabaseBuilder(app, MemoXDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(MemoXDatabase::class.java, "instance", NotNullLiveData(database))
        UpdateChecker.state.value = UpdateChecker.UpdateInfo("test", 1, "", null)
        val noteId = runBlocking {
            database.getLabelDao().insert(Label("生活", 1))
            val body = "阳光洒进窗边，写下一个新的念头。\n☐ 整理今天的灵感"
            val checkbox = body.indexOf('☐')
            database.getBaseNoteDao().insert(BaseNote(0, Type.NOTE, Folder.NOTES, BaseNote.COLOR_DEFAULT,
                "记录今天的小确幸", true, 1780000000000, 1780000000000, listOf("生活"),
                body, listOf(SpanRepresentation(checkbox, checkbox + 1, checkbox = true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        try {
            pump()
            val nav = (activity.supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment).navController
            val menu = activity.findViewById<NavigationView>(R.id.NavView).menu
            listOf(R.id.Reminders, R.id.Archive, R.id.Deleted, R.id.Labels, R.id.Folders, R.id.Settings).forEach { assertNotNull(menu.findItem(it)) }
            val manageLabels = activity.findViewById<Chip>(R.id.LabelCategoryManage)
            assertEquals("", manageLabels.text.toString())
            assertNotNull(manageLabels.chipIcon)
            manageLabels.performClick(); pump()
            assertEquals(R.id.Labels, nav.currentDestination?.id)
            nav.popBackStack(); pump()
            activity.findViewById<View>(R.id.NavExport).performClick()
            val backupIntent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, backupIntent.action)
            assertEquals("application/zip", backupIntent.type)
            assertTrue(backupIntent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".zip"))
            assertTrue(backupIntent.getStringExtra(Intent.EXTRA_TITLE)!!.contains("备份"))
            assertTrue(backupIntent.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("kakanote "))
            var backupSucceeded = false
            activity.baseModel.exportBackup(Uri.parse("content://missing-backup-provider/backup.zip")) { backupSucceeded = true }
            pump()
            assertFalse(backupSucceeded)
            assertEquals(false, activity.baseModel.progress.value?.inProgress)
            render(activity.findViewById(R.id.DrawerLayout), "paper-home.png")
            activity.findViewById<View>(R.id.NavTodos).performClick()
            pump()
            assertEquals(R.id.PaperTodos, nav.currentDestination?.id)
            render(activity.findViewById(R.id.DrawerLayout), "paper-todos.png")
            descendants(activity.findViewById(R.id.DrawerLayout)).filterIsInstance<MaterialCheckBox>().first().performClick()
            pump()
            assertTrue(requireNotNull(database.getBaseNoteDao().get(noteId)).spans.first { it.checkbox }.checkboxChecked)
            val quickInput = descendants(activity.findViewById(R.id.DrawerLayout)).filterIsInstance<EditText>()
                .first { it.hint == activity.getString(R.string.paper_quick_task) }
            quickInput.setText("添加测试待办")
            descendants(activity.findViewById(R.id.DrawerLayout)).filterIsInstance<MaterialButton>()
                .first { it.contentDescription == activity.getString(R.string.add) }.performClick()
            pump()
            assertTrue(runBlocking { database.getBaseNoteDao().getAllNotes() }.any { it.body == "☐ 添加测试待办" })
            activity.findViewById<View>(R.id.NavOrganize).performClick()
            pump()
            assertEquals(R.id.PaperOrganize, nav.currentDestination?.id)
            assertTrue(descendants(activity.findViewById(R.id.DrawerLayout)).filterIsInstance<MaterialButton>().any { it.text.toString() == "文件夹" })
            render(activity.findViewById(R.id.DrawerLayout), "paper-organize.png")
            nav.navigate(R.id.Archive)
            pump()
            assertEquals(R.id.Archive, nav.currentDestination?.id)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.BottomNavigation).visibility)
            SharePreviewActivity.open(activity, noteId)
            val preview = Robolectric.buildActivity(SharePreviewActivity::class.java, shadowOf(activity).nextStartedActivity)
            preview.get().setTheme(R.style.AppTheme)
            val previewActivity = preview.setup().get()
            pump()
            render(previewActivity.findViewById(R.id.PreviewRoot), "paper-share.png")
            descendants(previewActivity.findViewById(R.id.PreviewRoot)).filterIsInstance<MaterialButton>()
                .first { it.text == previewActivity.getString(R.string.paper_share_image_action) }.performClick()
            pump()
            val png = File(previewActivity.cacheDir, "exported/kakanote-preview.png")
            assertTrue(png.isFile)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(png.path, bounds)
            assertEquals(1080, bounds.outWidth)
            assertTrue(bounds.outHeight > 0)
            preview.pause().stop().destroy()
            val editor = Robolectric.buildActivity(EditNoteActivity::class.java,
                Intent(app, EditNoteActivity::class.java).putExtra(EditActivity.EXTRA_SELECTED_BASE_NOTE, noteId))
            editor.get().setTheme(R.style.AppTheme)
            val editorActivity = editor.setup().get()
            pump()
            render(editorActivity.findViewById(R.id.coordinatorLayout), "paper-editor.png")
            editor.pause().stop().destroy()
        } finally {
            controller.pause().stop().destroy()
            UpdateChecker.state.value = null
            ReflectionHelpers.setStaticField(MemoXDatabase::class.java, "instance", null)
            database.close()
        }
    }

    private fun pump() {
        repeat(15) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        ShadowLooper.idleMainLooper()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun render(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/ui", name).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
