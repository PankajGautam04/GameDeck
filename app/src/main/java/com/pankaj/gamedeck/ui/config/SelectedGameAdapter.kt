package com.pankaj.gamedeck.ui.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.pankaj.gamedeck.R
import com.pankaj.gamedeck.data.model.GameEntry
import java.io.File

class SelectedGameAdapter(
    private val onPickCover: (Int) -> Unit,
    private val onPickGif: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onEditGoal: (Int) -> Unit
) : RecyclerView.Adapter<SelectedGameAdapter.ViewHolder>() {

    private var games: List<GameEntry> = emptyList()

    fun submitList(list: List<GameEntry>) { games = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_selected_game, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(games[position], position)
    override fun getItemCount() = games.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgCover: ImageView = itemView.findViewById(R.id.img_game_cover)
        private val tvLabel: TextView = itemView.findViewById(R.id.tv_game_label)
        private val tvGifBadge: TextView = itemView.findViewById(R.id.tv_gif_badge)
        private val btnPickCover: ImageButton = itemView.findViewById(R.id.btn_pick_cover)
        private val btnPickGif: ImageButton = itemView.findViewById(R.id.btn_pick_gif)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btn_remove_game)

        fun bind(game: GameEntry, position: Int) {
            val customGoal = game.customPlayText
            tvLabel.text = if (customGoal.isNullOrEmpty()) game.appLabel else "${game.appLabel}\n🏆 Goal: $customGoal"
            tvGifBadge.visibility = if (game.gifPath != null && game.isGif) View.VISIBLE else View.GONE

            if (game.imagePath != null && File(game.imagePath).exists()) {
                Glide.with(itemView.context)
                    .load(File(game.imagePath))
                    .transform(CenterCrop(), RoundedCorners(32))
                    .placeholder(R.drawable.ic_placeholder)
                    .into(imgCover)
            } else {
                try {
                    imgCover.setImageDrawable(
                        itemView.context.packageManager.getApplicationIcon(game.packageName)
                    )
                } catch (e: Exception) {
                    imgCover.setImageResource(R.drawable.ic_placeholder)
                }
            }


            btnPickCover.setOnClickListener { onPickCover(position) }
            btnPickGif.setOnClickListener { onPickGif(position) }
            btnRemove.setOnClickListener { onRemove(position) }
            itemView.setOnClickListener { onEditGoal(position) }
        }
    }
}
