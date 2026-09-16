package com.neilturner.aerialviews.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.databinding.DialogUpdatePromptBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Option C4 dialog: art panel (version numeral + current → new meta) on the
 * left, release notes on the right, and a determinate progress row owned by
 * the returned [UpdateDialogHandle] so the download phase needs no second
 * dialog. The system DownloadManager notification stays the fallback for a
 * dismissed dialog.
 */
object HomeUpdatePromptHelper {
    class UpdateDialogHandle(
        val dialog: AlertDialog,
        private val binding: DialogUpdatePromptBinding,
    ) {
        fun showDownloading() {
            binding.updatePromptProgressRow.visibility = android.view.View.VISIBLE
            binding.updatePromptDownload.isEnabled = false
            setProgress(0)
        }

        fun setProgress(percent: Int) {
            binding.updatePromptProgress.progress = percent.coerceIn(0, 100)
            binding.updatePromptProgressText.text =
                binding.root.context.getString(R.string.home_update_downloading, percent.coerceIn(0, 100))
        }

        fun showDownloaded() {
            binding.updatePromptProgress.progress = 100
            binding.updatePromptProgressText.text =
                binding.root.context.getString(R.string.home_update_downloaded)
        }

        fun showFailed() {
            binding.updatePromptDownload.isEnabled = true
            binding.updatePromptProgressRow.visibility = android.view.View.GONE
        }
    }

    fun show(
        context: Context,
        currentVersion: String,
        updateInfo: UpdateInfo,
        onDownload: (UpdateDialogHandle) -> Unit,
        onLater: () -> Unit,
    ): UpdateDialogHandle {
        val binding = DialogUpdatePromptBinding.inflate(LayoutInflater.from(context))
        val newVersion = updateInfo.tagName.removePrefix("v")

        binding.updatePromptAppName.text = context.getString(R.string.home_update_app_name)
        binding.updatePromptVersion.text = newVersion
        binding.updatePromptSummary.text =
            context.getString(
                R.string.home_update_art_meta,
                currentVersion.removePrefix("v"),
                newVersion,
            )

        binding.updatePromptHighlightsLabel.text = context.getString(R.string.home_update_highlights)
        binding.updatePromptDate.text =
            SimpleDateFormat("MMMM yyyy", Locale.US).format(Date()).uppercase(Locale.US)
        binding.updatePromptNotes.text = formatReleaseNotes(context, updateInfo.releaseNotes)

        binding.updatePromptDownload.text = context.getString(R.string.home_update_download)
        binding.updatePromptLater.text = context.getString(R.string.home_update_later)

        val dialog = AlertDialog.Builder(context).setView(binding.root).create()
        val handle = UpdateDialogHandle(dialog, binding)
        dialog.setOnCancelListener { onLater() }
        dialog.setCanceledOnTouchOutside(false)

        binding.updatePromptDownload.setOnClickListener {
            onDownload(handle)
        }
        binding.updatePromptLater.setOnClickListener {
            onLater()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.62f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.60f).toInt(),
        )
        binding.updatePromptDownload.requestFocus()
        return handle
    }

    private fun formatReleaseNotes(
        context: Context,
        releaseNotes: String,
    ): String {
        val formatted =
            releaseNotes
                .replace("\\n", "\n")
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    when {
                        line.startsWith("## ") -> line.removePrefix("## ").trim()
                        line.startsWith("# ") -> line.removePrefix("# ").trim()
                        line.startsWith("- ") -> "• ${line.removePrefix("- ").trim()}"
                        line.startsWith("* ") -> "• ${line.removePrefix("* ").trim()}"
                        line.startsWith("• ") -> line.trim()
                        else -> "• ${line.trim()}"
                    }
                }
                .joinToString("\n")
                .trim()

        return formatted.ifBlank { context.getString(R.string.home_update_empty_notes) }
    }
}
