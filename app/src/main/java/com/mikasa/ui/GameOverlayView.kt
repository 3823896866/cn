package com.mikasa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 游戏辅助覆盖层：在同一透明悬浮层上画「辅助圆圈(红圈)」与「准心十字架(和平精英风格)」。
 * 圆圈大小 0-100，准心可切换类型(0圆+十字/1纯十字/2菱形)与颜色。
 */
class GameOverlayView(context: Context) : View(context) {
    var circleEnabled = false
    var circleSize = 40           // 0..100
    var crossEnabled = false
    var crossType = 0            // 0=圆环+十字, 1=纯十字, 2=菱形
    var crossColor = Color.RED
    var crossSize = 40           // 0..100，准心独立大小（与圆圈分开，自由调节）
    var crossX = 50              // 0..100，准心 X 位置（屏幕宽度百分比，50=正中）
    var crossY = 50              // 0..100，准心 Y 位置（屏幕高度百分比，50=正中）

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 6f
    }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    fun setCircle(enabled: Boolean, size0to100: Int) {
        circleEnabled = enabled
        circleSize = size0to100.coerceIn(0, 100)
        invalidate()
    }

    fun setCross(enabled: Boolean, type: Int, color: Int) {
        crossEnabled = enabled
        crossType = type
        crossColor = color
        invalidate()
    }

    /** 准心独立大小（0..100），自由调节、适配不同屏幕与习惯。 */
    fun applyCrossSize(size0to100: Int) {
        crossSize = size0to100.coerceIn(0, 100)
        invalidate()
    }

    /** 准心 X/Y 位置（0..100，屏幕百分比，50=正中），支持自由调节方位。 */
    fun applyCrossPosition(x0to100: Int, y0to100: Int) {
        crossX = x0to100.coerceIn(0, 100)
        crossY = y0to100.coerceIn(0, 100)
        invalidate()
    }

    fun clearColor() {
        paint.color = Color.RED
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * (crossX / 100f)
        val cy = height * (crossY / 100f)
        // 辅助圆圈（红圈）
        if (circleEnabled && circleSize > 0) {
            val r = px(20f + circleSize * 1.2f).toFloat()   // 20..140 dp 随大小伸缩
            paint.color = Color.RED
            paint.strokeWidth = px(3f).toFloat()
            canvas.drawCircle(cx, cy, r, paint)
        }
        // 准心十字架（和平精英风格）
        if (crossEnabled) {
            paint.color = crossColor
            paint.strokeWidth = px(2f + crossSize * 0.03f).toFloat()
            val arm = px(22f + crossSize * 1.3f).toFloat()
            val gap = px(5f + crossSize * 0.05f).toFloat()
            when (crossType) {
                0 -> { // 圆环 + 十字
                    canvas.drawCircle(cx, cy, px(10f + crossSize * 0.15f).toFloat(), paint)
                    drawCross(canvas, cx, cy, arm, gap)
                }
                1 -> drawCross(canvas, cx, cy, arm, gap)          // 纯十字
                2 -> { // 菱形准星
                    val d = px(10f + crossSize * 0.25f).toFloat()
                    val p1 = android.graphics.Path()
                    p1.moveTo(cx, cy - d); p1.lineTo(cx + d, cy)
                    p1.lineTo(cx, cy + d); p1.lineTo(cx - d, cy); p1.close()
                    canvas.drawPath(p1, paint)
                    drawCross(canvas, cx, cy, arm, gap)
                }
                else -> drawCross(canvas, cx, cy, arm, gap)
            }
        }
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, arm: Float, gap: Float) {
        canvas.drawLine(cx - arm, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + arm, cy, paint)
        canvas.drawLine(cx, cy - arm, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + arm, paint)
    }
}
