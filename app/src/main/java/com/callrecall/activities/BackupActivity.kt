package com.callrecall.activities

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.callrecall.R
import com.callrecall.backup.RestoreConflict
import com.callrecall.databinding.ActivityBackupBinding
import com.callrecall.utils.DateTimeUtils
import com.callrecall.utils.applyContentInsets
import com.callrecall.viewmodels.BackupEvent
import com.callrecall.viewmodels.BackupUiState
import com.callrecall.viewmodels.BackupViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class BackupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBackupBinding
    private lateinit var viewModel: BackupViewModel
    private var unlockDialog: AlertDialog? = null
    private var warningDialog: AlertDialog? = null
    private var reviewDialog: AlertDialog? = null
    private var noticeDialog: AlertDialog? = null

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { viewModel.exportTo(it) }
    private val selectBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { viewModel.readBackup(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)
        viewModel = ViewModelProvider(this)[BackupViewModel::class.java]
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.switchPassword.setOnCheckedChangeListener { _, checked ->
            binding.passwordFields.visibility = if (checked) View.VISIBLE else View.GONE
            binding.textPasswordHelp.setText(if (checked) R.string.backup_password_warning else R.string.backup_plain_warning)
            if (!checked) clearPasswordFields()
        }
        binding.buttonExport.setOnClickListener { requestExport() }
        binding.buttonSelectBackup.setOnClickListener {
            try {
                selectBackup.launch(arrayOf("*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
                showNotice(getString(R.string.backup_operation_failed))
            }
        }
        binding.buttonReviewConflicts.setOnClickListener { reviewConflicts() }
        binding.buttonRestore.setOnClickListener { confirmRestore() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is BackupEvent.ChooseExportLocation -> try {
                                createBackup.launch(event.filename)
                            } catch (_: android.content.ActivityNotFoundException) {
                                viewModel.exportTo(null)
                                showNotice(getString(R.string.backup_operation_failed))
                            }
                            is BackupEvent.Notice -> showNotice(getString(event.message, *event.counts.toTypedArray()))
                        }
                    }
                }
            }
        }
    }

    private fun showNotice(message: String) {
        noticeDialog?.dismiss()
        noticeDialog = MaterialAlertDialogBuilder(this)
            .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun requestExport() {
        binding.passwordLayout.error = null
        binding.confirmPasswordLayout.error = null
        if (!binding.switchPassword.isChecked) {
            viewModel.prepareExport(null)
            return
        }
        val text = binding.editPassword.text
        val password = CharArray(text?.length ?: 0) { text!![it] }
        val confirmation = binding.editConfirmPassword.text
        val confirm = CharArray(confirmation?.length ?: 0) { confirmation!![it] }
        val matches = password.contentEquals(confirm)
        confirm.fill('\u0000')
        if (password.size < 8) {
            password.fill('\u0000')
            binding.passwordLayout.error = getString(R.string.backup_password_short)
            return
        }
        if (!matches) {
            password.fill('\u0000')
            binding.confirmPasswordLayout.error = getString(R.string.backup_password_mismatch)
            return
        }
        warningDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_password_warning_title)
            .setMessage(R.string.backup_password_warning)
            .setPositiveButton(R.string.backup_encrypt_continue) { _, _ ->
                viewModel.prepareExport(password.copyOf())
                clearPasswordFields()
            }
            .setNegativeButton(R.string.backup_without_password) { _, _ ->
                binding.switchPassword.isChecked = false
                viewModel.prepareExport(null)
                clearPasswordFields()
            }
            .setNeutralButton(R.string.cancel, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { password.fill('\u0000'); warningDialog = null }
                dialog.show()
            }
    }

    private fun clearPasswordFields() {
        binding.editPassword.text?.clear()
        binding.editConfirmPassword.text?.clear()
    }

    private fun render(state: BackupUiState) {
        val interactive = !state.busy && !state.waitingForExportLocation && !state.passwordNeeded
        binding.buttonExport.isEnabled = interactive
        binding.buttonSelectBackup.isEnabled = interactive
        binding.switchPassword.isEnabled = interactive
        binding.editPassword.isEnabled = interactive
        binding.editConfirmPassword.isEnabled = interactive
        binding.progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        binding.textLastExport.text = if (state.lastExportAt == 0L) getString(R.string.backup_never_exported)
            else getString(R.string.backup_last_export, DateTimeUtils.formatDateTime(state.lastExportAt))
        val preview = state.preview
        binding.previewContainer.visibility = if (preview == null) View.GONE else View.VISIBLE
        if (preview != null) {
            val summary = getString(R.string.backup_preview,
                DateTimeUtils.formatDateTime(preview.backup.exportedAt), preview.backup.notes.size,
                preview.additions.size, preview.identicalCount, preview.conflicts.size, state.replacePhones.size)
            binding.textPreview.text = if (preview.legacyNumberCount > 0) {
                getString(R.string.backup_preview_with_legacy, summary, getString(R.string.backup_legacy_numbers))
            } else summary
            binding.buttonReviewConflicts.visibility = if (preview.conflicts.isEmpty()) View.GONE else View.VISIBLE
            binding.buttonReviewConflicts.isEnabled = interactive
            binding.buttonRestore.isEnabled = interactive
        }
        if (state.passwordNeeded && !state.busy && unlockDialog == null) showUnlockDialog()
    }

    private fun showUnlockDialog() {
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.backup_password)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        inputLayout.addView(input)
        val container = LinearLayout(this).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(inputLayout, LinearLayout.LayoutParams(-1, -2))
        }
        unlockDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_unlock_title)
            .setMessage(R.string.backup_unlock_help)
            .setView(container)
            .setPositiveButton(R.string.backup_unlock_action, null)
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.cancelUnlock() }
            .create().also { dialog ->
                dialog.setOnCancelListener { viewModel.cancelUnlock() }
                dialog.setOnDismissListener { input.text?.clear(); unlockDialog = null }
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text
                        if (text.isNullOrEmpty()) {
                            inputLayout.error = getString(R.string.backup_enter_password)
                        } else {
                            viewModel.unlock(CharArray(text.length) { text[it] })
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun reviewConflicts() {
        val state = viewModel.state.value
        val conflicts = state.preview?.conflicts.orEmpty()
        val labels = conflicts.map {
            val choice = if (it.incoming.phoneNumber in state.replacePhones) R.string.backup_use_backup else R.string.backup_keep_current
            "${it.incoming.displayLabel()}\n${it.incoming.phoneNumber} · ${getString(choice)}"
        }.toTypedArray()
        reviewDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_review_conflicts)
            .setItems(labels) { _, index -> showConflict(conflicts[index]) }
            .setNegativeButton(android.R.string.ok, null).show()
    }

    private fun showConflict(conflict: RestoreConflict) {
        fun snippet(text: String): String = if (text.length > 2000) text.take(2000) + "\n…" else text
        reviewDialog = MaterialAlertDialogBuilder(this)
            .setTitle(conflict.incoming.phoneNumber)
            .setMessage(getString(R.string.backup_conflict_comparison,
                conflict.existing.name, DateTimeUtils.formatDateTime(conflict.existing.updatedAt), snippet(conflict.existing.noteContent),
                conflict.incoming.name, DateTimeUtils.formatDateTime(conflict.incoming.updatedAt), snippet(conflict.incoming.noteContent)))
            .setPositiveButton(R.string.backup_use_backup) { _, _ -> viewModel.chooseReplacement(conflict.incoming.phoneNumber, true) }
            .setNegativeButton(R.string.backup_keep_current) { _, _ -> viewModel.chooseReplacement(conflict.incoming.phoneNumber, false) }
            .setNeutralButton(R.string.cancel, null).show()
    }

    private fun confirmRestore() {
        val state = viewModel.state.value
        val preview = state.preview ?: return
        reviewDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore_action)
            .setMessage(getString(R.string.backup_restore_confirmation,
                preview.additions.size, state.replacePhones.size, preview.conflicts.size - state.replacePhones.size,
                preview.identicalCount))
            .setPositiveButton(R.string.backup_restore_action) { _, _ -> viewModel.restore() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onDestroy() {
        unlockDialog?.dismiss()
        warningDialog?.dismiss()
        reviewDialog?.dismiss()
        noticeDialog?.dismiss()
        super.onDestroy()
    }
}
