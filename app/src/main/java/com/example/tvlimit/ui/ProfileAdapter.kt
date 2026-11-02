package com.example.tvlimit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlimit.R

class ProfileAdapter(private val onItemClick: (ProfileWithUsage) -> Unit) :
    ListAdapter<ProfileWithUsage, ProfileAdapter.ProfileViewHolder>(ProfileComparator()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.cardProfile)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvProfileName)
        private val tvType: TextView = itemView.findViewById(R.id.tvProfileType)
        private val tvSub: TextView = itemView.findViewById(R.id.tvProfileSub)

        init {
            TvFocus.attach(card, 1.04f)
        }

        fun bind(item: ProfileWithUsage) {
            val ctx = itemView.context
            val p = item.profile
            tvName.text = p.name

            card.setOnClickListener { onItemClick(item) }

            if (!p.isRestricted) {
                ivAvatar.setImageResource(R.drawable.ic_avatar_adult)
                tvType.text = "家长模式"
                tvType.setTextColor(ContextCompat.getColor(ctx, R.color.sky))
                tvSub.text = "不限制观看时长"
            } else {
                ivAvatar.setImageResource(R.drawable.ic_avatar_kid)

                val dailyRemain = if (p.dailyLimitMinutes > 0)
                    (p.dailyLimitMinutes - item.dailyUsage).coerceAtLeast(0) else -1
                val sessionRemain = if (p.sessionLimitMinutes > 0)
                    (p.sessionLimitMinutes - item.sessionUsage).coerceAtLeast(0) else -1
                val now = when {
                    dailyRemain >= 0 && sessionRemain >= 0 -> minOf(dailyRemain, sessionRemain)
                    dailyRemain >= 0 -> dailyRemain
                    else -> sessionRemain
                }

                tvType.text = when {
                    now < 0 -> "不限制观看时长"
                    now == 0 -> "现在要休息一下"
                    else -> "现在还能看 $now 分钟"
                }
                tvType.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (now in 0..5) R.color.coral else R.color.sun
                    )
                )

                tvSub.text = if (dailyRemain >= 0) "今天还剩 $dailyRemain 分钟" else "今天不限时长"
            }
        }
    }

    class ProfileComparator : DiffUtil.ItemCallback<ProfileWithUsage>() {
        override fun areItemsTheSame(oldItem: ProfileWithUsage, newItem: ProfileWithUsage): Boolean {
            return oldItem.profile.id == newItem.profile.id
        }

        override fun areContentsTheSame(oldItem: ProfileWithUsage, newItem: ProfileWithUsage): Boolean {
            return oldItem == newItem
        }
    }
}
