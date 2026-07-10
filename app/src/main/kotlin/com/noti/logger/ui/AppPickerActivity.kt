package com.noti.logger.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.noti.logger.R
import com.noti.logger.util.AppInfo
import com.noti.logger.util.InstalledApps
import kotlinx.coroutines.launch

/**
 * Multi-select picker over launchable apps. Seeded with the current selection via
 * [EXTRA_SELECTED]; returns the chosen package names in the same extra on OK.
 */
class AppPickerActivity : AppCompatActivity() {

    private val selected = LinkedHashSet<String>()
    private var allApps: List<AppInfo> = emptyList()
    private lateinit var adapter: AppAdapter
    private lateinit var countView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val root = findViewById<View>(R.id.picker_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        intent.getStringArrayExtra(EXTRA_SELECTED)?.let { selected.addAll(it) }

        countView = findViewById(R.id.txt_selected_count)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() } // cancel = no result
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> { allApps.forEach { selected.add(it.packageName) }; refresh(); true }
                R.id.action_clear -> { selected.clear(); refresh(); true }
                else -> false
            }
        }

        adapter = AppAdapter()
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            adapter = this@AppPickerActivity.adapter
        }

        findViewById<TextInputEditText>(R.id.et_search).doAfterTextChanged { text ->
            adapter.submit(InstalledApps.filter(allApps, text?.toString().orEmpty()))
        }

        findViewById<MaterialButton>(R.id.btn_done).setOnClickListener {
            setResult(
                Activity.RESULT_OK,
                intent.putExtra(EXTRA_SELECTED, selected.toTypedArray())
            )
            finish()
        }

        updateCount()
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            allApps = InstalledApps.loadLaunchableApps(this@AppPickerActivity)
            adapter.submit(allApps)
        }
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        countView.text = getString(R.string.picker_selected_count, selected.size)
    }

    // ---- Adapter ----

    private inner class AppAdapter : RecyclerView.Adapter<AppVH>() {
        private val items = ArrayList<AppInfo>()

        fun submit(list: List<AppInfo>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
            return AppVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: AppVH, position: Int) {
            val app = items[position]
            holder.label.text = app.label
            holder.pkg.text = app.packageName
            holder.icon.setImageDrawable(app.icon)
            holder.check.isChecked = app.packageName in selected
            holder.itemView.setOnClickListener {
                if (!selected.add(app.packageName)) selected.remove(app.packageName)
                holder.check.isChecked = app.packageName in selected
                updateCount()
            }
        }
    }

    private inner class AppVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.img_icon)
        val label: TextView = v.findViewById(R.id.txt_label)
        val pkg: TextView = v.findViewById(R.id.txt_package)
        val check: MaterialCheckBox = v.findViewById(R.id.check)
    }

    companion object {
        const val EXTRA_SELECTED = "selected_packages"
    }
}
