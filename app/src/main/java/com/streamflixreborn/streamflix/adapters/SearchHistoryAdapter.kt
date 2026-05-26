package com.streamflixreborn.streamflix.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R

class SearchHistoryAdapter(
    private val onItemClick: (String) -> Unit,
    private val onRemoveClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun submitList(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val query = items[position]
        holder.tvQuery.text = query
        holder.itemView.setOnClickListener { onItemClick(query) }
        holder.btnRemove.setOnClickListener { onRemoveClick(query) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvQuery: TextView = view.findViewById(R.id.tv_history_query)
        val btnRemove: ImageView = view.findViewById(R.id.btn_history_remove)
    }
}
