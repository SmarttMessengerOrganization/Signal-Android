package com.smarttmessenger.communicationwindow.ui.banner

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.smarttmessenger.communicationwindow.model.WindowExpectations
import com.smarttmessenger.communicationwindow.network.WindowMetadataResponse
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.formatHours
import java.time.LocalTime

class SmarttWindowBannerView(context: Context) {

  val root: View = LayoutInflater.from(context)
    .inflate(R.layout.smartt_window_banner, null, false)

  private val title: TextView = root.findViewById(R.id.smartt_banner_title)
  private val toggle: ImageView = root.findViewById(R.id.smartt_banner_toggle)
  private val details: LinearLayout = root.findViewById(R.id.smartt_banner_details)
  private val checkFrequency: TextView = root.findViewById(R.id.smartt_banner_check_frequency)
  private val replyTime: TextView = root.findViewById(R.id.smartt_banner_reply_time)
  private val personalNote: TextView = root.findViewById(R.id.smartt_banner_personal_note)

  private var expanded = true

  init {
    toggle.setOnClickListener { toggleExpanded() }
    root.isVisible = false
  }

  fun bind(recipientName: String, metadata: WindowMetadataResponse) {
    if (!metadata.windowActive) {
      root.isVisible = false
      return
    }

    val context = root.context
    title.text = context.getString(
      R.string.CommunicationWindow__banner_closed,
      recipientName,
      formatMinutes(metadata.windowStartMinutes),
      formatMinutes(metadata.windowEndMinutes)
    )

    // One view instance is reused for every recipient, so each row's visibility must be set on every
    // bind. Only ever showing rows would leak the previous contact's expectations onto this banner.
    val exp = metadata.expectations
    checkFrequency.setTextOrHide(
      exp?.checkFrequency?.let {
        val label = WindowExpectations.CheckFrequency.fromServerValue(it)?.let { freq -> context.getString(freq.label) } ?: unknownValue(it)
        context.getString(R.string.CommunicationWindow__banner_check_frequency, label)
      }
    )
    replyTime.setTextOrHide(
      exp?.usualReplyTime?.let {
        val label = WindowExpectations.UsualReplyTime.fromServerValue(it)?.let { time -> context.getString(time.label) } ?: unknownValue(it)
        context.getString(R.string.CommunicationWindow__banner_reply_time, label)
      }
    )
    personalNote.setTextOrHide(exp?.personalNote?.let { context.getString(R.string.CommunicationWindow__banner_personal_note, it) })

    root.isVisible = true
    setExpanded(true)
  }

  fun hide() {
    root.isVisible = false
  }

  private fun toggleExpanded() = setExpanded(!expanded)

  private fun setExpanded(expand: Boolean) {
    expanded = expand
    details.isVisible = expand
    toggle.rotation = if (expand) 0f else 180f
  }

  private fun TextView.setTextOrHide(value: String?) {
    text = value ?: ""
    isVisible = value != null
  }

  /** Minutes since midnight, in the phone's 12- or 24-hour format. */
  private fun formatMinutes(minutes: Int): String =
    LocalTime.of(minutes / 60 % 24, minutes % 60).formatHours(root.context)

  /** A value newer than this build: shown as the server sent it, as it was before it had a label. */
  private fun unknownValue(value: String): String = value.lowercase().replace('_', ' ')
}
