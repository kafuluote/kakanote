package cool.hin.memox.presentation.activity.main.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.CircularProgressIndicator
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.PaperTask
import cool.hin.memox.data.model.PaperTasks
import cool.hin.memox.data.model.SpanRepresentation
import cool.hin.memox.data.model.Type
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.presentation.activity.note.EditActivity
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.hideKeyboard
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.*
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaperTodosFragment : Fragment() {
    private val model: BaseNoteModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val root = ctx.paperColumn(20)
        val progressContent = ctx.paperRow().apply { setPadding(20.dp, 20.dp, 20.dp, 20.dp) }
        val progressText = ctx.paperColumn()
        progressText.paperAdd(ctx.paperText("●  ${getString(R.string.paper_task_progress)}", 12f, true, R.color.paper_accent_ink))
        val headline = ctx.paperText("", 21f, true)
        val detail = ctx.paperText("", 12f, color = R.color.paper_muted)
        progressText.paperAdd(headline, 10)
        progressText.paperAdd(detail, 10)
        progressContent.addView(progressText, LinearLayout.LayoutParams(0, -2, 1f))
        val progress = CircularProgressIndicator(ctx).apply {
            isIndeterminate = false
            max = 100
            indicatorSize = 64.dp
            trackThickness = 6.dp
            trackColor = ContextCompat.getColor(ctx, R.color.paper_wash)
            setIndicatorColor(ContextCompat.getColor(ctx, R.color.paper_accent))
        }
        progressContent.addView(progress, LinearLayout.LayoutParams(72.dp, 72.dp))
        root.paperAdd(ctx.paperCard(progressContent))
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            clipToPadding = false
            setPadding(0, 16.dp, 0, 16.dp)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val quickRow = ctx.paperRow().apply { setPadding(14.dp, 4.dp, 6.dp, 4.dp); paperPill(ctx, R.color.paper_card) }
        val input = EditText(ctx).apply {
            hint = getString(R.string.paper_quick_task)
            textSize = 14f
            background = null
            maxLines = 2
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(ContextCompat.getColor(ctx, R.color.paper_ink))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.paper_muted))
        }
        quickRow.addView(input, LinearLayout.LayoutParams(0, 48.dp, 1f))
        lateinit var submit: View
        val app = requireActivity().application
        fun addTask() {
            val text = input.text.toString().trim()
            if (text.isEmpty() || !submit.isEnabled) return
            submit.isEnabled = false
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val now = System.currentTimeMillis()
                        MemoXDatabase.getDatabase(app, false).value.getBaseNoteDao().insert(
                            BaseNote(0, Type.NOTE, Folder.NOTES, BaseNote.COLOR_DEFAULT, text, false,
                                now, now, emptyList(), "☐ $text", listOf(SpanRepresentation(0, 1, checkbox = true)),
                                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
                        )
                    }.isSuccess
                }
                if (!isAdded) return@launch
                submit.isEnabled = true
                if (success) {
                    if (input.text.toString().trim() == text) input.setText("")
                    ctx.hideKeyboard(input)
                    SyncRouter.syncNow(ctx)
                } else ctx.showToast(R.string.paper_operation_failed)
            }
        }
        submit = ctx.paperButton("", true, ::addTask).apply {
            contentDescription = getString(R.string.add)
            setIconResource(R.drawable.arrow_upward)
            iconTint = ColorStateList.valueOf(android.graphics.Color.WHITE)
            iconPadding = 0
            setPadding(12.dp, 0, 12.dp, 0)
        }
        quickRow.addView(submit, LinearLayout.LayoutParams(48.dp, 48.dp))
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { addTask(); true } else false
        }
        root.paperAdd(quickRow)
        root.tag = Triple(list, progress, Pair(headline, detail))
        return root
    }

    @Suppress("UNCHECKED_CAST")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val (list, progress, labels) = view.tag as Triple<RecyclerView, CircularProgressIndicator, Pair<TextView, TextView>>
        val adapter = TaskAdapter(::toggleTask) { task ->
            startActivity(Intent(requireContext(), EditNoteActivity::class.java)
                .putExtra(EditActivity.EXTRA_SELECTED_BASE_NOTE, task.noteId))
        }
        list.adapter = adapter
        model.folder.value = Folder.NOTES
        model.baseNotes!!.observe(viewLifecycleOwner) { items ->
            val tasks = items.filterIsInstance<BaseNote>().flatMap(PaperTasks::from)
            val complete = tasks.count { it.checked }
            val percent = if (tasks.isEmpty()) 0 else complete * 100 / tasks.size
            progress.setProgressCompat(percent, true)
            labels.first.text = getString(R.string.paper_progress_title, percent)
            labels.second.text = getString(R.string.paper_progress_detail, complete, tasks.size - complete)
            adapter.submit(tasks)
        }
    }

    private fun toggleTask(task: PaperTask, checked: Boolean) {
        val app = requireActivity().application
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching {
                    val database = MemoXDatabase.getDatabase(app, false).value
                    database.withTransaction {
                        val dao = database.getBaseNoteDao()
                        val current = dao.get(task.noteId) ?: return@withTransaction false
                        val note = PaperTasks.setChecked(current, task, checked) ?: return@withTransaction false
                        dao.insert(note)
                        true
                    }
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            if (updated) SyncRouter.syncNow(app) else {
                app.showToast(R.string.paper_task_changed)
                @Suppress("UNCHECKED_CAST")
                val list = (view?.tag as? Triple<RecyclerView, *, *>)?.first
                list?.adapter?.notifyDataSetChanged()
            }
        }
    }

    private class TaskAdapter(val toggle: (PaperTask, Boolean) -> Unit, val open: (PaperTask) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Any> = emptyList()
        fun submit(tasks: List<PaperTask>) {
            rows = buildList {
                val pending = tasks.filterNot { it.checked }
                if (tasks.isEmpty()) add(R.string.paper_no_tasks)
                if (pending.isNotEmpty()) { add(R.string.paper_pending); addAll(pending) }
                val completed = tasks.filter { it.checked }
                if (completed.isNotEmpty()) { add(R.string.paper_completed); addAll(completed) }
            }
            notifyDataSetChanged()
        }
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is PaperTask) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            if (type == 0) return object : RecyclerView.ViewHolder(ctx.paperText("", 18f, true).apply {
                setPadding(2.dp, 12.dp, 2.dp, 12.dp)
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }) {}
            val content = ctx.paperRow().apply { setPadding(12.dp, 14.dp, 16.dp, 14.dp) }
            val check = MaterialCheckBox(ctx).apply {
                buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.md_theme_secondary))
            }
            val column = ctx.paperColumn()
            val title = ctx.paperText("", 16f)
            val meta = ctx.paperText("", 11f, color = R.color.paper_muted)
            column.paperAdd(title)
            column.paperAdd(meta, 8)
            content.addView(check, LinearLayout.LayoutParams(44.dp, 48.dp))
            content.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            val card = ctx.paperCard(content).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = 12.dp }
            }
            return object : RecyclerView.ViewHolder(card) {}.apply { itemView.tag = Triple(check, title, meta) }
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is Int) { (holder.itemView as TextView).setText(row); return }
            val task = row as PaperTask
            val (check, title, meta) = holder.itemView.tag as Triple<MaterialCheckBox, TextView, TextView>
            title.text = task.text
            title.paintFlags = if (task.checked) title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            meta.text = listOfNotNull(task.label, task.noteTitle.takeIf { it.isNotBlank() }).distinct().joinToString(" · ")
            check.setOnCheckedChangeListener(null)
            check.isChecked = task.checked
            check.setOnCheckedChangeListener { _, checked -> toggle(task, checked) }
            holder.itemView.setOnClickListener { open(task) }
        }
    }
}
