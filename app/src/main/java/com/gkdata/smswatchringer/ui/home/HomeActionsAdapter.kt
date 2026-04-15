package com.gkdata.smswatchringer.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gkdata.smswatchringer.R
import com.gkdata.smswatchringer.databinding.ItemHomeActionBinding
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class HomeActionsAdapter(
    private val onClick: (HomeActionId) -> Unit,
) : RecyclerView.Adapter<HomeActionViewHolder>() {

    private var items: List<HomeActionItem> = emptyList()

    fun submit(next: List<HomeActionItem>) {
        items = next
        notifyDataSetChanged()
    }

    fun spanAt(position: Int): Int = items.getOrNull(position)?.span ?: 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeActionViewHolder {
        val binding = ItemHomeActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HomeActionViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: HomeActionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

class HomeActionViewHolder(
    private val binding: ItemHomeActionBinding,
    private val onClick: (HomeActionId) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: HomeActionItem) {
        val ctx = binding.root.context

        binding.icon.setImageResource(item.iconRes)
        binding.chevron.setImageResource(R.drawable.ic_chevron_right)

        binding.titleText.text = item.title
        binding.descText.text = item.description

        binding.chevron.visibility = if (item.showChevron) View.VISIBLE else View.GONE

        applyTone(ctx, item)
        applyEnabled(item.enabled)

        binding.card.setOnClickListener {
            if (item.enabled) onClick(item.id)
        }
    }

    private fun applyTone(context: Context, item: HomeActionItem) {
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)

        when (item.tone) {
            HomeActionTone.NORMAL -> {
                binding.card.setCardBackgroundColor(colorSurface)
                binding.card.strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
                binding.icon.setColorFilter(colorPrimary)
                binding.chevron.setColorFilter(colorOnSurface)
                binding.chevron.alpha = 0.45f
                binding.titleText.setTextColor(colorOnSurface)
                binding.descText.setTextColor(colorOnSurface)
                binding.descText.alpha = 0.6f
            }

            HomeActionTone.DANGER -> {
                binding.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.danger_bg))
                binding.card.strokeColor = ContextCompat.getColor(context, R.color.danger_fg)
                binding.icon.setColorFilter(ContextCompat.getColor(context, R.color.danger_fg))
                binding.chevron.setColorFilter(ContextCompat.getColor(context, R.color.danger_fg))
                binding.chevron.alpha = 0.7f
                binding.titleText.setTextColor(ContextCompat.getColor(context, R.color.danger_fg))
                binding.descText.setTextColor(ContextCompat.getColor(context, R.color.danger_fg))
                binding.descText.alpha = 0.75f
                binding.content.minimumHeight = context.dpToPx(120)
                binding.titleText.textSize = 18f
                binding.descText.textSize = 13f
            }
        }
    }

    private fun applyEnabled(enabled: Boolean) {
        binding.card.isEnabled = enabled
        binding.card.isClickable = enabled
        binding.card.alpha = if (enabled) 1f else 0.55f
    }
}

private fun Context.dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()
