package com.noti.logger.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings tab: a menu that opens focused sub-screens, plus a Purge action. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.settings_container)
        val inflater = LayoutInflater.from(requireContext())

        fun addRow(titleRes: Int, subRes: Int, onClick: () -> Unit) {
            val row = inflater.inflate(R.layout.item_settings_row, container, false)
            row.findViewById<TextView>(R.id.row_title).setText(titleRes)
            row.findViewById<TextView>(R.id.row_sub).setText(subRes)
            row.setOnClickListener { onClick() }
            container.addView(row)
        }

        fun open(cls: Class<*>) = startActivity(Intent(requireContext(), cls))

        addRow(R.string.menu_connection_title, R.string.menu_connection_sub) { open(ConnectionActivity::class.java) }
        addRow(R.string.menu_sync_title, R.string.menu_sync_sub) { open(SyncActivity::class.java) }
        addRow(R.string.menu_apps_title, R.string.menu_apps_sub) { openApps() }
        addRow(R.string.menu_privacy_title, R.string.menu_privacy_sub) { open(PrivacyActivity::class.java) }
        addRow(R.string.menu_appearance_title, R.string.menu_appearance_sub) { open(AppearanceActivity::class.java) }
        addRow(R.string.menu_purge_title, R.string.menu_purge_sub) { confirmPurge() }
    }

    private val appPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringArrayExtra(AppPickerActivity.EXTRA_SELECTED)?.let {
                Settings.get(requireContext()).includedPackages = it.toSet()
                Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openApps() {
        val current = Settings.get(requireContext()).includedPackages.toTypedArray()
        appPicker.launch(
            Intent(requireContext(), AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_SELECTED, current)
        )
    }

    private fun confirmPurge() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.purge_confirm_title)
            .setMessage(R.string.purge_confirm_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.menu_purge_title) { _, _ -> purge() }
            .show()
    }

    private fun purge() {
        val ctx = requireContext().applicationContext
        val retDays = Settings.get(ctx).retentionDays
        val cutoff = System.currentTimeMillis() - retDays * 86_400_000L
        val dao = NotiDatabase.get(ctx).notificationDao()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val deleted = dao.purgeUploadedOlderThan(cutoff)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, getString(R.string.purge_result, deleted), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
