package com.example.suppressor.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.suppressor.R
import com.example.suppressor.util.AppForegroundDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dialog enabling users to select applications for Exclusion (Blacklist) or Inclusion (Whitelist).
 */
class AppPickerDialog(
    private val activity: AppCompatActivity,
    private val title: String,
    private val initialSelected: Set<String>,
    private val onSave: (Set<String>) -> Unit
) : Dialog(activity) {

    data class AppItem(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        var isSelected: Boolean
    )

    private val selectedPackages = initialSelected.toMutableSet()
    private val allApps = mutableListOf<AppItem>()
    private val displayedApps = mutableListOf<AppItem>()
    private var adapter: AppPickerAdapter? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnClear: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_app_picker)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        initViews()
        loadInstalledApps()
        checkUsageAccessTip()
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tv_dialog_title)
        tvCount = findViewById(R.id.tv_dialog_selected_count)
        etSearch = findViewById(R.id.et_search_apps)
        progressBar = findViewById(R.id.progress_apps_loading)
        recyclerView = findViewById(R.id.rv_apps_list)
        btnClear = findViewById(R.id.btn_clear_selection)
        btnCancel = findViewById(R.id.btn_cancel_picker)
        btnSave = findViewById(R.id.btn_save_picker)

        tvTitle.text = title
        updateCountBadge()

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = AppPickerAdapter(displayedApps) { item ->
            if (item.isSelected) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }
            updateCountBadge()
        }
        recyclerView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClear.setOnClickListener {
            selectedPackages.clear()
            allApps.forEach { it.isSelected = false }
            adapter?.notifyDataSetChanged()
            updateCountBadge()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnSave.setOnClickListener {
            onSave(selectedPackages.toSet())
            dismiss()
        }
    }

    private fun updateCountBadge() {
        tvCount.text = context.getString(R.string.apps_selected_count, selectedPackages.size)
    }

    private fun loadInstalledApps() {
        activity.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val pm = activity.packageManager
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val items = mutableListOf<AppItem>()

                for (app in installed) {
                    // Only include apps that have a launch intent or are already selected
                    val isLaunchable = pm.getLaunchIntentForPackage(app.packageName) != null
                    val isAlreadySelected = selectedPackages.contains(app.packageName)

                    if (isLaunchable || isAlreadySelected) {
                        if (app.packageName == activity.packageName) continue

                        val label = try {
                            app.loadLabel(pm).toString()
                        } catch (e: Exception) {
                            app.packageName
                        }
                        val icon = try {
                            app.loadIcon(pm)
                        } catch (e: Exception) {
                            pm.defaultActivityIcon
                        }

                        items.add(
                            AppItem(
                                packageName = app.packageName,
                                label = label,
                                icon = icon,
                                isSelected = isAlreadySelected
                            )
                        )
                    }
                }

                // Sort: Selected first, then alphabetical by label
                items.sortedWith(
                    compareByDescending<AppItem> { it.isSelected }
                        .thenBy { it.label.lowercase(Locale.ROOT) }
                )
            }

            allApps.clear()
            allApps.addAll(list)
            filterApps(etSearch.text?.toString().orEmpty())

            progressBar.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        displayedApps.clear()
        if (q.isEmpty()) {
            displayedApps.addAll(allApps)
        } else {
            displayedApps.addAll(allApps.filter {
                it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
            })
        }
        adapter?.notifyDataSetChanged()
    }

    private fun checkUsageAccessTip() {
        if (!AppForegroundDetector.hasUsageStatsPermission(context)) {
            // Optional tip for optimal experience
        }
    }

    private class AppPickerAdapter(
        private val items: List<AppItem>,
        private val onToggle: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
            val tvLabel: TextView = view.findViewById(R.id.tv_app_label)
            val tvPackage: TextView = view.findViewById(R.id.tv_package_name)
            val cbSelected: CheckBox = view.findViewById(R.id.cb_app_selected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ivIcon.setImageDrawable(item.icon)
            holder.tvLabel.text = item.label
            holder.tvPackage.text = item.packageName
            holder.cbSelected.isChecked = item.isSelected

            holder.itemView.setOnClickListener {
                item.isSelected = !item.isSelected
                holder.cbSelected.isChecked = item.isSelected
                onToggle(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
