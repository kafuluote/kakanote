package cool.hin.memox.presentation.view.note.action

import androidx.annotation.ColorInt
import cool.hin.memox.R
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.EditAction

class ExportBottomSheet(@ColorInt color: Int?, callback: (ExportMimeType) -> Unit, onPreview: (() -> Unit)? = null) :
    ActionBottomSheet(createActions(callback, onPreview), color) {

    companion object {
        const val TAG = "ExportBottomSheet"

        private fun createActions(callback: (ExportMimeType) -> Unit, onPreview: (() -> Unit)?): List<Action> {
            val preview = onPreview?.let { action -> listOf(Action(R.string.paper_share_image) { action(); true }) }.orEmpty()
            return preview + ExportMimeType.entries.map { mimeType ->
                Action(labelResId = EditAction.EXPORT.textResId, label = mimeType.name) { _ ->
                    callback.invoke(mimeType)
                    true
                }
            }
        }
    }
}
