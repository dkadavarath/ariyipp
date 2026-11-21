package com.noti.sender.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.noti.sender.R

/**
 * Fills a vertical container with `ds_kv_row`s - label on the left, value on the right, a hairline
 * between rows - in place of a block of "Label: value" lines.
 */
object KeyValueList {

    fun fill(container: ViewGroup, rows: List<Pair<CharSequence, CharSequence>>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        val hairline = MaterialColors.getColor(container, R.attr.notiHairline)
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) {
                container.addView(View(container.context).apply { setBackgroundColor(hairline) },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            val row = inflater.inflate(R.layout.ds_kv_row, container, false)
            row.findViewById<TextView>(R.id.kv_label).text = label
            row.findViewById<TextView>(R.id.kv_value).text = value
            container.addView(row)
        }
    }
}
