package com.callrecall.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.callrecall.R
import com.callrecall.CallRecallApp
import com.callrecall.databinding.ActivityNoteEditorBinding
import com.callrecall.utils.DateTimeUtils
import com.callrecall.utils.PhoneNumberUtils
import com.callrecall.utils.applyContentInsets
import com.callrecall.viewmodels.NoteEditorViewModel
import kotlinx.coroutines.launch

/**
 * Standalone note editor — independent of call lifecycle.
 * Stays open until the user explicitly closes it.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var viewModel: NoteEditorViewModel
    private var suppressTextCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { closeEditor() }

        val repository = (application as CallRecallApp).repository
        viewModel = ViewModelProvider(
            this,
            NoteEditorViewModel.Factory(application, repository)
        )[NoteEditorViewModel::class.java]

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, 0L)
        val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        val contactNameExtra = intent.getStringExtra(EXTRA_CONTACT_NAME)
        if (!contactNameExtra.isNullOrBlank() && !phone.isNullOrBlank()) {
            viewModel.loadFromContact(contactNameExtra, phone)
        } else {
            viewModel.load(noteId, phone)
        }

        setupFields()
        observeViewModel()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeEditor()
                }
            }
        )
    }

    private fun setupFields() {
        val country = PhoneNumberUtils.defaultCountryLabel()
        binding.phoneNumberLayout.helperText = if (country == null) {
            getString(R.string.phone_number_help_international)
        } else {
            getString(R.string.phone_number_help, country)
        }
        binding.editName.addTextChangedListener { text ->
            if (!suppressTextCallbacks) viewModel.updateName(text?.toString().orEmpty())
        }
        binding.editPhoneNumber.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !suppressTextCallbacks) {
                viewModel.updatePhoneNumber(binding.editPhoneNumber.text?.toString().orEmpty())
            }
        }
        binding.editNoteContent.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!suppressTextCallbacks) {
                        viewModel.updateNoteContent(s?.toString().orEmpty())
                    }
                }
            }
        )
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.phoneNumberError.collect { error ->
                binding.phoneNumberLayout.error = error?.let { getString(it) }
            }
        }
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.name,
                viewModel.phoneNumber,
                viewModel.updatedAt,
                viewModel.isLoading
            ) { name, phone, updated, loading ->
                arrayOf(name, phone, updated, loading)
            }.collect { values ->
                if (values[3] as Boolean) return@collect
                suppressTextCallbacks = true
                val name = values[0] as String
                if (binding.editName.text?.toString() != name) binding.editName.setText(name)
                if (!binding.editPhoneNumber.hasFocus()) {
                    val phone = values[1] as String
                    if (binding.editPhoneNumber.text?.toString() != phone) binding.editPhoneNumber.setText(phone)
                }
                binding.textLastUpdated.text = getString(
                    com.callrecall.R.string.last_updated,
                    DateTimeUtils.formatDateTime(values[2] as Long)
                )
                suppressTextCallbacks = false
            }
        }
        lifecycleScope.launch {
            viewModel.noteContent.collect { content ->
                if (binding.editNoteContent.text?.toString() != content) {
                    suppressTextCallbacks = true
                    binding.editNoteContent.setText(content)
                    binding.editNoteContent.setSelection(content.length)
                    suppressTextCallbacks = false
                }
            }
        }
    }

    private fun closeEditor() {
        if (binding.editPhoneNumber.text.isNullOrBlank() &&
            binding.editName.text.isNullOrBlank() && binding.editNoteContent.text.isNullOrBlank()
        ) {
            finish()
            return
        }
        viewModel.updateName(binding.editName.text?.toString().orEmpty())
        viewModel.updatePhoneNumber(binding.editPhoneNumber.text?.toString().orEmpty())
        viewModel.updateNoteContent(binding.editNoteContent.text?.toString().orEmpty())
        lifecycleScope.launch {
            if (viewModel.persist()) {
                finish()
            } else {
                binding.editPhoneNumber.requestFocus()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.updateName(binding.editName.text?.toString().orEmpty())
        viewModel.updatePhoneNumber(binding.editPhoneNumber.text?.toString().orEmpty())
        viewModel.updateNoteContent(binding.editNoteContent.text?.toString().orEmpty())
        lifecycleScope.launch { viewModel.persist() }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { viewModel.persist() }
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"

        fun start(context: Context, noteId: Long, phoneNumber: String) {
            context.startActivity(
                Intent(context, NoteEditorActivity::class.java).apply {
                    putExtra(EXTRA_NOTE_ID, noteId)
                    putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                    if (context !is AppCompatActivity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            )
        }

        fun startFromContact(context: Context, name: String, phone: String) {
            context.startActivity(
                Intent(context, NoteEditorActivity::class.java).apply {
                    putExtra(EXTRA_PHONE_NUMBER, phone)
                    putExtra(EXTRA_CONTACT_NAME, name)
                }
            )
        }
    }
}
