package cool.hin.memox.presentation.activity.main.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.*
import cool.hin.memox.data.sync.SyncState
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.presentation.displayEditFolderDialog
import cool.hin.memox.presentation.displayEditLabelDialog
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.*
import cool.hin.memox.presentation.viewmodel.BaseNoteModel

/** Shared management surface: tags remain many-to-many, folders remain a single assignment. */
open class PaperCategoryFragment : Fragment() {
    private val model: BaseNoteModel by activityViewModels()
    private var folders = false
    private var reordering = false
    private var dragging = false
    private var query = ""
    private var notes = emptyList<BaseNote>()
    private var ui: Ui? = null
    private var adapter: CategoryAdapter? = null
    private var touchHelper: ItemTouchHelper? = null
    private data class Entry(val name: String, val count: Int, val hidden: Boolean)
    private data class Ui(val tabs: LinearLayout, val summary: TextView, val badge: TextView,
        val heading: TextView, val sort: MaterialButton, val add: MaterialButton,
        val search: EditText, val hint: TextView, val empty: TextView, val list: RecyclerView)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        folders = state?.getBoolean("folders") ?: (this is FoldersFragment)
        query = state?.getString("query").orEmpty()
        reordering = state?.getBoolean("reordering") ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val root = ctx.paperColumn(20)
        val tabs = ctx.paperRow()
        root.paperAdd(tabs, height = 40)
        val overview = ctx.paperColumn(16)
        val titleRow = ctx.paperRow()
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.folder)
            setColorFilter(ContextCompat.getColor(ctx, R.color.paper_accent_ink))
            paperPill(ctx, R.color.paper_peach)
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        }
        titleRow.addView(icon, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 12.dp })
        titleRow.addView(ctx.paperText(getString(R.string.paper_library), 17f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val badge = ctx.paperText("", 10f).apply { setPadding(6.dp, 4.dp, 6.dp, 4.dp); paperPill(ctx, R.color.paper_sage) }
        badge.setTextColor(0xFF3D4A3D.toInt())
        titleRow.addView(badge)
        overview.paperAdd(titleRow)
        val summary = ctx.paperText("", 12f, color = R.color.paper_muted)
        overview.paperAdd(summary, 12)
        val searchRow = ctx.paperRow().apply { paperPill(ctx); setPadding(10.dp, 0, 8.dp, 0) }
        searchRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.search); setColorFilter(ContextCompat.getColor(ctx, R.color.paper_muted))
        }, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 8.dp })
        val search = EditText(ctx).apply {
            id = R.id.CategoriesSearch; hint = getString(R.string.paper_category_search)
            setText(query); textSize = 14f; isSingleLine = true; background = null
            setTextColor(ContextCompat.getColor(ctx, R.color.paper_ink))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.paper_muted))
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 44.dp, 1f))
        searchRow.addView(ctx.paperIcon(R.drawable.close, getString(R.string.cancel)) { search.setText("") }, LinearLayout.LayoutParams(36.dp, 40.dp))
        overview.paperAdd(searchRow, 14)
        root.paperAdd(ctx.paperCard(overview).apply { cardElevation = 0f; radius = 16.dp.toFloat(); strokeWidth = 0 }, 16)
        val controls = ctx.paperRow()
        val heading = ctx.paperText("", 11f, color = R.color.paper_muted)
        controls.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val sort = ctx.paperButton("") {
            reordering = !reordering
            refresh()
        }.apply { id = R.id.CategorySort; textSize = 11f; setPadding(8.dp, 0, 8.dp, 0); minWidth = 0 }
        controls.addView(sort, LinearLayout.LayoutParams(-2, 40.dp))
        val add = ctx.paperButton("", true) { addCategory() }.apply {
            id = R.id.CategoryAdd; textSize = 11f; setPadding(10.dp, 0, 10.dp, 0); minWidth = 0
        }
        controls.addView(add, LinearLayout.LayoutParams(-2, 40.dp).apply { marginStart = 6.dp })
        root.paperAdd(controls, 16)
        val hint = ctx.paperText("", 11f, color = R.color.paper_muted)
        root.paperAdd(hint, 8)
        val empty = ctx.paperText("", 14f, color = R.color.paper_muted).apply { visibility = View.GONE }
        root.paperAdd(empty, 12)
        val list = RecyclerView(ctx).apply {
            id = R.id.CategoryList; layoutManager = LinearLayoutManager(ctx); clipToPadding = false
            setPadding(0, 10.dp, 0, 12.dp)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        ui = Ui(tabs, summary, badge, heading, sort, add, search, hint, empty, list)
        return root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        model.folder.value = Folder.NOTES
        adapter = CategoryAdapter()
        ui!!.list.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = reordering && query.isBlank()
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (reordering && query.isBlank()) super.getMovementFlags(rv, vh) else 0
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val rows = adapter?.entries ?: return false
                val start = from.bindingAdapterPosition; val end = to.bindingAdapterPosition
                if (start !in rows.indices || end !in rows.indices) return false
                dragging = true
                rows.add(end, rows.removeAt(start)); adapter?.notifyItemMoved(start, end)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (!dragging) return
                dragging = false
                val names = adapter?.entries.orEmpty().map { it.name }
                if (folders) model.updateFolders(names.mapIndexed { index, name -> NoteFolder(name, names.size - index - 1) })
                else model.updateLabels(names.mapIndexed { index, name -> Label(name, names.size - index - 1) })
            }
        }).also { it.attachToRecyclerView(ui!!.list) }
        ui!!.search.doAfterTextChanged { query = it.toString(); if (query.isNotBlank()) reordering = false; refresh() }
        model.labels.observe(viewLifecycleOwner) { refresh() }
        model.folders.observe(viewLifecycleOwner) { refresh() }
        model.preferences.labelsHidden.observe(viewLifecycleOwner) { refresh() }
        model.preferences.foldersHidden.observe(viewLifecycleOwner) { refresh() }
        SyncStatus.state.observe(viewLifecycleOwner) { refresh() }
        MemoXDatabase.getDatabase(requireActivity().application, false).value.getBaseNoteDao()
            .getFrom(Folder.NOTES).observe(viewLifecycleOwner) { notes = it; refresh() }
        refresh()
    }

    private fun refresh() {
        val ui = ui ?: return
        if (dragging) return
        val names = if (folders) model.folders.value.orEmpty().map { it.name } else model.labels.value.orEmpty().map { it.value }
        val hidden = if (folders) model.preferences.foldersHidden.value else model.preferences.labelsHidden.value
        ui.tabs.removeAllViews()
        listOf(false, true).forEach { folderTab ->
            ui.tabs.addView(requireContext().paperButton(getString(if (folderTab) R.string.paper_folder_categories else R.string.paper_label_categories), folderTab == folders) {
                folders = folderTab; reordering = false; refresh()
            }, LinearLayout.LayoutParams(0, 40.dp, 1f))
        }
        ui.summary.text = getString(if (folders) R.string.paper_folder_summary else R.string.paper_label_summary, names.size, notes.size)
        ui.badge.setText(when (SyncStatus.state.value?.state) {
            SyncState.SYNCING -> R.string.paper_syncing
            SyncState.COMPLETED -> R.string.paper_synced
            else -> R.string.paper_category_local
        })
        ui.heading.setText(if (folders) R.string.paper_custom_folders else R.string.paper_custom_labels)
        ui.sort.setText(if (reordering) R.string.paper_reorder_done else R.string.paper_reorder)
        ui.sort.isEnabled = query.isBlank()
        ui.add.setText(if (folders) R.string.add_folder else R.string.add_label)
        ui.hint.setText(if (query.isNotBlank()) R.string.paper_reorder_search_hint else R.string.paper_reorder_hint)
        ui.hint.visibility = if (reordering || query.isNotBlank()) View.VISIBLE else View.GONE
        val rows = names.filter { it.contains(query.trim(), ignoreCase = true) }.map { name ->
            Entry(name, notes.count { if (folders) it.folderId == name else name in it.labels }, name in hidden)
        }
        adapter?.entries = rows.toMutableList(); adapter?.notifyDataSetChanged()
        ui.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        ui.empty.setText(if (names.isEmpty()) R.string.paper_no_categories else R.string.paper_category_no_match)
    }

    private fun addCategory() {
        val isFolder = folders
        val input = EditText(requireContext()).apply { isSingleLine = true; setPadding(24.dp, 12.dp, 24.dp, 12.dp) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (isFolder) R.string.add_folder else R.string.add_label)
            .setView(input).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(-1)
            save.isEnabled = false
            input.doAfterTextChanged { save.isEnabled = !it.isNullOrBlank() }
            save.setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setOnClickListener
                val existing = if (isFolder) model.folders.value.orEmpty().map { it.name } else model.labels.value.orEmpty().map { it.value }
                if (name in existing) {
                    input.error = getString(if (isFolder) R.string.folder_exists else R.string.label_exists)
                    return@setOnClickListener
                }
                val result: (Boolean) -> Unit = { success ->
                    if (success) dialog.dismiss()
                    else if (isAdded) input.error = getString(if (isFolder) R.string.folder_exists else R.string.label_exists)
                }
                if (isFolder) model.insertFolder(name, result) else model.insertLabel(name, result)
            }
        }
        dialog.show()
    }

    private fun showActions(entry: Entry, anchor: View) {
        val isFolder = folders
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, getString(if (isFolder) R.string.edit_folder else R.string.edit_label))
            menu.add(0, 2, 1, getString(if (entry.hidden) R.string.paper_category_show else R.string.paper_category_hide))
            menu.add(0, 3, 2, getString(if (isFolder) R.string.delete_folder else R.string.delete_label))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> if (isFolder) displayEditFolderDialog(entry.name, model) else displayEditLabelDialog(entry.name, model)
                    2 -> {
                        val preference = if (isFolder) model.preferences.foldersHidden else model.preferences.labelsHidden
                        val values = preference.value.toMutableSet()
                        if (entry.hidden) values.remove(entry.name) else values.add(entry.name)
                        model.savePreference(preference, values)
                    }
                    3 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(if (isFolder) R.string.delete_folder else R.string.delete_label)
                        .setMessage(R.string.paper_category_delete_message).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> if (isFolder) model.deleteFolder(entry.name) else model.deleteLabel(entry.name) }.show()
                }
                true
            }
        }.show()
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putBoolean("folders", folders); state.putString("query", query); state.putBoolean("reordering", reordering)
    }
    override fun onDestroyView() {
        touchHelper?.attachToRecyclerView(null); touchHelper = null
        ui?.list?.adapter = null; ui = null; adapter = null
        super.onDestroyView()
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryHolder>() {
        var entries = mutableListOf<Entry>()
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): CategoryHolder {
            val ctx = parent.context
            val row = ctx.paperRow().apply { setPadding(12.dp, 16.dp, 4.dp, 16.dp) }
            val icon = ImageView(ctx).apply { setPadding(9.dp, 9.dp, 9.dp, 9.dp); paperPill(ctx, R.color.paper_peach) }
            row.addView(icon, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 10.dp })
            val column = ctx.paperColumn()
            val name = ctx.paperText("", 17f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val subtitle = ctx.paperText("", 11f, color = R.color.paper_muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            column.paperAdd(name); column.paperAdd(subtitle, 6)
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            val count = ctx.paperText("", 10f).apply { paperPill(ctx); setPadding(6.dp, 4.dp, 6.dp, 4.dp) }
            row.addView(count, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 6.dp })
            val more = ctx.paperIcon(R.drawable.more_vert, "") {}
            row.addView(more, LinearLayout.LayoutParams(40.dp, 48.dp))
            val drag = ctx.paperIcon(R.drawable.drag_handle, "") {}
            row.addView(drag, LinearLayout.LayoutParams(36.dp, 48.dp))
            val card = ctx.paperCard(row).apply {
                cardElevation = 0f; radius = 16.dp.toFloat(); strokeWidth = 0
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = 8.dp }
            }
            return CategoryHolder(card, icon, name, subtitle, count, more, drag)
        }
        override fun onBindViewHolder(holder: CategoryHolder, position: Int) {
            val entry = entries[position]
            holder.name.text = entry.name
            holder.subtitle.setText(if (entry.hidden) R.string.paper_category_hidden else if (folders) R.string.paper_category_folder_hint else R.string.paper_category_label_hint)
            holder.count.text = getString(R.string.paper_category_count, entry.count)
            holder.icon.setImageResource(if (folders) R.drawable.folder else R.drawable.label)
            holder.icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.paper_accent_ink))
            holder.more.contentDescription = getString(R.string.paper_category_actions, entry.name)
            holder.more.setOnClickListener { showActions(entry, it) }
            holder.drag.visibility = if (reordering && query.isBlank()) View.VISIBLE else View.GONE
            holder.drag.contentDescription = getString(R.string.paper_category_drag, entry.name)
            holder.drag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(holder)
                false
            }
            holder.itemView.setOnClickListener {
                if (reordering) return@setOnClickListener
                findNavController().navigate(if (folders) R.id.DisplayFolder else R.id.DisplayLabel,
                    Bundle().apply { putString(if (folders) DisplayFolderFragment.EXTRA_DISPLAYED_FOLDER else DisplayLabelFragment.EXTRA_DISPLAYED_LABEL, entry.name) })
            }
            holder.itemView.setOnLongClickListener { showActions(entry, holder.more); true }
        }
    }
    private class CategoryHolder(view: View, val icon: ImageView, val name: TextView, val subtitle: TextView,
        val count: TextView, val more: View, val drag: View) : RecyclerView.ViewHolder(view)
}

