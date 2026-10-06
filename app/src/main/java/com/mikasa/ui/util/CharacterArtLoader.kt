package com.mikasa.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * 角色立绘加载工具类
 * 支持从assets、本地文件路径、资源ID加载图片
 * 带内存缓存，支持运行时切换立绘，所有绑定的ImageView自动更新
 */
object CharacterArtLoader {

    // 线程池用于后台加载图片
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 内存缓存，大小为应用可用内存的1/8
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // 当前立绘路径
    private var currentCharacterPath: String = "illust/character.png"

    // 当前头像路径
    private var currentAvatarPath: String = "avatar/avatar.png"

    // 绑定的ImageView弱引用列表（立绘）
    private val characterImageViews = mutableListOf<WeakReference<ImageView>>()

    // 绑定的ImageView弱引用列表（头像）
    private val avatarImageViews = mutableListOf<WeakReference<ImageView>>()

    // 占位图资源ID
    private var placeholderResId: Int = 0
    private var avatarPlaceholderResId: Int = 0

    /**
     * 初始化加载器
     * @param placeholder 立绘占位图资源ID
     * @param avatarPlaceholder 头像占位图资源ID
     */
    fun init(placeholder: Int, avatarPlaceholder: Int) {
        placeholderResId = placeholder
        avatarPlaceholderResId = avatarPlaceholder
    }

    /**
     * 从assets加载Bitmap
     */
    fun loadFromAssets(context: Context, path: String): Bitmap? {
        return try {
            context.assets.open(path).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从本地文件路径加载Bitmap
     */
    fun loadFromFile(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从资源ID加载Bitmap
     */
    fun loadFromRes(context: Context, resId: Int): Bitmap? {
        return try {
            BitmapFactory.decodeResource(context.resources, resId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取缓存key
     */
    private fun getCacheKey(path: String): String {
        return path
    }

    /**
     * 从缓存获取Bitmap
     */
    fun getFromCache(path: String): Bitmap? {
        return memoryCache.get(getCacheKey(path))
    }

    /**
     * 添加到缓存
     */
    fun putToCache(path: String, bitmap: Bitmap) {
        memoryCache.put(getCacheKey(path), bitmap)
    }

    /**
     * 智能加载图片 - 自动判断路径类型
     * @param context 上下文
     * @param path 路径：assets路径（如"illust/character.png"）或本地文件路径（如"/sdcard/xxx.png"）
     * @return Bitmap或null
     */
    fun loadBitmap(context: Context, path: String): Bitmap? {
        // 先从缓存获取
        getFromCache(path)?.let { return it }

        val bitmap = when {
            // 本地文件路径（以/开头）
            path.startsWith("/") -> loadFromFile(path)
            // assets路径
            else -> loadFromAssets(context, path)
        }

        // 存入缓存
        bitmap?.let { putToCache(path, it) }
        return bitmap
    }

    /**
     * 设置立绘路径并刷新所有绑定的ImageView
     * @param path assets路径或本地文件路径
     */
    fun setCharacter(context: Context, path: String) {
        currentCharacterPath = path
        // 清除旧缓存
        memoryCache.remove(getCacheKey(path))
        // 重新加载并刷新
        loadAndApplyCharacter(context, path)
    }

    /**
     * 设置头像路径并刷新所有绑定的ImageView
     */
    fun setAvatar(context: Context, path: String) {
        currentAvatarPath = path
        memoryCache.remove(getCacheKey(path))
        loadAndApplyAvatar(context, path)
    }

    /**
     * 加载并应用立绘到所有绑定的ImageView
     */
    private fun loadAndApplyCharacter(context: Context, path: String) {
        executor.execute {
            val bitmap = loadBitmap(context, path)
            mainHandler.post {
                val drawable: Drawable? = bitmap?.let { BitmapDrawable(context.resources, it) }
                    ?: if (placeholderResId != 0) {
                        try {
                            context.getDrawable(placeholderResId)
                        } catch (e: Exception) { null }
                    } else null

                characterImageViews.removeAll { it.get() == null }
                characterImageViews.forEach { ref ->
                    ref.get()?.setImageDrawable(drawable)
                }
            }
        }
    }

    /**
     * 加载并应用头像到所有绑定的ImageView
     */
    private fun loadAndApplyAvatar(context: Context, path: String) {
        executor.execute {
            val bitmap = loadBitmap(context, path)
            mainHandler.post {
                val drawable: Drawable? = bitmap?.let { BitmapDrawable(context.resources, it) }
                    ?: if (avatarPlaceholderResId != 0) {
                        try {
                            context.getDrawable(avatarPlaceholderResId)
                        } catch (e: Exception) { null }
                    } else null

                avatarImageViews.removeAll { it.get() == null }
                avatarImageViews.forEach { ref ->
                    ref.get()?.setImageDrawable(drawable)
                }
            }
        }
    }

    /**
     * 绑定立绘ImageView
     * 调用后会自动加载当前立绘并缓存引用，后续切换立绘时自动更新
     */
    fun bindCharacterImageView(context: Context, imageView: ImageView, path: String? = null) {
        val targetPath = path ?: currentCharacterPath
        characterImageViews.add(WeakReference(imageView))

        // 先尝试从缓存加载
        getFromCache(targetPath)?.let {
            imageView.setImageBitmap(it)
            return
        }

        // 异步加载
        executor.execute {
            val bitmap = loadBitmap(context, targetPath)
            mainHandler.post {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else if (placeholderResId != 0) {
                    imageView.setImageResource(placeholderResId)
                }
            }
        }
    }

    /**
     * 绑定头像ImageView
     */
    fun bindAvatarImageView(context: Context, imageView: ImageView, path: String? = null) {
        val targetPath = path ?: currentAvatarPath
        avatarImageViews.add(WeakReference(imageView))

        getFromCache(targetPath)?.let {
            imageView.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = loadBitmap(context, targetPath)
            mainHandler.post {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else if (avatarPlaceholderResId != 0) {
                    imageView.setImageResource(avatarPlaceholderResId)
                }
            }
        }
    }

    /**
     * 解绑ImageView（可选，当View被销毁时调用）
     */
    fun unbindImageView(imageView: ImageView) {
        characterImageViews.removeAll { it.get() == null || it.get() == imageView }
        avatarImageViews.removeAll { it.get() == null || it.get() == imageView }
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        memoryCache.evictAll()
    }

    /**
     * 获取当前立绘路径
     */
    fun getCurrentCharacterPath(): String = currentCharacterPath

    /**
     * 获取当前头像路径
     */
    fun getCurrentAvatarPath(): String = currentAvatarPath
}
