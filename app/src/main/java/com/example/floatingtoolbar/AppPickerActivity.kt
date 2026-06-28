package com.example.floatingtoolbar

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * Lets the user pick which installed apps - including system apps such as
 * Settings, Camera, Phone, or Calculator - appear as shortcuts in the
 * floating panel's tools grid.
 *
 * The list has two sections in a single RecyclerView:
 *  - "Your shortcuts": the chosen apps, draggable (via the handle) to
 *    reorder, with a quick remove button.
 *  - "All apps": every launchable app, filterable via the search box,
 *    with a checkbox to add/remove it from the shortcuts above.
 *
 * Every change is saved immediately and broadcast to FloatingToolbarService
 * so a running overlay updates without needing to be restarted.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingText: TextView
    private lateinit var searchInput: EditText
    private lateinit var selectedOrder: MutableList<String>
    private lateinit var adapter: PickerAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var apps: List<AppEntry> = emptyList()
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        recyclerView = findViewById(R.id.appRecyclerView)
        loadingText = findViewById(R.id.loadingText)
        searchInput = findViewById(R.id.searchInput)

        selectedOrder = PrefsHelper.getSelectedApps(this).toMutableList()
        adapter = PickerAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recyclerView)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString() ?: ""
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Querying every launchable app can take a moment on devices with
        // lots of installed apps, so do it off the main thread.
        Thread {
            val loaded = loadLaunchableApps()
            runOnUiThread {
                apps = loaded
                refreshList()
            }
        }.start()
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun buildItems(): MutableList<PickerItem> {
        val list = mutableListOf<PickerItem>()
        list += PickerItem.Header(
            if (selectedOrder.isEmpty()) "Your shortcuts — tap an app below to add one"
            else "Your shortcuts (drag ☰ to reorder)"
        )
        selectedOrder.forEach { pkg ->
            apps.find { it.packageName == pkg }?.let { list += PickerItem.Selected(it) }
        }
        val query = currentQuery.trim().lowercase()
        val filtered = if (query.isBlank()) apps else apps.filter { it.label.lowercase().contains(query) }
        list += PickerItem.Header("All apps (${filtered.size})")
        filtered.forEach { list += PickerItem.Available(it) }
        return list
    }

    private fun refreshList() {
        adapter.submit(buildItems())
        loadingText.text = "${apps.size} apps found · ${selectedOrder.size}/${PrefsHelper.MAX_APPS} selected"
    }

    private fun persistAndNotifyService() {
        PrefsHelper.setSelectedApps(this, selectedOrder)
        sendBroadcast(Intent(FloatingToolbarService.ACTION_APPS_UPDATED).setPackage(packageName))
    }

    private fun removeSelected(pkg: String) {
        selectedOrder.remove(pkg)
        persistAndNotifyService()
        refreshList()
    }

    private fun onToggle(pkg: String, isChecked: Boolean, checkBox: CheckBox) {
        if (isChecked) {
            if (selectedOrder.size >= PrefsHelper.MAX_APPS) {
                checkBox.isChecked = false
                Toast.makeText(this, "You can pick up to ${PrefsHelper.MAX_APPS} apps", Toast.LENGTH_SHORT).show()
                return
            }
            if (pkg !in selectedOrder) selectedOrder.add(pkg)
        } else {
            selectedOrder.remove(pkg)
        }
        persistAndNotifyService()
        refreshList()
    }

    // ---------- List item model ----------

    private sealed class PickerItem {
        data class Header(val text: String) : PickerItem()
        data class Selected(val entry: AppEntry) : PickerItem()
        data class Available(val entry: AppEntry) : PickerItem()
    }

    // ---------- View holders ----------

    private inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.headerText)
        fun bind(value: String) {
            text.text = value
        }
    }

    private inner class SelectedVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val name: TextView = view.findViewById(R.id.appName)
        private val remove: ImageView = view.findViewById(R.id.btnRemove)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)

        fun bind(entry: AppEntry) {
            icon.setImageDrawable(entry.icon)
            name.text = entry.label
            remove.setOnClickListener { removeSelected(entry.packageName) }
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(this)
                }
                false
            }
        }
    }

    private inner class AvailableVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val name: TextView = view.findViewById(R.id.appName)
        private val check: CheckBox = view.findViewById(R.id.appCheck)

        fun bind(entry: AppEntry) {
            icon.setImageDrawable(entry.icon)
            name.text = entry.label
            check.setOnCheckedChangeListener(null)
            check.isChecked = entry.packageName in selectedOrder
            check.setOnCheckedChangeListener { _, isChecked -> onToggle(entry.packageName, isChecked, check) }
            itemView.setOnClickListener { check.toggle() }
        }
    }

    // ---------- Adapter ----------

    private inner class PickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val items: MutableList<PickerItem> = mutableListOf()

        fun submit(newItems: List<PickerItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun moveItem(from: Int, to: Int) {
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }

        fun currentSelectedOrder(): List<String> =
            items.filterIsInstance<PickerItem.Selected>().map { it.entry.packageName }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is PickerItem.Header -> TYPE_HEADER
            is PickerItem.Selected -> TYPE_SELECTED
            is PickerItem.Available -> TYPE_AVAILABLE
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_app_header, parent, false))
                TYPE_SELECTED -> SelectedVH(inflater.inflate(R.layout.item_selected_app, parent, false))
                else -> AvailableVH(inflater.inflate(R.layout.item_app_picker, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is PickerItem.Header -> (holder as HeaderVH).bind(item.text)
                is PickerItem.Selected -> (holder as SelectedVH).bind(item.entry)
                is PickerItem.Available -> (holder as AvailableVH).bind(item.entry)
            }
        }
    }

    // ---------- Drag-to-reorder (restricted to the "Selected" section) ----------

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(0, 0) {

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return if (viewHolder is SelectedVH) {
                makeMovementFlags(ItemTouchHelper.ACTION_STATE_DRAG, ItemTouchHelper.UP or ItemTouchHelper.DOWN)
            } else {
                0
            }
        }

        override fun isLongPressDragEnabled() = false

        override fun onMove(
            recyclerView: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            if (source !is SelectedVH || target !is SelectedVH) return false
            adapter.moveItem(source.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // No swipe actions - only drag is enabled.
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            selectedOrder = adapter.currentSelectedOrder().toMutableList()
            persistAndNotifyService()
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SELECTED = 1
        private const val TYPE_AVAILABLE = 2
    }
}
