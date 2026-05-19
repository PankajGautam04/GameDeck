package com.pankaj.gamedeck.ui.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pankaj.gamedeck.R

/**
 * Adapter for the app picker dialog.
 * Displays installed apps in a grid with icon + label.
 */
class AppPickerAdapter(
    private val onAppSelected: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var apps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()

    fun submitList(list: List<AppInfo>) {
        apps = list
        filteredApps = list
        notifyDataSetChanged()
    }

    /** Filter apps by search query */
    fun filter(query: String) {
        filteredApps = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.img_app_icon)
        private val label: TextView = itemView.findViewById(R.id.tv_app_label)

        fun bind(app: AppInfo) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.setOnClickListener { onAppSelected(app) }
        }
    }
}
