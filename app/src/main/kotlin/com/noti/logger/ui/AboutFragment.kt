package com.noti.logger.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.shared.Role

/** About tab: app info, changelog, the device role (switchable), and a Help button. */
class AboutFragment : Fragment(R.layout.fragment_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        view.findViewById<TextView>(R.id.tv_version).text = getString(R.string.about_version, version)
        view.findViewById<TextView>(R.id.tv_device_id).text =
            getString(R.string.about_device_id, Settings.get(requireContext()).deviceId)

        bindRole(view)

        view.findViewById<View>(R.id.btn_help).setOnClickListener {
            startActivity(Intent(requireContext(), HelpActivity::class.java))
        }
    }

    private fun bindRole(view: View) {
        val role = Settings.get(requireContext()).role ?: Role.MAIN
        val main = role == Role.MAIN
        view.findViewById<TextView>(R.id.tv_role).setText(
            if (main) R.string.about_role_main else R.string.about_role_companion
        )
        view.findViewById<Button>(R.id.btn_switch_role).apply {
            setText(if (main) R.string.about_switch_to_companion else R.string.about_switch_to_main)
            setOnClickListener { confirmSwitch(if (main) Role.COMPANION else Role.MAIN) }
        }
    }

    private fun confirmSwitch(target: Role) {
        val targetLabel = getString(
            if (target == Role.COMPANION) R.string.about_role_companion_name else R.string.about_role_main_name
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_switch_title)
            .setMessage(getString(R.string.about_switch_msg, targetLabel))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.about_switch_confirm) { _, _ ->
                Settings.get(requireContext()).role = target
                // Relaunch the app fresh so it rebuilds for the new role; keys/settings are kept.
                startActivity(
                    Intent(requireContext(), MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                requireActivity().finish()
            }
            .show()
    }
}
