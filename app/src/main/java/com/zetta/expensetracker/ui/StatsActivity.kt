package com.zetta.expensetracker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zetta.expensetracker.R
import com.zetta.expensetracker.data.CatStat
import com.zetta.expensetracker.data.MerchantStat
import com.zetta.expensetracker.data.Money
import com.zetta.expensetracker.data.Months
import com.zetta.expensetracker.data.TxRepository
import com.zetta.expensetracker.databinding.ActivityStatsBinding
import com.zetta.expensetracker.databinding.ItemBarRowBinding
import com.zetta.expensetracker.databinding.ItemTopRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsActivity : AppCompatActivity() {

    private lateinit var b: ActivityStatsBinding
    private lateinit var repo: TxRepository
    private var month: String? = null
    private var rangeMode = RANGE_6
    private var categories: List<String> = emptyList()
    private var selectedCategory: String? = null
    private var suppressCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = TxRepository.get(this)
        month = intent.getStringExtra(EXTRA_MONTH)?.takeIf { it != TxRepository.ALL_MONTHS }

        // default to the month that was tapped, otherwise the rolling 6 months
        if (month != null && validMonth(month!!)) {
            rangeMode = RANGE_MONTH
            b.chipMonth.isChecked = true
        } else {
            month = null
            rangeMode = RANGE_6
            b.chip6.isChecked = true
        }
        b.chipRange.setOnCheckedStateChangeListener { _, checkedIds ->
            rangeMode = when (checkedIds.firstOrNull()) {
                R.id.chipMonth -> RANGE_MONTH
                R.id.chip12 -> RANGE_12
                R.id.chipYear -> RANGE_YEAR
                R.id.chipPrev -> RANGE_PREV
                else -> RANGE_6
            }
            load()
        }
        if (month == null) b.chipMonth.isEnabled = false

        // one category at a time in the line chart, chosen from the dropdown
        b.ddCategory.setOnItemClickListener { _, _, position, _ ->
            if (suppressCallback) return@setOnItemClickListener
            selectedCategory = categories.getOrNull(position)
            lifecycleScope.launch { loadLine() }
        }
        lifecycleScope.launch {
            categories = withContext(Dispatchers.IO) { repo.allCategories() }
            if (categories.isEmpty()) {
                b.tilCategory.visibility = View.GONE
                b.lineChart.visibility = View.GONE
                b.txtNoCatLine.visibility = View.VISIBLE
                return@launch
            }
            b.ddCategory.setSimpleItems(categories.toTypedArray())
            // keep a valid selection when the list refreshes
            val keep = selectedCategory?.takeIf { it in categories } ?: categories.first()
            selectedCategory = keep
            suppressCallback = true
            b.ddCategory.setText(keep, false)
            suppressCallback = false
        }

        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun validMonth(iso: String) = iso.matches(Regex("""\d{4}-\d{2}"""))

    /** The [from, to] month window for the selected chip; both bounds are inclusive "yyyy-MM". */
    private fun window(): Pair<String, String> {
        val now = Months.nowIso()
        return when (rangeMode) {
            RANGE_MONTH -> (month ?: now) to (month ?: now)
            RANGE_12 -> Months.shift(now, -11) to now
            RANGE_YEAR -> {
                val y = Months.yearOf(now)
                Months.yearStart(y) to Months.yearEnd(y)
            }
            RANGE_PREV -> {
                val y = Months.yearOf(now) - 1
                Months.yearStart(y) to Months.yearEnd(y)
            }
            else -> Months.shift(now, -5) to now
        }
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                loadData()
            } catch (e: Exception) {
                // never let a stats hiccup take the app down
                b.txtChartTitle.text = getString(R.string.stats_error)
            }
        }
    }

    private suspend fun loadData() {
        val (from, to) = window()
        // a complete series: empty months are included, so a full year always plots 12 bars
        val months = withContext(Dispatchers.IO) { repo.monthStatsInRange(from, to) }
        val cats = withContext(Dispatchers.IO) { repo.byCategory(from, to) }
        val tops = withContext(Dispatchers.IO) { repo.topMerchants(from, to, 10) }
        val totals = withContext(Dispatchers.IO) { repo.totalsRange(from, to) }
        val count = withContext(Dispatchers.IO) { repo.countRange(from, to) }

        b.chart.setData(months)
        b.txtChartTitle.text = when {
            from == to -> getString(R.string.range_month, Money.monthLabel(from))
            rangeMode == RANGE_YEAR -> getString(R.string.range_title_year, Months.yearOf(from))
            rangeMode == RANGE_PREV -> getString(R.string.range_title_year, Months.yearOf(from))
            rangeMode == RANGE_12 -> getString(R.string.range_title_12m)
            else -> getString(R.string.last_months, months.size)
        }

        val avg = if (count > 0) totals.expenseCents / count else 0L
        b.txtExpense.text = Money.fmt(totals.expenseCents)
        b.txtIncome.text = Money.fmt(totals.incomeCents)
        b.txtBalance.text = Money.fmt(totals.balanceCents, withSign = true)
        b.txtAvg.text = Money.fmt(avg)
        b.txtCount.text = resources.getQuantityString(R.plurals.tx_count, count, count)

        renderCategories(cats)
        renderTop(tops)
        loadLine()
    }

    /** Monthly expense line for the category picked in the dropdown, over the current range. */
    private suspend fun loadLine() {
        val cat = selectedCategory
        if (cat == null || categories.isEmpty()) {
            b.lineChart.setData(emptyList())
            b.txtCatLineSum.visibility = View.GONE
            return
        }
        val (from, to) = window()
        val series = withContext(Dispatchers.IO) { repo.monthStatsForCategory(cat, from, to) }
        b.lineChart.setData(series)
        val sum = series.sumOf { it.expenseCents }
        val peak = series.maxOfOrNull { it.expenseCents } ?: 0L
        val active = series.count { it.expenseCents > 0 }
        b.txtCatLineSum.visibility = if (active == 0) View.GONE else View.VISIBLE
        b.txtCatLineSum.text = getString(
            R.string.cat_line_sum,
            Money.fmt(sum),
            Money.fmt(if (active > 0) sum / active else 0L),
            Money.monthLabel(series.maxByOrNull { it.expenseCents }?.month ?: from) +
                " " + Money.fmt(peak)
        )
    }

    private fun renderCategories(cats: List<CatStat>) {
        b.layoutCategories.removeAllViews()
        if (cats.isEmpty()) {
            b.txtNoCat.visibility = View.VISIBLE
            b.txtCatTotal.visibility = View.GONE
            return
        }
        b.txtNoCat.visibility = View.GONE
        val total = cats.sumOf { it.cents }.coerceAtLeast(1L)
        b.txtCatTotal.visibility = View.VISIBLE
        b.txtCatTotal.text = getString(R.string.cat_total, Money.fmt(total))
        val inflater = LayoutInflater.from(this)
        cats.take(12).forEach { c ->
            val row = ItemBarRowBinding.inflate(inflater, b.layoutCategories, false)
            val pct = (c.cents * 100.0 / total).toInt().coerceIn(2, 100)
            row.txtLabel.text = c.category
            row.txtValue.text = getString(R.string.cat_line, Money.fmt(c.cents), pct, c.count)
            (row.bar.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
            (row.spacer.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
            b.layoutCategories.addView(row.root)
        }
    }

    private fun renderTop(tops: List<MerchantStat>) {
        b.layoutTop.removeAllViews()
        if (tops.isEmpty()) {
            b.txtNoTop.visibility = View.VISIBLE
            return
        }
        b.txtNoTop.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        tops.forEachIndexed { i, m ->
            val row = ItemTopRowBinding.inflate(inflater, b.layoutTop, false)
            row.txtRank.text = (i + 1).toString()
            row.txtLabel.text = m.merchant
            row.txtValue.text = getString(R.string.times_n, Money.fmt(m.cents), m.count)
            b.layoutTop.addView(row.root)
        }
    }

    companion object {
        const val EXTRA_MONTH = "month"
        private const val RANGE_MONTH = 0
        private const val RANGE_6 = 1
        private const val RANGE_12 = 2
        private const val RANGE_YEAR = 3
        private const val RANGE_PREV = 4
    }
}
