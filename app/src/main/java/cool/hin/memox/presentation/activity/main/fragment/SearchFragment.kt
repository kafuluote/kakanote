package cool.hin.memox.presentation.activity.main.fragment

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.isEmpty

class SearchFragment : MemoXFragment() {

    private var selectedLabel: String? = ""
    private var selectedFolder = Folder.NOTES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolder =
            savedInstanceState?.getString(STATE_FOLDER)?.let(Folder::valueOf) ?: arguments?.let {
                BundleCompat.getSerializable(it, EXTRA_INITIAL_FOLDER, Folder::class.java)
            } ?: model.folder.value
        selectedLabel =
            if (savedInstanceState != null) savedInstanceState.getString(STATE_LABEL)
            else if (arguments?.containsKey(EXTRA_INITIAL_LABEL) == true) {
                arguments?.getString(EXTRA_INITIAL_LABEL)
            } else model.currentLabel
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.currentLabel = selectedLabel
        model.folder.value = selectedFolder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding?.MainListView?.scrollIndicators = View.SCROLL_INDICATOR_TOP
        }
        super.onViewCreated(view, savedInstanceState)

        binding?.ChipGroup?.apply {
            check(
                when (selectedFolder) {
                    Folder.NOTES -> R.id.Notes
                    Folder.ARCHIVED -> R.id.Archived
                    Folder.DELETED -> R.id.Deleted
                }
            )
            setOnCheckedStateChangeListener { _, checkedIds ->
                selectedFolder =
                    when (checkedIds.firstOrNull()) {
                        R.id.Notes -> Folder.NOTES
                        R.id.Archived -> Folder.ARCHIVED
                        R.id.Deleted -> Folder.DELETED
                        else -> selectedFolder
                    }
                model.folder.value = selectedFolder
            }
            isVisible = true
        }
        setupLabelCategories(selectedLabel) {
            selectedLabel = it
            model.currentLabel = it
        }
        getObservable().observe(viewLifecycleOwner) { items ->
            model.actionMode.updateSelected(items?.filterIsInstance<BaseNote>()?.map { it.id })
            notesAdapter?.setSearchKeyword(model.keyword)
        }
        model.searchResults?.isLoading?.observe(viewLifecycleOwner) { isLoading ->
            binding?.ImageView?.isVisible = !isLoading && model.searchResults.isEmpty
            binding?.LoadingProgress?.isVisible = isLoading
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LABEL, selectedLabel)
        outState.putString(STATE_FOLDER, selectedFolder.name)
    }

    override fun getBackground() = R.drawable.search

    override fun getObservable() = model.searchResults!!.results

    companion object {
        const val EXTRA_INITIAL_FOLDER = "memox.intent.extra.INITIAL_FOLDER"
        const val EXTRA_INITIAL_LABEL = "memox.intent.extra.INITIAL_LABEL"
        private const val STATE_LABEL = "search.label"
        private const val STATE_FOLDER = "search.folder"
    }
}
