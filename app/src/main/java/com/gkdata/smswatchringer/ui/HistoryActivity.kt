package com.gkdata.smswatchringer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gkdata.smswatchringer.R
import com.gkdata.smswatchringer.databinding.ActivityHistoryBinding
import com.gkdata.smswatchringer.databinding.ItemMatchRecordBinding
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.sms.model.SmsSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var prefs: Prefs
    private val adapter = MatchRecordAdapter(::copyToClipboard)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            R.id.action_clear_history -> {
                confirmClear()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun render() {
        val records = prefs.matchHistory()
        adapter.submit(records)

        binding.emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_confirm_clear_title)
            .setMessage(R.string.history_confirm_clear_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                prefs.clearMatchHistory()
                render()
            }
            .show()
    }

    private fun copyToClipboard(record: Prefs.MatchRecord) {
        val clipboard = getSystemService(ClipboardManager::class.java)

        val keywordLine = record.matchedKeywords.takeIf { it.isNotEmpty() }?.let { keywords ->
            val preview = keywords.joinToString(separator = "，")
            "关键词：$preview"
        }

        val regexLine = record.matchedRegex.takeIf { it.isNotEmpty() }?.let { rules ->
            val preview = rules.joinToString(separator = "，")
            "正则：$preview"
        }

        val text = buildString {
            append(formatTime(record.receivedAtMillis))
            append('\n')
            append("来源：")
            append(if (record.source == SmsSource.TEST) "测试" else "短信")
            append(if (record.alerted) "" else "（已抑制）")
            append('\n')
            if (!keywordLine.isNullOrBlank()) {
                append(keywordLine)
                append('\n')
            }
            if (!regexLine.isNullOrBlank()) {
                append(regexLine)
                append('\n')
            }
            append(record.from)
            append('\n')
            append(record.body)
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("sms_match", text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(millis: Long): String =
        runCatching {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrElse { "-" }
}

private class MatchRecordAdapter(
    private val onClick: (Prefs.MatchRecord) -> Unit,
) : RecyclerView.Adapter<MatchRecordViewHolder>() {

    private var items: List<Prefs.MatchRecord> = emptyList()

    fun submit(next: List<Prefs.MatchRecord>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MatchRecordViewHolder {
        val binding = ItemMatchRecordBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
        return MatchRecordViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: MatchRecordViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

private class MatchRecordViewHolder(
    private val binding: ItemMatchRecordBinding,
    private val onClick: (Prefs.MatchRecord) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(record: Prefs.MatchRecord) {
        val sourceLabel = if (record.source == SmsSource.TEST) "测试" else "短信"
        val suppressedLabel = if (record.alerted) "" else " · 已抑制"
        val title = "${formatTime(record.receivedAtMillis)} · $sourceLabel · ${record.from}$suppressedLabel"
        binding.titleText.text = title

        val keywordText = record.matchedKeywords.takeIf { it.isNotEmpty() }?.let { keywords ->
            val preview = keywords.joinToString(separator = "，")
            "关键词：$preview"
        }
        val regexText = record.matchedRegex.takeIf { it.isNotEmpty() }?.let { rules ->
            val preview = rules.joinToString(separator = "，") { it.take(24) + if (it.length > 24) "…" else "" }
            "正则：$preview"
        }

        val matchText = listOfNotNull(keywordText, regexText).joinToString(separator = "\n")
        binding.keywordText.visibility = if (matchText.isBlank()) View.GONE else View.VISIBLE
        binding.keywordText.text = matchText

        binding.bodyText.text = record.body.trim()

        binding.root.setOnClickListener { onClick(record) }
    }

    private fun formatTime(millis: Long): String =
        runCatching {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrElse { "-" }
}
