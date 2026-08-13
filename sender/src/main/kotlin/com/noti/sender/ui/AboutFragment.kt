package com.noti.sender.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.noti.sender.R
import com.noti.sender.config.SenderSettings

/** About tab: app info, changelog, and a Help button. */
class AboutFragment : Fragment(R.layout.cmp_fragment_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        view.findViewById<TextView>(R.id.tv_version).text = getString(R.string.about_version, version)
        view.findViewById<TextView>(R.id.tv_device_id).text =
            getString(R.string.about_device_id, SenderSettings.get(requireContext()).deviceId)

        view.findViewById<View>(R.id.btn_help).setOnClickListener {
            startActivity(Intent(requireContext(), HelpActivity::class.java))
        }
    }
}
