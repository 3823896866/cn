package com.xiaoran.nb.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 圆形 GIF 播放控件（纯 Android Movie API，无需第三方库）
 * - 圆形裁剪 + 白色描边
 * - 自动循环播放 GIF 动图
 */
class GifMovieView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var movie: Movie? = null
    private var movieStart = 0L
    private var movieDuration = 0
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val clipPath = Path()

    /** 从输入流加载 GIF */
    fun setGif(input: java.io.InputStream) {
        movie = Movie.decodeStream(input)
        movieDuration = movie?.duration() ?: 0
        movieStart = 0L
        invalidate()
    }

    fun setGifResource(context: Context, resId: Int) {
        runCatching { setGif(context.resources.openRawResource(resId)) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = movie ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f

        // 圆形裁剪
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // 帧定位
        val now = SystemClock.uptimeMillis()
        if (movieStart == 0L) movieStart = now
        if (movieDuration > 0) {
            m.setTime(((now - movieStart) % movieDuration).toInt())
        }

        // 居中缩放绘制（CENTER_CROP）
        val mw = m.width().toFloat()
        val mh = m.height().toFloat()
        if (mw > 0 && mh > 0) {
            val scale = maxOf(w / mw, h / mh)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)
            m.draw(canvas, -mw / 2f, -mh / 2f)
            canvas.restore()
        }
        canvas.restore()

        // 白色描边
        canvas.drawCircle(cx, cy, radius - borderPaint.strokeWidth / 2f, borderPaint)

        // 持续刷新播放
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}