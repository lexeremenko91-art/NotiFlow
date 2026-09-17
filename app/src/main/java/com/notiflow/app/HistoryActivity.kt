package com.lexlebeau.notiflow

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val time: Long
)

class HistoryAdapter(
    private val items: MutableList<HistoryItem>
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var filtered = items.toMutableList()
    private val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    private var currentQuery = ""
    private var currentApp = ""

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appText: TextView = view.findViewById(R.id.appText)
        val titleText: TextView = view.findViewById(R.id.titleText)
        val bodyText: TextView = view.findViewById(R.id.bodyText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filtered[position]
        holder.appText.text = "${item.appName} • ${sdf.format(Date(item.time))}"
        holder.titleText.text = item.title
        holder.bodyText.text = item.text
    }

    override fun getItemCount() = filtered.size

    fun applyFilters(query: String = currentQuery, appName: String = currentApp) {
        currentQuery = query
        currentApp = appName
        filtered = items.filter { item ->
            val matchesApp = appName.isEmpty() || item.appName == appName
            val matchesQuery = query.isEmpty() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.text.contains(query, ignoreCase = true) ||
                    item.appName.contains(query, ignoreCase = true)
            matchesApp && matchesQuery
        }.toMutableList()
        notifyDataSetChanged()
    }

    fun removeAt(position: Int): HistoryItem {
        val item = filtered.removeAt(position)
        items.remove(item)
        notifyItemRemoved(position)
        return item
    }

    fun undoDelete(item: HistoryItem, position: Int) {
        filtered.add(position, item)
        items.add(item)
        notifyItemInserted(position)
    }

    fun getFiltered() = filtered
    fun getItems() = items
    fun getAppNames() = items.map { it.appName }.distinct().sorted()
}

class HistoryActivity : AppCompatActivity() {

    private lateinit var historyPrefs: SharedPreferences
    private lateinit var adapter: HistoryAdapter
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var chipGroup: ChipGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyPrefs = getSharedPreferences("notiflow_history", MODE_PRIVATE)

        emptyText = findViewById(R.id.emptyText)
        recyclerView = findViewById(R.id.recyclerView)
        chipGroup = findViewById(R.id.chipGroup)

        val items = loadItems()
        adapter = HistoryAdapter(items)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        setupChips()

        // Свайп для удаления
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            private val background = ColorDrawable(Color.parseColor("#C62828"))
            private val paint = Paint().apply {
                color = Color.WHITE
                textSize = 48f
                textAlign = Paint.Align.CENTER
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val deletedItem = adapter.removeAt(position)
                updateEmptyState()
                var undone = false

                com.google.android.material.snackbar.Snackbar.make(
                    recyclerView,
                    getString(R.string.history_deleted),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
                    .setAction(getString(R.string.btn_undo)) {
                        undone = true
                        adapter.undoDelete(deletedItem, position)
                        updateEmptyState()
                    }
                    .addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
                        override fun onDismissed(snackbar: com.google.android.material.snackbar.Snackbar?, event: Int) {
                            if (!undone) historyPrefs.edit().remove(deletedItem.key).apply()
                        }
                    })
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                background.setBounds(itemView.left, itemView.top, itemView.right, itemView.bottom)
                background.draw(c)
                val textY = itemView.top + (itemView.height / 2f) + (paint.textSize / 3f)
                if (dX > 0) {
                    c.drawText("🗑", itemView.left + 80f, textY, paint)
                } else {
                    c.drawText("🗑", itemView.right - 80f, textY, paint)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)

        // Поиск
        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.applyFilters(query = s?.toString() ?: "")
                updateEmptyState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Очистить историю
        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_clear_confirm_title))
                .setMessage(getString(R.string.history_clear_confirm_message))
                .setPositiveButton(getString(R.string.btn_clear_history)) { _, _ ->
                    historyPrefs.edit().clear().apply()
                    adapter.getItems().clear()
                    adapter.applyFilters()
                    setupChips()
                    updateEmptyState()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        updateEmptyState()
    }

    private fun setupChips() {
        chipGroup.removeAllViews()

        val allChip = Chip(this)
        allChip.text = getString(R.string.history_filter_all)
        allChip.isCheckable = true
        allChip.isChecked = true
        allChip.setChipBackgroundColorResource(android.R.color.transparent)
        allChip.setTextColor(getColor(android.R.color.white))
        chipGroup.addView(allChip)

        adapter.getAppNames().forEach { appName ->
            val chip = Chip(this)
            chip.text = appName
            chip.isCheckable = true
            chip.setChipBackgroundColorResource(android.R.color.transparent)
            chip.setTextColor(getColor(android.R.color.white))
            chipGroup.addView(chip)
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val checkedChip = group.findViewById<Chip>(checkedIds[0])
            val appName = if (checkedChip.text == getString(R.string.history_filter_all)) "" else checkedChip.text.toString()
            adapter.applyFilters(appName = appName)
            updateEmptyState()
        }
    }

    private fun loadItems(): MutableList<HistoryItem> {
        return historyPrefs.all.entries
            .mapNotNull { (key, v) ->
                if (v is String) {
                    val parts = v.split("|")
                    if (parts.size >= 4) {
                        val packageName = parts[0]
                        val appName = if (parts.size >= 5) parts[4] else {
                            try {
                                val info = packageManager.getApplicationInfo(packageName, 0)
                                packageManager.getApplicationLabel(info).toString()
                            } catch (e: Exception) {
                                packageName.substringAfterLast(".")
                            }
                        }
                        HistoryItem(
                            key = key,
                            packageName = packageName,
                            appName = appName,
                            title = parts[1],
                            text = parts[2],
                            time = parts[3].toLongOrNull() ?: 0L
                        )
                    } else null
                } else null
            }
            .sortedByDescending { it.time }
            .toMutableList()
    }

    private fun updateEmptyState() {
        val isEmpty = adapter.getFiltered().isEmpty()
        emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
}