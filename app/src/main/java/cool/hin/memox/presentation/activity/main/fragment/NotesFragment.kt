package cool.hin.memox.presentation.activity.main.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import cool.hin.memox.R
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Item
import cool.hin.memox.presentation.activity.main.fragment.DisplayLabelFragment.Companion.EXTRA_DISPLAYED_LABEL

class NotesFragment : MemoXFragment() {

    private val categoryNotes = MediatorLiveData<List<Item>>()
    private var categorySource: LiveData<List<Item>>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState?.containsKey(STATE_LABEL) == true) {
            model.homeLabel = savedInstanceState.getString(STATE_LABEL)
        }
        model.folder.value = Folder.NOTES
        selectCategory(model.homeLabel)
        super.onViewCreated(view, savedInstanceState)
        setupLabelCategories(model.homeLabel, ::selectCategory)
    }

    private fun selectCategory(label: String?) {
        model.homeLabel = label
        model.currentLabel = label
        val source =
            when (label) {
                null -> model.getNotesWithoutLabel()
                "" -> model.baseNotes!!
                else -> model.getNotesByLabel(label)
            }
        categorySource?.let(categoryNotes::removeSource)
        categorySource = source
        categoryNotes.addSource(source) { categoryNotes.value = it }
    }

    override fun getObservable() = categoryNotes

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LABEL, model.homeLabel)
    }

    override fun prepareNewNoteIntent(intent: Intent): Intent {
        return model.homeLabel?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(EXTRA_DISPLAYED_LABEL, it)
        } ?: intent
    }

    override fun getBackground() = R.drawable.notebook

    companion object {
        private const val STATE_LABEL = "notes.label"
    }
}
