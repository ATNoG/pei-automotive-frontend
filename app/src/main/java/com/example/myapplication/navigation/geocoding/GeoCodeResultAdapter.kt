package com.example.myapplication.navigation.geocoding

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R

/**
 * Adapter for displaying geocoding search results in a RecyclerView
 */
class GeoCodeResultAdapter(
    private val results: MutableList<GeoCodeResult> = mutableListOf(),
    private val onResultClick: (GeoCodeResult) -> Unit
) : RecyclerView.Adapter<GeoCodeResultAdapter.ResultViewHolder>() {

    inner class ResultViewHolder(private val resultItem: LinearLayout) : RecyclerView.ViewHolder(resultItem) {
        fun bind(result: GeoCodeResult) {
            val titleView = resultItem.findViewById<TextView>(R.id.txtResultTitle)
            val addressView = resultItem.findViewById<TextView>(R.id.txtResultAddress)
            
            titleView?.text = result.name
            addressView?.text = result.address
            
            resultItem.setOnClickListener {
                onResultClick(result)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val resultItem = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_geocode_result, parent, false) as LinearLayout
        return ResultViewHolder(resultItem)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount(): Int = results.size

    fun updateResults(newResults: List<GeoCodeResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }
}
