package com.noti.sender.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.noti.sender.R

/** Settings tab: a menu of focused sub-screens. */
class SettingsFragment : Fragment(R.layout.cmp_fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.settings_container)
        val inflater = LayoutInflater.from(requireContext())

        fun addRow(titleRes: Int, subRes: Int, iconRes: Int, cls: Class<*>) {
            val row = inflater.inflate(R.layout.cmp_item_settings_row, container, false)
            row.findViewById<TextView>(R.id.row_title).setText(titleRes)
            row.findViewById<TextView>(R.id.row_sub).setText(subRes)
            row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
            row.setOnClickListener { startActivity(Intent(requireContext(), cls)) }
            container.addView(row)
        }

        // Order: Pairing, Webhook, Appearance first; SIM names; Diagnostics last.
        addRow(R.string.menu_pairing_title, R.string.menu_pairing_sub, R.drawable.ic_qr_code, PairingActivity::class.java)
        addRow(R.string.menu_webhook_title, R.string.menu_webhook_sub, R.drawable.ic_connection, WebhookActivity::class.java)
        addRow(R.string.menu_appearance_title, R.string.menu_appearance_sub, R.drawable.ic_palette, AppearanceActivity::class.java)
        addRow(R.string.menu_sim_names_title, R.string.menu_sim_names_sub, R.drawable.ic_sim, SimNamesActivity::class.java)
        addRow(R.string.menu_diag_title, R.string.menu_diag_sub, R.drawable.ic_status_warning, DiagnosticsActivity::class.java)
    }
}
