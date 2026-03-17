package io.stamethyst.ui

import android.app.Activity
import android.os.CountDownTimer
import androidx.appcompat.app.AlertDialog
import io.stamethyst.R
import io.stamethyst.backend.file_interactive.FileLinkOpener

internal object VupShionPatchedDialog {
    private const val CONFIRM_DELAY_MS = 5_000L
    private const val CONFIRM_TICK_MS = 1_000L

    fun show(host: Activity) {
        val confirmText = host.getString(R.string.mod_import_dialog_vupshion_confirm)
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_vupshion_patched_title)
            .setMessage(R.string.mod_import_dialog_vupshion_special_message)
            .setNegativeButton(R.string.mod_import_dialog_vupshion_open_workshop, null)
            .setNeutralButton(R.string.mod_import_dialog_vupshion_open_afdian, null)
            .setPositiveButton(
                host.getString(R.string.mod_import_dialog_vupshion_confirm_countdown, 5),
                null
            )
            .create()

        var timer: CountDownTimer? = null
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                FileLinkOpener.open(
                    host,
                    host.getString(R.string.mod_import_dialog_vupshion_workshop_url)
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                FileLinkOpener.open(
                    host,
                    host.getString(R.string.mod_import_dialog_vupshion_afdian_url)
                )
            }
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmButton.isEnabled = false
            confirmButton.setOnClickListener {
                dialog.dismiss()
            }
            timer = object : CountDownTimer(CONFIRM_DELAY_MS, CONFIRM_TICK_MS) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = ((millisUntilFinished + 999L) / 1000L).toInt()
                    confirmButton.text = host.getString(
                        R.string.mod_import_dialog_vupshion_confirm_countdown,
                        seconds
                    )
                }

                override fun onFinish() {
                    confirmButton.text = confirmText
                    confirmButton.isEnabled = true
                }
            }.also { countdown ->
                countdown.start()
            }
        }
        dialog.setOnDismissListener {
            timer?.cancel()
            timer = null
        }
        dialog.show()
    }
}
