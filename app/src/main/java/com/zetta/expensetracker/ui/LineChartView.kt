package com.zetta.expensetracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.zetta.expensetracker.data.Money
import com.zetta.expensetracker.data.MonthStat
import kotlin.math.max

/**
 * Line chart of a single series (one category) over the selected months: grid, soft area fill,
 * the polyline and a dot per month. No external libraries, same style as [BarChartView].
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var data: List<MonthStat> = emptyList()

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintArea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintDotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(10f)
    }
    private val paintYLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(9f)
        textAlign = Paint.Align.LEFT
    }

    // mid greys: legible on both the light and the dark theme
    var colorLine: Int = 0xFFD32F2F.toInt()
    var colorArea: Int = 0x26D32F2F
    var colorText: Int = 0xFF9E9E9E.toInt()
    var colorGrid: Int = 0x33808080
    var colorDotRing: Int = 0xFFFFFFFF.toInt()

    fun setData(list: List<MonthStat>) {
        data = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        paintLine.color = colorLine
        paintArea.color = colorArea
        paintDot.color = colorLine
        paintDotRing.color = colorDotRing
        paintGrid.color = colorGrid
        paintText.color = colorText
        paintYLabel.color = colorText

        val labelH = spToPx(16f)
        val topPad = spToPx(6f)
        val yAxisW = spToPx(34f)
        val chartH = height - labelH - topPad - dp(4f)
        if (chartH <= 0f) return
        val left = yAxisW
        val right = width - dp(2f)
        val chartW = right - left
        if (chartW <= 0f) return
        val bottom = topPad + chartH

        var maxV = 0L
        for (m in data) maxV = max(maxV, m.expenseCents)
        // round the ceiling up to a clean step so the grid lines read well
        val step = niceStep(maxV)
        val top = step

        // horizontal grid + y labels
        for (i in 0..2) {
            val v = top * i / 2
            val y = (bottom - (v.toDouble() / top) * chartH).toFloat()
            canvas.drawLine(left, y, right, y, paintGrid)
            canvas.drawText(Money.fmtCompact(v), 0f, y - dp(2f), paintYLabel)
        }

        val n = data.size
        val slot = chartW / n
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = left + slot * i + slot / 2f
            val y = (bottom - (data[i].expenseCents.toDouble() / top) * chartH).toFloat()
            pts[i * 2] = x
            pts[i * 2 + 1] = y
        }

        // soft area under the line
        val area = Path()
        area.moveTo(pts[0], bottom)
        for (i in 0 until n) area.lineTo(pts[i * 2], pts[i * 2 + 1])
        area.lineTo(pts[(n - 1) * 2], bottom)
        area.close()
        canvas.drawPath(area, paintArea)

        // the line itself
        val line = Path()
        line.moveTo(pts[0], pts[1])
        for (i in 1 until n) line.lineTo(pts[i * 2], pts[i * 2 + 1])
        canvas.drawPath(line, paintLine)

        // a dot per month, ringed so it stays visible on the fill
        for (i in 0 until n) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            canvas.drawCircle(x, y, dp(3.2f), paintDot)
            canvas.drawCircle(x, y, dp(3.2f), paintDotRing)
        }

        // x labels: thin them out over 12-24 months so they never overlap
        val stepX = if (n > 8) (n + 5) / 6 else 1
        paintText.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            if (i % stepX == 0 || i == n - 1) {
                canvas.drawText(Money.monthLabel(data[i].month), pts[i * 2], height - dp(3f), paintText)
            }
        }
    }

    /** Rounds a maximum up to 1/2/5 x 10^n so the y axis gets 0, half and full ticks. */
    private fun niceStep(maxCents: Long): Long {
        if (maxCents <= 0) return 100
        val euros = maxCents / 100
        val pow = Math.pow(10.0, Math.floor(Math.log10(max(euros, 1L).toDouble())))
        val norm = euros / pow
        val mult = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 5.0 -> 5.0
            else -> 10.0
        }
        return max(100L, (mult * pow).toLong() * 100)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
