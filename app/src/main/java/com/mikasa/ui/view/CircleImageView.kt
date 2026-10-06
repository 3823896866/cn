package com.mikasa.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 自定义圆形ImageView
 * 使用BitmapShader实现圆形裁剪，不依赖第三方库
 * 支持白色边框、描边宽度自定义
 */
class CircleImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // 画笔
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circleBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 矩形区域
    private val drawableRect = RectF()
    private val borderRect = RectF()

    // 变换矩阵
    private val shaderMatrix = Matrix()

    // BitmapShader
    private var bitmapShader: BitmapShader? = null

    // 当前bitmap
    private var currentBitmap: Bitmap? = null

    // 配置
    var borderWidth: Float = 2f
        set(value) {
            field = value
            setup()
            invalidate()
        }

    var borderColor: Int = Color.WHITE
        set(value) {
            field = value
            borderPaint.color = value
            invalidate()
        }

    var circleBackgroundColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            circleBackgroundPaint.color = value
            invalidate()
        }

    // 圆形半径
    private var drawableRadius: Float = 0f
    private var borderRadius: Float = 0f

    // 是否准备好绘制
    private var isReady: Boolean = false
    private var isSetupPending: Boolean = false

    init {
        super.setScaleType(ScaleType.CENTER_CROP)
        bitmapPaint.isAntiAlias = true
        bitmapPaint.isDither = true
        bitmapPaint.isFilterBitmap = true

        borderPaint.isAntiAlias = true
        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = borderColor
        borderPaint.strokeWidth = borderWidth

        circleBackgroundPaint.isAntiAlias = true
        circleBackgroundPaint.color = circleBackgroundColor

        isReady = true
        if (isSetupPending) {
            setup()
            isSetupPending = false
        }
    }

    override fun getScaleType(): ScaleType {
        return ScaleType.CENTER_CROP
    }

    override fun setScaleType(scaleType: ScaleType) {
        // 强制使用CENTER_CROP
        super.setScaleType(ScaleType.CENTER_CROP)
    }

    override fun setAdjustViewBounds(adjustViewBounds: Boolean) {
        super.setAdjustViewBounds(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (currentBitmap == null) {
            // 没有图片时绘制圆形背景和边框
            canvas.drawCircle(
                drawableRect.centerX(),
                drawableRect.centerY(),
                drawableRadius,
                circleBackgroundPaint
            )
            if (borderWidth > 0) {
                canvas.drawCircle(
                    borderRect.centerX(),
                    borderRect.centerY(),
                    borderRadius,
                    borderPaint
                )
            }
            return
        }

        // 绘制圆形图片
        canvas.drawCircle(
            drawableRect.centerX(),
            drawableRect.centerY(),
            drawableRadius,
            bitmapPaint
        )

        // 绘制边框
        if (borderWidth > 0) {
            canvas.drawCircle(
                borderRect.centerX(),
                borderRect.centerY(),
                borderRadius,
                borderPaint
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setup()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        initializeBitmap()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        initializeBitmap()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        initializeBitmap()
    }

    override fun setImageURI(uri: Uri?) {
        super.setImageURI(uri)
        initializeBitmap()
    }

    /**
     * 从Drawable提取Bitmap
     */
    private fun getBitmapFromDrawable(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        try {
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(2),
                drawable.intrinsicHeight.coerceAtLeast(2),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 初始化Bitmap并设置Shader
     */
    private fun initializeBitmap() {
        currentBitmap = getBitmapFromDrawable(drawable)
        setup()
    }

    /**
     * 设置绘制参数
     */
    private fun setup() {
        if (!isReady) {
            isSetupPending = true
            return
        }

        if (width == 0 && height == 0) return

        if (currentBitmap == null) {
            invalidate()
            return
        }

        // 创建BitmapShader
        bitmapShader = BitmapShader(currentBitmap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bitmapPaint.shader = bitmapShader

        // 计算绘制区域
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom

        val sideLength = min(availableWidth, availableHeight)

        // 边框区域
        val left = paddingLeft + (availableWidth - sideLength) / 2f
        val top = paddingTop + (availableHeight - sideLength) / 2f
        borderRect.set(
            left,
            top,
            left + sideLength,
            top + sideLength
        )

        // 图片区域（减去边框）
        val halfBorder = borderWidth / 2f
        drawableRect.set(
            borderRect.left + halfBorder,
            borderRect.top + halfBorder,
            borderRect.right - halfBorder,
            borderRect.bottom - halfBorder
        )

        // 计算半径
        drawableRadius = min(drawableRect.width(), drawableRect.height()) / 2f
        borderRadius = min(borderRect.width(), borderRect.height()) / 2f - halfBorder

        // 更新Shader矩阵
        updateShaderMatrix()
        invalidate()
    }

    /**
     * 更新Shader变换矩阵，使图片居中裁剪显示
     */
    private fun updateShaderMatrix() {
        if (currentBitmap == null) return

        val scale: Float
        var dx = 0f
        var dy = 0f

        shaderMatrix.set(null)

        val bitmapWidth = currentBitmap!!.width
        val bitmapHeight = currentBitmap!!.height

        // 计算缩放比例，使图片填满圆形区域（CENTER_CROP效果）
        if (bitmapWidth * drawableRect.height() > drawableRect.width() * bitmapHeight) {
            scale = drawableRect.height() / bitmapHeight.toFloat()
            dx = (drawableRect.width() - bitmapWidth * scale) * 0.5f
        } else {
            scale = drawableRect.width() / bitmapWidth.toFloat()
            dy = (drawableRect.height() - bitmapHeight * scale) * 0.5f
        }

        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(
            drawableRect.left + dx,
            drawableRect.top + dy
        )

        bitmapShader?.setLocalMatrix(shaderMatrix)
    }

    /**
     * 设置边框属性
     */
    fun setBorder(borderWidth: Float, borderColor: Int) {
        this.borderWidth = borderWidth
        this.borderColor = borderColor
        borderPaint.color = borderColor
        borderPaint.strokeWidth = borderWidth
        setup()
    }
}
