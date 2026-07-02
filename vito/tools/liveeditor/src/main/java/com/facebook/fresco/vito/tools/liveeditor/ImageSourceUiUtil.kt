/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.tools.liveeditor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ImageSourceUiUtil(private val context: Context) {

  private fun createView(): ViewGroup {
    return LinearLayout(context).apply { addView(createTextView()) }
  }

  private fun createTextView(): TextView {
    return TextView(context).apply {
      id = android.R.id.text1
      textSize = 12.spToPx(context)
    }
  }

  fun createSourceDialog(source: List<Pair<String, String>>): AlertDialog? {

    if (source.isEmpty()) {
      Toast.makeText(context, "Source is Empty", Toast.LENGTH_LONG).show()
      return null
    }

    val items = source.map { pair ->
      val spannable = SpannableString("${pair.first} \n${pair.second}")
      spannable.apply {
        setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            pair.first.length,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE,
        )
        setSpan(
            StyleSpan(Typeface.ITALIC),
            pair.first.length,
            spannable.length,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE,
        )
      }
    }

    val dialogBuilder = AlertDialog.Builder(context)
    val layout = createView()
    var dialog: AlertDialog? =
        dialogBuilder
            .setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, items), null)
            .create()
    dialog?.setView(layout)
    dialog?.setOnDismissListener {
      items.forEach { item ->
        val spans = item.getSpans(0, item.length, StyleSpan::class.java)
        for (span in spans) {
          item.removeSpan(span)
        }
      }
      dialog = null
    }

    return dialog
  }

  fun createImageInfoView(info: Pair<String, String>, parent: ViewGroup): View {
    val label = "${info.first} $COPY_GLYPH"
    val spannable = SpannableString("$label\n${info.second}")
    spannable.apply {
      setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
      setSpan(
          StyleSpan(Typeface.ITALIC),
          label.length,
          spannable.length,
          Spannable.SPAN_INCLUSIVE_INCLUSIVE,
      )
    }

    val textView: TextView =
        LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
            as TextView
    return textView.apply {
      text = spannable
      setOnClickListener { copyToClipboard(context, info.first, info.second) }
    }
  }

  private fun Int.spToPx(context: Context): Float =
      TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_SP,
          this.toFloat(),
          context.resources.displayMetrics,
      )

  companion object {
    // 📋 clipboard glyph appended to a row's label as a copy affordance.
    internal const val COPY_GLYPH = "📋"

    /**
     * Copies [value] to the system clipboard and confirms with a Toast. Blank values are skipped so
     * a "Copied" toast never appears with nothing on the clipboard.
     */
    internal fun copyToClipboard(context: Context, label: String, value: String) {
      if (value.isBlank()) {
        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
        return
      }
      val clipboard =
          context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
      clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
      Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
    }
  }
}
