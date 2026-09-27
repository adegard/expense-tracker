package com.zetta.expensetracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zetta.expensetracker.R
import com.zetta.expensetracker.data.Money
import com.zetta.expensetracker.data.TYPE_EXPENSE
import com.zetta.expensetracker.data.Tx
import com.zetta.expensetracker.databinding.ItemTxBinding

class TxAdapter(
    private val onClick: (Tx) -> Unit,
    private val onLongClick: (Tx) -> Unit
) : ListAdapter<Tx, TxAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    fun submit(data: List<Tx>) = submitList(data)

    inner class VH(private val b: ItemTxBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(t: Tx) {
            val ctx = b.root.context
            b.txtMerchant.text = t.merchant.ifBlank { ctx.getString(R.string.unknown_merchant) }
            b.txtCategory.text = t.category
            b.txtDate.text = Money.prettyDate(t.date)
            val signed = if (t.type == TYPE_EXPENSE) -t.amountCents.toLong() else t.amountCents.toLong()
            b.txtAmount.text = Money.fmt(signed, withSign = true)
            b.txtAmount.setTextColor(
                ctx.getColor(if (t.type == TYPE_EXPENSE) R.color.expense else R.color.income)
            )
            b.imgDup.visibility = if (t.possibleDup) android.view.View.VISIBLE else android.view.View.GONE
            b.root.setOnClickListener { onClick(t) }
            b.root.setOnLongClickListener {
                onLongClick(t)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Tx>() {
            override fun areItemsTheSame(a: Tx, b: Tx) = a.id == b.id
            override fun areContentsTheSame(a: Tx, b: Tx) = a == b
        }
    }
}
