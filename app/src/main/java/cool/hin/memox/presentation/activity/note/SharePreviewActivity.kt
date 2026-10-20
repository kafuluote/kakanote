package cool.hin.memox.presentation.activity.note

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.card.MaterialCardView
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.PaperTasks
import cool.hin.memox.databinding.ActivitySharePreviewBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.applySpans
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.withoutImagePlaceholders
import cool.hin.memox.presentation.view.*
import cool.hin.memox.utils.resolveAttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Entirely local image composition. Original file/PDF export remains in the editor menu. */
class SharePreviewActivity : LockedActivity<ActivitySharePreviewBinding>() {
    private var selectedStyle = 0
    private val options = booleanArrayOf(true, false, false, true)
    private lateinit var paper: MaterialCardView
    private lateinit var content: LinearLayout
    private var note: BaseNote? = null
    private val images = mutableListOf<Bitmap?>()
    private var pendingPng: ByteArray? = null
    private var busy = true
    private val exportButtons = mutableListOf<View>()
    private val saveImage = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingPng
        pendingPng = null
        if (uri != null && bytes != null) lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No output stream") }.isSuccess
            }
            showToast(if (success) R.string.paper_saved else R.string.paper_export_error)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedStyle = state?.getInt("style") ?: 0
        state?.getBooleanArray("options")?.takeIf { it.size == options.size }?.copyInto(options)
        pendingPng = state?.getString("pendingFile")?.let { path ->
            runCatching { File(cacheDir, path).readBytes() }.getOrNull()
        }
        binding = ActivitySharePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val root = paperColumn()
        binding.PreviewRoot.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = paperRow().apply { setPadding(12.dp, 4.dp, 12.dp, 4.dp) }
        toolbar.addView(paperButton("") { finish() }.apply {
            contentDescription = getString(R.string.cancel)
            setIconResource(R.drawable.back)
            iconTint = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, R.color.paper_ink))
            iconPadding = 0
            setPadding(12.dp, 0, 12.dp, 0)
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        toolbar.addView(paperText(getString(R.string.paper_share_preview), 20f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, 56.dp, 1f))
        root.paperAdd(toolbar)
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        val body = paperColumn(20)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content = paperColumn(26)
        paper = paperCard(content).apply { radius = 12.dp.toFloat() }
        body.paperAdd(paper)
        body.paperAdd(paperText(getString(R.string.paper_choose_style), 16f, true), 24)
        val styles = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf(R.string.paper_template_warm, R.string.paper_template_white, R.string.paper_template_kraft, R.string.paper_template_sage).forEachIndexed { index, title ->
            styles.addView(Chip(this).apply {
                id = View.generateViewId(); text = getString(title); isCheckable = true; isChecked = selectedStyle == index
                setOnCheckedChangeListener { _, checked -> if (checked) { selectedStyle = index; render() } }
            })
        }
        body.paperAdd(styles, 12)
        val switches = ChipGroup(this)
        listOf(R.string.paper_include_date, R.string.paper_include_author, R.string.paper_include_frame, R.string.paper_include_watermark).forEachIndexed { index, title ->
            switches.addView(Chip(this).apply {
                text = getString(title); isCheckable = true; isChecked = options[index]
                setOnCheckedChangeListener { _, checked -> options[index] = checked; render() }
            })
        }
        body.paperAdd(switches, 8)
        val actions = paperRow().apply { setPadding(20.dp, 8.dp, 20.dp, 12.dp) }
        listOf(false, true).forEach { share ->
            val button = paperButton(getString(if (share) R.string.paper_share_image_action else R.string.paper_save_image), share) { export(share) }
            button.isEnabled = false
            exportButtons.add(button)
            actions.addView(button, LinearLayout.LayoutParams(0, 52.dp, 1f).apply { if (share) marginStart = 12.dp })
        }
        root.paperAdd(actions)
        lifecycleScope.launch {
            note = withContext(Dispatchers.IO) { MemoXDatabase.getDatabase(application, false).value.getBaseNoteDao().get(intent.getLongExtra(EXTRA_NOTE, -1)) }
            val value = note
            if (value == null || value.locked) { showToast(R.string.paper_unlock_export); finish(); return@launch }
            images.addAll(withContext(Dispatchers.IO) {
                value.images.map { attachment ->
                    runCatching {
                        val file = resolveAttachmentFile("Images", attachment.localName) ?: return@runCatching null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1080 || bounds.outHeight / sample > 2400) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }.getOrNull()
                }
            })
            render()
            setBusy(false)
        }
    }

    private fun render() {
        val value = note ?: return
        val backgrounds = intArrayOf(0xFFFBF9F5.toInt(), Color.WHITE, 0xFFEAD7BD.toInt(), 0xFFF3F6EE.toInt())
        paper.setCardBackgroundColor(backgrounds[selectedStyle])
        paper.strokeWidth = if (options[2]) 2.dp else 0
        paper.setStrokeColor(0xFFB8AA98.toInt())
        content.removeAllViews()
        fun text(value: CharSequence, size: Float = 15f, bold: Boolean = false) = paperText(value, size, bold).apply {
            setTextColor(0xFF292B2C.toInt()); setLineSpacing(5.dp.toFloat(), 1.3f)
        }
        if (options[0]) content.paperAdd(text(SimpleDateFormat("yyyy.MM.dd · EEEE", Locale.getDefault()).format(Date(value.timestamp)), 11f))
        if (value.labels.isNotEmpty()) content.paperAdd(text(value.labels.joinToString("  ") { "# $it" }, 11f), 14)
        if (value.title.isNotBlank()) content.paperAdd(text(value.title, 25f, true), 18)
        if (value.body.isNotBlank()) content.paperAdd(text(value.body.applySpans(value.spans).withoutImagePlaceholders()), 20)
        PaperTasks.from(value).filter { it.legacyPath.isNotEmpty() }.forEach { task ->
            content.paperAdd(text("${if (task.checked) "☑" else "☐"} ${task.text}"), 12)
        }
        value.images.forEachIndexed { index, attachment ->
            val bitmap = images.getOrNull(index)
            if (bitmap == null) content.paperAdd(text("[${attachment.originalName}]", 11f), 14)
            else content.paperAdd(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }, 18)
        }
        (value.files.map { it.originalName } + value.audios.map { it.name }).forEach { content.paperAdd(text("↗ $it", 11f), 12) }
        if (options[1]) content.paperAdd(text(getString(R.string.about_author), 11f), 24)
        if (options[3]) content.paperAdd(text("${getString(R.string.product_name)}  ·  ${getString(R.string.paper_feed)}", 10f).apply { gravity = Gravity.END }, 28)
    }

    private fun setBusy(value: Boolean) { busy = value; exportButtons.forEach { it.isEnabled = !value } }

    private fun export(share: Boolean) {
        if (busy || paper.width <= 0 || paper.height <= 0) return
        val width = 1080
        val scale = width.toFloat() / paper.width
        val height = (paper.height * scale).toInt()
        if (height > 16000) { showToast(R.string.paper_export_too_long); return }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                paper.draw(Canvas(bitmap).apply { scale(scale, scale) })
                val bytes = withContext(Dispatchers.Default) {
                    try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
                    finally { bitmap.recycle() }
                }
                if (share) {
                    val file = withContext(Dispatchers.IO) {
                        File(cacheDir, "exported").apply { mkdirs() }.let { File(it, "kakanote-preview.png") }.apply { writeBytes(bytes) }
                    }
                    val uri = FileProvider.getUriForFile(this@SharePreviewActivity, "$packageName.provider", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(getString(R.string.product_name), uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, getString(R.string.paper_share_image_action)))
                } else {
                    pendingPng = bytes
                    withContext(Dispatchers.IO) { File(cacheDir, "pending-preview.png").writeBytes(bytes) }
                    saveImage.launch("kakanote-${note?.id}.png")
                }
            } catch (_: Exception) { showToast(R.string.paper_export_error) }
            finally { setBusy(false) }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("style", selectedStyle); out.putBooleanArray("options", options)
        if (pendingPng != null) out.putString("pendingFile", "pending-preview.png")
        super.onSaveInstanceState(out)
    }

    companion object {
        private const val EXTRA_NOTE = "paper_note_id"
        fun open(context: Context, id: Long) = context.startActivity(Intent(context, SharePreviewActivity::class.java).putExtra(EXTRA_NOTE, id))
    }
}
