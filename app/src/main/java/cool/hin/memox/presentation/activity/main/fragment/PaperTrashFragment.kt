package cool.hin.memox.presentation.activity.main.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.activity.main.ModelFolderObserver
import cool.hin.memox.presentation.activity.note.EditActivity
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.withoutImagePlaceholders
import cool.hin.memox.presentation.view.*
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import java.util.concurrent.TimeUnit

/** Reuses the original action controller, including undo and attachment cleanup. */
open class PaperTrashFragment : Fragment() {
    private val model: BaseNoteModel by activityViewModels()
    private var notes = emptyList<BaseNote>()
    private var oldestFirst = false
    private var ui: Ui? = null
    private var adapter: TrashAdapter? = null
    private val notesProvider: () -> Collection<BaseNote> = { notes }
    private val host get() = requireActivity() as MainActivity
    private val actions get() = ModelFolderObserver(host, host.binding.ActionMode.menu, model)
    private data class Ui(val policy: TextView, val count: TextView, val clear: View,
        val select: MaterialCheckBox, val sort: MaterialButton, val empty: TextView,
        val list: RecyclerView, val delete: MaterialButton, val restore: MaterialButton, val more: View)

    override fun onCreate(state: Bundle?) { super.onCreate(state); oldestFirst = state?.getBoolean("oldest") ?: false }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putBoolean("oldest", oldestFirst) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val root = ctx.paperColumn(20)
        val notice = ctx.paperColumn(14)
        val policy = ctx.paperText("", 12f, color = R.color.paper_danger)
        notice.paperAdd(policy)
        val noticeActions = ctx.paperRow()
        val count = ctx.paperText("", 11f, color = R.color.paper_muted)
        noticeActions.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = ctx.paperButton(getString(R.string.paper_trash_clear)) { confirmClear() }.apply {
            id = R.id.TrashClear; textSize = 11f; minWidth = 0; setPadding(10.dp, 0, 10.dp, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.paper_danger))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.paper_card))
        }
        noticeActions.addView(clear, LinearLayout.LayoutParams(-2, 36.dp))
        notice.paperAdd(noticeActions, 12)
        root.paperAdd(ctx.paperCard(notice).apply {
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.paper_danger_wash))
            strokeWidth = 0; radius = 16.dp.toFloat(); cardElevation = 0f
        })
        val controls = ctx.paperRow()
        val select = MaterialCheckBox(ctx).apply { id = R.id.TrashSelectAll; textSize = 12f }
        controls.addView(select, LinearLayout.LayoutParams(0, 52.dp, 1f))
        val sort = ctx.paperButton("") { oldestFirst = !oldestFirst; refresh() }.apply {
            id = R.id.TrashSort; textSize = 11f; minWidth = 0; setPadding(10.dp, 0, 10.dp, 0)
        }
        controls.addView(sort, LinearLayout.LayoutParams(-2, 36.dp))
        root.paperAdd(controls, 12)
        val empty = ctx.paperText(getString(R.string.paper_trash_empty) + "\n\n" + getString(R.string.paper_trash_empty_hint), 15f, color = R.color.paper_muted)
            .apply { gravity = android.view.Gravity.CENTER; visibility = View.GONE }
        root.paperAdd(empty, 28)
        val list = RecyclerView(ctx).apply {
            id = R.id.TrashList; layoutManager = LinearLayoutManager(ctx); clipToPadding = false; setPadding(0, 4.dp, 0, 12.dp)
            itemAnimator = null
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val batch = ctx.paperRow().apply { setPadding(6.dp, 6.dp, 6.dp, 6.dp) }
        val delete = ctx.paperButton("") { if (!model.actionMode.isEmpty()) actions.deleteForever() }.apply {
            id = R.id.TrashDeleteSelection; textSize = 12f; setPadding(8.dp, 0, 8.dp, 0)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.paper_danger_wash))
            setTextColor(ContextCompat.getColor(ctx, R.color.paper_danger))
        }
        val restore = ctx.paperButton("", true) { if (!model.actionMode.isEmpty()) actions.moveNotes(Folder.NOTES) }.apply {
            id = R.id.TrashRestoreSelection; textSize = 12f; setPadding(8.dp, 0, 8.dp, 0)
        }
        batch.addView(delete, LinearLayout.LayoutParams(0, 48.dp, 1f))
        batch.addView(restore, LinearLayout.LayoutParams(0, 48.dp, 1.2f).apply { marginStart = 6.dp })
        val more = ctx.paperIcon(R.drawable.more_vert, getString(R.string.tap_for_more_options)) { anchor ->
            if (!model.actionMode.isEmpty()) PopupMenu(ctx, anchor).apply {
                ModelFolderObserver(host, menu, model).onChanged(Folder.DELETED)
                show()
            }
        }
        batch.addView(more, LinearLayout.LayoutParams(36.dp, 48.dp))
        root.paperAdd(ctx.paperCard(batch).apply { radius = 32.dp.toFloat(); strokeWidth = 0; cardElevation = 0f }, 8)
        ui = Ui(policy, count, clear, select, sort, empty, list, delete, restore, more)
        return root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        model.folder.value = Folder.DELETED
        host.getCurrentFragmentNotes = notesProvider
        adapter = TrashAdapter(); ui!!.list.adapter = adapter
        MemoXDatabase.getDatabase(requireActivity().application, false).value.getBaseNoteDao()
            .getFrom(Folder.DELETED).observe(viewLifecycleOwner) { list ->
                notes = list
                model.actionMode.updateSelected(list.map { it.id })
                list.filter { it.id in model.actionMode.selectedIds }.forEach { model.actionMode.selectedNotes[it.id] = it }
                refresh()
            }
        model.actionMode.count.observe(viewLifecycleOwner) { refresh() }
        model.actionMode.loading.observe(viewLifecycleOwner) { refresh() }
        model.preferences.autoRemoveDeletedNotesAfterDays.observe(viewLifecycleOwner) { refresh() }
        refresh()
    }

    private fun refresh() {
        val ui = ui ?: return
        val days = model.preferences.autoRemoveDeletedNotesAfterDays.value
        ui.policy.text = if (days > 0) getString(R.string.paper_trash_policy, days) else getString(R.string.paper_trash_policy_off)
        ui.count.text = getString(R.string.paper_trash_count, notes.size)
        ui.clear.isEnabled = notes.isNotEmpty() && !model.actionMode.loading.value
        ui.clear.alpha = if (ui.clear.isEnabled) 1f else 0.45f
        val count = model.actionMode.selectedIds.size
        ui.select.checkedState = when {
            count == 0 -> MaterialCheckBox.STATE_UNCHECKED
            count == notes.size -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
        ui.select.text = getString(R.string.paper_trash_selected, count, notes.size)
        ui.select.isEnabled = notes.isNotEmpty()
        ui.select.setOnClickListener {
            if (model.actionMode.selectedIds.size == notes.size) model.actionMode.close(true) else model.actionMode.add(notes)
        }
        ui.sort.setText(if (oldestFirst) R.string.paper_trash_sort_old else R.string.paper_trash_sort_new)
        ui.delete.text = getString(R.string.paper_trash_delete, count)
        ui.restore.text = getString(R.string.paper_trash_restore, count)
        val enabled = count > 0 && !model.actionMode.loading.value
        ui.delete.isEnabled = enabled; ui.restore.isEnabled = enabled
        ui.more.isEnabled = enabled
        listOf(ui.delete, ui.restore, ui.more).forEach { it.alpha = if (enabled) 1f else 0.45f }
        ui.empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        adapter?.rows = if (oldestFirst) notes.sortedBy { it.modifiedTimestamp } else notes.sortedByDescending { it.modifiedTimestamp }
        adapter?.notifyDataSetChanged()
    }

    private fun toggle(note: BaseNote) {
        if (note.id in model.actionMode.selectedIds) model.actionMode.remove(note.id) else model.actionMode.add(note.id, note)
    }
    private fun confirmClear() {
        if (notes.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.paper_trash_clear)
            .setMessage(R.string.delete_all_notes).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> model.deleteAllTrashedBaseNotes() }.show()
    }
    override fun onDestroyView() {
        if (host.getCurrentFragmentNotes === notesProvider) host.getCurrentFragmentNotes = null
        if (!requireActivity().isChangingConfigurations) model.actionMode.close(true)
        ui?.list?.adapter = null; ui = null; adapter = null
        super.onDestroyView()
    }

    private inner class TrashAdapter : RecyclerView.Adapter<TrashHolder>() {
        var rows = emptyList<BaseNote>()
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): TrashHolder {
            val ctx = parent.context
            val row = ctx.paperRow().apply { gravity = android.view.Gravity.TOP; setPadding(8.dp, 16.dp, 14.dp, 14.dp) }
            val check = MaterialCheckBox(ctx)
            row.addView(check, LinearLayout.LayoutParams(40.dp, 48.dp))
            val column = ctx.paperColumn()
            val title = ctx.paperText("", 17f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val body = ctx.paperText("", 14f, color = R.color.paper_muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val meta = ctx.paperText("", 10f, color = R.color.paper_muted)
            val remaining = ctx.paperText("", 10f).apply { paperPill(ctx); setPadding(8.dp, 5.dp, 8.dp, 5.dp) }
            column.paperAdd(title); column.paperAdd(body, 8); column.paperAdd(meta, 12)
            column.addView(remaining, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp })
            val controls = ctx.paperRow().apply { gravity = android.view.Gravity.END }
            val restore = ctx.paperButton(getString(R.string.restore)) {}.apply { textSize = 12f; minWidth = 0; setPadding(12.dp, 0, 12.dp, 0) }
            controls.addView(restore, LinearLayout.LayoutParams(-2, 36.dp))
            val delete = ctx.paperIcon(R.drawable.delete, "") {}.apply { imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.paper_danger)) }
            controls.addView(delete, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginStart = 6.dp })
            column.paperAdd(controls, 12)
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            val card = ctx.paperCard(row).apply {
                cardElevation = 0f; radius = 20.dp.toFloat(); strokeWidth = 0
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = 12.dp }
            }
            return TrashHolder(card, check, title, body, meta, remaining, restore, delete)
        }
        override fun onBindViewHolder(holder: TrashHolder, position: Int) {
            val note = rows[position]
            val title = note.title.ifBlank { getString(R.string.paper_no_note_title) }
            holder.title.text = title
            holder.body.text = if (note.locked) getString(R.string.lock_note) else note.body.withoutImagePlaceholders().ifBlank {
                note.items.joinToString(" · ") { it.body }.ifBlank { (note.images.map { it.originalName } + note.files.map { it.originalName }).joinToString(" · ") }
            }
            val age = TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - note.modifiedTimestamp).coerceAtLeast(0))
            holder.meta.text = listOfNotNull(note.labels.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                note.folderId.takeIf { it.isNotBlank() }, getString(R.string.paper_trash_age, age.toInt())).joinToString(" · ")
            val days = model.preferences.autoRemoveDeletedNotesAfterDays.value
            val remaining = (days - age).coerceAtLeast(0).toInt()
            holder.remaining.text = if (days <= 0) getString(R.string.paper_trash_manual)
                else if (remaining == 0) getString(R.string.paper_trash_due) else getString(R.string.paper_trash_remaining, remaining)
            holder.remaining.paperPill(requireContext(), if (days > 0 && remaining <= 5) R.color.paper_danger_wash else R.color.paper_wash)
            holder.remaining.setTextColor(ContextCompat.getColor(requireContext(), if (days > 0 && remaining <= 5) R.color.paper_danger else R.color.paper_muted))
            holder.check.isChecked = note.id in model.actionMode.selectedIds
            holder.check.contentDescription = getString(R.string.paper_trash_select_note, title)
            holder.check.setOnClickListener { toggle(note) }
            holder.restore.contentDescription = getString(R.string.paper_trash_restore_note, title)
            holder.restore.setOnClickListener { model.moveBaseNotes(longArrayOf(note.id), Folder.NOTES) }
            holder.delete.contentDescription = getString(R.string.paper_trash_delete_note, title)
            holder.delete.setOnClickListener {
                model.actionMode.close(false); model.actionMode.add(note.id, note); actions.deleteForever()
            }
            holder.itemView.setOnClickListener {
                if (model.actionMode.isEnabled()) toggle(note)
                else startActivity(Intent(requireContext(), EditNoteActivity::class.java).putExtra(EditActivity.EXTRA_SELECTED_BASE_NOTE, note.id))
            }
            holder.itemView.setOnLongClickListener { toggle(note); true }
        }
    }
    private class TrashHolder(view: View, val check: MaterialCheckBox, val title: TextView, val body: TextView,
        val meta: TextView, val remaining: TextView, val restore: MaterialButton, val delete: View) : RecyclerView.ViewHolder(view)
}
