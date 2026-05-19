package com.pankaj.gamedeck.ui.config

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pankaj.gamedeck.R

/**
 * Dialog for selecting installed apps to add to the widget.
 * Shows a searchable grid of all launchable apps.
 */
class InstalledAppPickerDialog : DialogFragment() {

    private val viewModel: WidgetConfigViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_app_picker, null)

        val searchBar = view.findViewById<EditText>(R.id.et_search_apps)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_apps)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_loading)

        val adapter = AppPickerAdapter { appInfo ->
            viewModel.addGame(appInfo)
            dismiss()
        }

        recyclerView.layoutManager = GridLayoutManager(requireContext(), 4)
        recyclerView.adapter = adapter

        // Observe installed apps
        viewModel.installedApps.observe(this) { apps ->
            adapter.submitList(apps)
        }

        viewModel.isLoading.observe(this) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            recyclerView.visibility = if (loading) View.GONE else View.VISIBLE
        }

        // Search filter
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Load apps if not already loaded
        if (viewModel.installedApps.value.isNullOrEmpty()) {
            viewModel.loadInstalledApps()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_apps))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
    }
}
