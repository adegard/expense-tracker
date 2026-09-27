package com.zetta.expensetracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.zetta.expensetracker.data.Money
import com.zetta.expensetracker.data.MonthStat
import kotlin.math.max

/** Simple grouped bar chart: expenses vs income per month. No external libraries. */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var data: List<MonthStat> = emptyList()

    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = spToPx(10f)
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    var colorExpense: Int = 0xFFD32F2F.toInt()
    var colorIncome: Int = 0xFF2E7D32.toInt()
    var colorText: Int = 0xFF888888.toInt()
    var colorGrid: Int = 0x22000000

    fun setData(list: List<MonthStat>) {
        data = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paintGrid.color = colorGrid
        paintText.color = colorText
        if (data.isEmpty()) return

        val labelH = spToPx(16f)
        val topPad = spToPx(6f)
        val chartH = height - labelH - topPad - spToPx(4f)
        if (chartH <= 0) return
        val chartBottom = topPad + chartH

        var maxV = 0.0
        for (m in data) {
            maxV = max(maxV, max(m.expenseCents.toDouble(), m.incomeCents.toDouble()))
        }
        if (maxV <= 0) maxV = 1.0

        val slot = width.toFloat() / data.size
        val barW = slot * 0.28f
        val gap = slot * 0.06f

        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, paintGrid)

        data.forEachIndexed { i, m ->
            val cx = slot * i + slot / 2f
            val eH = (m.expenseCents / maxV * chartH).toFloat()
            val iH = (m.incomeCents / maxV * chartH).toFloat()
            paintBar.color = colorExpense
            if (eH > 0f) {
                canvas.drawRoundRect(
                    RectF(cx - barW - gap / 2f, chartBottom - eH, cx - gap / 2f, chartBottom),
                    spToPx(2f), spToPx(2f), paintBar
                )
            }
            paintBar.color = colorIncome
            if (iH > 0f) {
                canvas.drawRoundRect(
                    RectF(cx + gap / 2f, chartBottom - iH, cx + barW + gap / 2f, chartBottom),
                    spToPx(2f), spToPx(2f), paintBar
                )
            }
            // over a whole year the labels would overlap: draw a few, spread out
            val step = if (data.size > 8) (data.size + 5) / 6 else 1
            if (i % step == 0 || i == data.size - 1) {
                canvas.drawText(Money.monthLabel(m.month), cx, height - spToPx(3f), paintText)
            }
        }
    }

    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
