package com.example.tvlimit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlimit.R

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable,
    var isSelected: Boolean
)

class AppAdapter(
    private val apps: List<AppItem>,
    private val onAppToggled: (AppItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    /** 只在第一次绑定卡片时抢一次焦点，避免遥控器进来时焦点落在「选好了」上。 */
    private var focusGiven = false

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.cardApp)
        private val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = view.findViewById(R.id.tvAppName)
        private val ivCheck: ImageView = view.findViewById(R.id.ivCheck)

        init {
            TvFocus.attach(card)
        }

        private fun bindCheck(item: AppItem) {
            val ctx = itemView.context
            if (item.isSelected) {
                ivCheck.setImageResource(R.drawable.ic_check)
                ivCheck.setColorFilter(ContextCompat.getColor(ctx, R.color.sun))
            } else {
                ivCheck.setImageResource(R.drawable.ic_circle)
                ivCheck.setColorFilter(ContextCompat.getColor(ctx, R.color.text_faint))
            }
        }

        fun bind(app: AppItem) {
            tvName.text = app.name
            ivIcon.setImageDrawable(app.icon)
            bindCheck(app)
            card.setOnClickListener {
                app.isSelected = !app.isSelected
                bindCheck(app)
                onAppToggled(app, app.isSelected)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_selection, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
        if (!focusGiven) {
            focusGiven = true
            val target = holder.itemView
            target.post { TvFocus.focus(target) }
        }
    }

    override fun getItemCount() = apps.size
}
