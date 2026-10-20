package cool.hin.memox.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import cool.hin.memox.R
import cool.hin.memox.data.model.*
import cool.hin.memox.databinding.ActivityEditBinding
import cool.hin.memox.databinding.RecyclerBaseNoteBinding
import cool.hin.memox.presentation.view.main.BaseNoteVH
import cool.hin.memox.presentation.view.main.BaseNoteVHPreferences
import cool.hin.memox.presentation.view.misc.ItemListener
import cool.hin.memox.presentation.viewmodel.preference.DateFormat
import cool.hin.memox.presentation.viewmodel.preference.TimeFormat
import cool.hin.memox.presentation.viewmodel.preference.NotesSortBy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperCardTest {
    @Test fun categoryPillKeepsContrastAndSelectionSurvivesBinding() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)
        val binding = RecyclerBaseNoteBinding.inflate(LayoutInflater.from(context), FrameLayout(context), false)
        val note = BaseNote(1, Type.NOTE, Folder.NOTES, BaseNote.COLOR_DEFAULT, "记录今天的小确幸", false,
            1780000000000, 1780000000000, listOf("生活", "灵感"), "阳光洒进窗边，写下一个新的念头。\n慢慢整理，让日常有迹可循。",
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
        val holder = BaseNoteVH(binding, DateFormat.SHORT_ISO, TimeFormat.NONE,
            BaseNoteVHPreferences(16f, false, NotesSortBy.CREATION_DATE), object : ItemListener {
                override fun onClick(position: Int) {}
                override fun onLongClick(position: Int) {}
            })
        holder.bind(note, null, true, NotesSortBy.CREATION_DATE, true)
        assertTrue(binding.root.isChecked)
        assertEquals(2.dp, binding.root.strokeWidth)
        assertNull(binding.LabelsText.backgroundTintList)
        assertEquals(0xFF3D4A3D.toInt(), binding.LabelsText.currentTextColor)
        holder.bind(note, null, false, NotesSortBy.CREATION_DATE, true)
        assertFalse(binding.root.isChecked)
        render(binding.root, 520, 0, "paper-card.png")

        val editor = ActivityEditBinding.inflate(LayoutInflater.from(context))
        editor.ScrollView.visibility = View.VISIBLE
        editor.Toolbar.title = context.getString(R.string.paper_editor)
        editor.EnterTitle.setText(note.title)
        editor.EnterBody.setText(note.body)
        editor.LabelsText.text = "#生活   #灵感"
        render(editor.root, 900, 1700, "paper-editor-layout.png")
    }

    private fun render(view: View, width: Int, height: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, if (height > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/ui", name).apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
