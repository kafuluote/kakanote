package cool.hin.memox.utils

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.BuildConfig
import cool.hin.memox.R

/** DialogFragment 保证旋转和进程重建后仍能恢复强制更新弹窗。 */
class UpdateDialogFragment : DialogFragment() {
    internal val info: UpdateChecker.UpdateInfo
        get() = UpdateProtocol.parse(
            requireArguments().getString(ARG_INFO)!!,
            UpdateProtocol.currentVersion(BuildConfig.VERSION_NAME),
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = !info.required
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val update = info
        val changelog = update.changelog.ifBlank { getString(R.string.update_changelog_title) }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(
                if (update.required) R.string.update_required_title else R.string.new_version_available,
                update.version,
            ))
            .setMessage(
                if (update.required) getString(R.string.update_required_message) + "\n\n" + changelog
                else changelog,
            )
            .setPositiveButton(R.string.update_download, null)
        if (update.required) {
            builder.setNeutralButton(R.string.update_recheck, null)
        } else {
            builder.setNegativeButton(R.string.update_later, null)
        }
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        val alert = requireDialog() as AlertDialog
        alert.setCanceledOnTouchOutside(!info.required)
        // 替换默认监听，避免点“下载更新”后自动关闭强制更新弹窗。
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (UpdateChecker.openUpdate(requireContext(), info)) {
                if (!info.required) dismiss()
            } else {
                Toast.makeText(requireContext(), R.string.update_open_failed, Toast.LENGTH_LONG).show()
            }
        }
        if (info.required) {
            alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { button ->
                button.isEnabled = false
                UpdateChecker.checkNow(requireContext()) { result ->
                    if (!isAdded) return@checkNow
                    button.isEnabled = true
                    if (result == null) {
                        Toast.makeText(requireContext(), R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "version_update_dialog"
        private const val ARG_INFO = "update_info"

        fun newInstance(info: UpdateChecker.UpdateInfo) = UpdateDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_INFO, UpdateProtocol.encode(
                    info, UpdateProtocol.currentVersion(BuildConfig.VERSION_NAME),
                ))
            }
        }
    }
}
