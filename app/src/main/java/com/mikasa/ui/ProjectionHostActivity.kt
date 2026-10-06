package com.mikasa.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * 透明 Activity：用于请求系统录屏(MediaProjection)授权。
 * 拿到授权后把 MediaProjection 交给 FloatingWindowService 建虚拟屏录制，随即自毁。
 */
class ProjectionHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(
                mpm.createScreenCaptureIntent(),
                PROJECTION_REQ_CODE
            )
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null && requestCode == PROJECTION_REQ_CODE) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj = mpm.getMediaProjection(PROJECTION_REQ_CODE, data)
                if (proj != null) {
                    com.mikasa.ui.FloatingWindowService.onProjectionGranted?.invoke(proj)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        finish()
    }

    companion object {
        const val PROJECTION_REQ_CODE = 0xA1CE
    }
}
