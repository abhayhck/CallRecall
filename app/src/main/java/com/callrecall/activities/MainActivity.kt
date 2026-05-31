package com.callrecall.activities

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.callrecall.CallRecallApp
import com.callrecall.R
import com.callrecall.adapters.NoteListAdapter
import com.callrecall.databinding.ActivityMainBinding
import com.callrecall.models.ContactNote
import com.callrecall.models.SortOption
import com.callrecall.utils.ContactUtils
import com.callrecall.utils.PermissionHelper
import com.callrecall.viewmodels.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: NoteListAdapter

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            val info = ContactUtils.resolveContact(this, uri)
            if (info != null) {
                NoteEditorActivity.startFromContact(this, info.name, info.phoneNumber)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            onPermissionsReady()
        } else {
            showPermissionRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val repository = (application as CallRecallApp).repository
        viewModel = ViewModelProvider(
            this,
            MainViewModel.Factory(repository)
        )[MainViewModel::class.java]

        setupRecyclerView()
        setupSearch()
        setupFab()
        observeNotes()
        requestPermissionsIfNeeded()
    }

    private fun setupRecyclerView() {
        adapter = NoteListAdapter(
            onNoteClick = { note ->
                NoteEditorActivity.start(this, note.id, note.phoneNumber)
            },
            onNoteLongClick = { note -> confirmDelete(note) }
        )
        binding.recyclerNotes.layoutManager = LinearLayoutManager(this)
        binding.recyclerNotes.adapter = adapter
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
        }
    }

    private fun setupFab() {
        binding.fabNewNote.setOnClickListener { showNewNoteOptions() }
    }

    private fun observeNotes() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.notes.collect { notes ->
                    adapter.submitList(notes)
                    binding.textEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showNewNoteOptions() {
        val options = arrayOf(
            getString(R.string.new_note_from_contact),
            getString(R.string.new_note_manual_phone)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_note)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createFromContact()
                    1 -> createFromManualPhone()
                }
            }
            .show()
    }

    private fun createFromContact() {
        if (!PermissionHelper.hasReadContacts(this)) {
            requestPermissionsIfNeeded()
            return
        }
        pickContactLauncher.launch(null)
    }

    private fun createFromManualPhone() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.hint_phone_number)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_phone_number)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val phone = input.text?.toString().orEmpty()
                if (com.callrecall.utils.PhoneNumberUtils.isValid(phone)) {
                    NoteEditorActivity.start(this, 0L, phone)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(note: ContactNote) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_note_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteNote(note) {}
            }
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        if (PermissionHelper.hasAllCorePermissions(this)) {
            onPermissionsReady()
            return
        }
        showPermissionRationale()
    }

    private fun showPermissionRationale() {
        val missing = PermissionHelper.missingPermissions(this)
        val message = getString(R.string.permissions_needed, missing.joinToString(", "))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(message)
            .setPositiveButton(R.string.grant_permissions) { _, _ ->
                permissionLauncher.launch(PermissionHelper.REQUIRED_RUNTIME_PERMISSIONS)
                if (!PermissionHelper.hasOverlayPermission(this)) {
                    startActivity(PermissionHelper.overlaySettingsIntent(this))
                }
            }
            .setNeutralButton(R.string.open_overlay_settings) { _, _ ->
                startActivity(PermissionHelper.overlaySettingsIntent(this))
            }
            .show()
    }

    private fun onPermissionsReady() {
        (application as CallRecallApp).registerOutgoingCallReceiverIfNeeded()
        if (!PermissionHelper.hasOverlayPermission(this)) {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
            return
        }
        if (!PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_optimization_title)
                .setMessage(R.string.battery_optimization_message)
                .setPositiveButton(R.string.open_battery_settings) { _, _ ->
                    try {
                        startActivity(PermissionHelper.batteryOptimizationSettingsIntent(this))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortDialog() {
        val options = SortOption.entries.map { it.label }.toTypedArray()
        val current = viewModel.currentSortOption.value
        val selectedIndex = SortOption.entries.indexOf(current)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                viewModel.setSortOption(SortOption.entries[which])
                dialog.dismiss()
            }
            .show()
    }
}
