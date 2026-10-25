package com.callrecall.activities

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
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
import com.callrecall.utils.applyContentInsets
import com.callrecall.utils.ContactUtils
import com.callrecall.utils.PermissionHelper
import com.callrecall.utils.PhoneNumberUtils
import com.callrecall.utils.PermissionRequestPlan
import com.callrecall.utils.permissionRequestPlan
import com.callrecall.viewmodels.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: NoteListAdapter
    private var permissionDialog: AlertDialog? = null
    private var permissionStepInFlight = false
    private var batteryPromptShown = false
    private val permissionHistory by lazy { getSharedPreferences("permission_requests", MODE_PRIVATE) }

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
    ) {
        permissionStepInFlight = false
        requestPermissionsIfNeeded()
    }

    private val permissionSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionStepInFlight = false
        requestPermissionsIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionStepInFlight = savedInstanceState?.getBoolean("permissionStepInFlight") ?: false
        batteryPromptShown = savedInstanceState?.getBoolean("batteryPromptShown") ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

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
        binding.buttonEnableCalls.setOnClickListener { requestPermissionsIfNeeded() }
    }

    override fun onResume() {
        super.onResume()
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
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(48, 32, 48, 32)
        }
        val country = PhoneNumberUtils.defaultCountryLabel()
        val help = if (country == null) getString(R.string.phone_number_help_international)
            else getString(R.string.phone_number_help, country)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_phone_number)
            .setMessage(help)
            .setView(input)
            .setPositiveButton(R.string.create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phone = PhoneNumberUtils.normalize(input.text?.toString().orEmpty())
                if (phone.isNotEmpty()) {
                    NoteEditorActivity.start(this, 0L, phone)
                    dialog.dismiss()
                } else {
                    input.error = getString(R.string.invalid_phone_number)
                }
            }
        }
        dialog.show()
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
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        (application as CallRecallApp).registerOutgoingCallReceiverIfNeeded()
        val ready = PermissionHelper.hasAllCorePermissions(this)
        binding.buttonEnableCalls.visibility = if (ready) View.GONE else View.VISIBLE
        if (permissionStepInFlight) return
        if (ready) {
            permissionDialog?.dismiss()
            onPermissionsReady()
        } else if (permissionDialog?.isShowing != true) {
            showPermissionRationale()
        }
    }

    private fun currentPermissionPlan(): PermissionRequestPlan {
        val missing = PermissionHelper.missingRuntimePermissions(this)
        val blocked = missing.filter {
            permissionHistory.getBoolean(it, false) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }.toSet()
        return permissionRequestPlan(missing, blocked, PermissionHelper.hasOverlayPermission(this))
    }

    private fun showPermissionRationale() {
        val missing = PermissionHelper.missingPermissions(this)
        val plan = currentPermissionPlan()
        val actionLabel = when (plan) {
            PermissionRequestPlan.AppSettings -> R.string.open_app_settings
            PermissionRequestPlan.OverlaySettings -> R.string.open_overlay_settings
            else -> R.string.grant_permissions
        }
        val message = getString(R.string.permissions_needed, missing.joinToString(", ")) +
            if (plan == PermissionRequestPlan.AppSettings) "\n\n" + getString(R.string.permissions_blocked) else ""
        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(message)
            .setPositiveButton(actionLabel) { _, _ -> performPermissionStep() }
            .setNegativeButton(R.string.not_now, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { if (permissionDialog === dialog) permissionDialog = null }
                dialog.show()
            }
    }

    private fun performPermissionStep() {
        when (val plan = currentPermissionPlan()) {
            is PermissionRequestPlan.Runtime -> {
                permissionHistory.edit().apply {
                    plan.permissions.forEach { putBoolean(it, true) }
                }.apply()
                permissionStepInFlight = true
                permissionLauncher.launch(plan.permissions.toTypedArray())
            }
            PermissionRequestPlan.AppSettings -> openPermissionSettings(PermissionHelper.appSettingsIntent(this))
            PermissionRequestPlan.OverlaySettings -> openPermissionSettings(PermissionHelper.overlaySettingsIntent(this))
            PermissionRequestPlan.Ready -> requestPermissionsIfNeeded()
        }
    }

    private fun openPermissionSettings(intent: Intent) {
        permissionStepInFlight = true
        try {
            permissionSettingsLauncher.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            permissionSettingsLauncher.launch(PermissionHelper.appSettingsIntent(this))
        }
    }

    private fun onPermissionsReady() {
        (application as CallRecallApp).registerOutgoingCallReceiverIfNeeded()
        val preferences = getSharedPreferences("background_setup", MODE_PRIVATE)
        if (!batteryPromptShown && !preferences.getBoolean("prompt_shown", false) &&
            PermissionHelper.isBackgroundRestricted(this) &&
            !PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            batteryPromptShown = true
            // OEM background settings may not match Android's optimization whitelist.
            // Offer setup once per installation; the menu always allows reopening it.
            preferences.edit().putBoolean("prompt_shown", true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_optimization_title)
                .setMessage(R.string.battery_optimization_message)
                .setPositiveButton(R.string.open_battery_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                .setNegativeButton(R.string.not_now, null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionStepInFlight", permissionStepInFlight)
        outState.putBoolean("batteryPromptShown", batteryPromptShown)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_backup -> {
                startActivity(Intent(this, BackupActivity::class.java))
                true
            }
            R.id.action_background_settings -> {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            }
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
