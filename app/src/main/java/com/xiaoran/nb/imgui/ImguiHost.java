package com.xiaoran.nb.imgui;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import java.util.Locale;

/**
 * 小染集成：把 imgui-md3 的 MD3 ImGui 引擎承载进系统悬浮窗。
 *
 * 非 Activity：由 {@code com.xiaoran.nb.ui.FloatingWindowService} new 出来，
 * 把 {@link #getView()}（一个 GLSurfaceView）addView 到 WindowManager。
 *
 * 职责：GL 帧循环调 C++；触摸/按键/IME 转发 C++；并提供 C++ 调回的实例方法
 * （getAssets / showIme / hideIme / openUrl / setClipboard / getClipboard /
 *  ttsInit / ttsSay / ttsStop / ttsSetPitch / ttsSetRate / mikasaToggle）。
 */
public final class ImguiHost {

    static { System.loadLibrary("imguidemo"); }

    private final Context ctx;
    private final ImGuiGLSurfaceView gl;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile float pitch = 1.0f, rate = 1.0f;

    public ImguiHost(Context context) {
        this.ctx = context.getApplicationContext();
        gl = new ImGuiGLSurfaceView(ctx);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        gl.setRenderer(new Renderer());
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        gl.setImeCallback(new ImGuiGLSurfaceView.ImeCallback() {
            @Override public void onCommitText(String text) {
                if (text == null) return;
                for (int i = 0; i < text.length(); i++) nativeOnChar(text.charAt(i));
            }
            @Override public void onDeleteChar() { nativeOnDeleteChar(); }
        });
    }

    /** Service 要 addView 的 GLSurfaceView。 */
    public GLSurfaceView getView() { return gl; }

    public void start() { gl.onResume(); }
    public void stop()  { gl.onPause(); }

    /** 把原 buildTabData 功能列表推给 C++（行协议，见 mikasa_page.cpp）。 */
    public void pushFunctions(String lines) { nativePushXrFunctions(lines); }
    /** 推灵动岛状态（time/fps/temp/battery/song，行协议）。 */
    public void pushStatus(String lines) { nativePushXrStatus(lines); }

    // ===== native（符号 Java_com_xiaoran_nb_imgui_ImguiHost_*，见 md3_jni.cpp）=====
    private native void nativeInit(ImguiHost host, android.view.Surface surface);
    private native void nativeResize(int w, int h);
    private native void nativeRenderFrame();
    private native void nativeShutdown();
    private native void nativeOnTouch(int action, float x, float y);
    private native void nativeOnKey(int keyCode, boolean down);
    private native void nativeOnChar(int unicodeChar);
    private native void nativeOnCommitText(String text);
    private native void nativeOnDeleteChar();
    private native void nativePushXrFunctions(String lines);
    private native void nativePushXrStatus(String lines);
    private native void nativeXrCardResult(boolean ok, String msg);
    private native void nativeXrShizukuResult(boolean ok, String msg);
    private native void nativeXrService(boolean disabled, String announce, boolean force, String minv, String url);
    private native void nativeXrPreFillCard(String key);
    private native void nativeXrResetGate();
    private native void nativeXrPushMedia(String files, String music);
    private native void nativeXrDownloadProgress(String name, float pct, boolean done);
    private native void nativeXrStats(int online, int total);
    private native void nativeXrPushCs(String csv);

    /** Service 把触摸转发给 C++（拖动/收起由 Service 掌控）。 */
    public void forwardTouch(int action, float x, float y) { nativeOnTouch(action, x, y); }

    // ===== 小染：卡密 / Shizuku / 服务状态 对接（C++ 调回 / Kotlin 推送）=====
    private final java.util.concurrent.ExecutorService exec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private String deviceId() {
        String id = ctx.getSharedPreferences("xiaoran_prefs", Context.MODE_PRIVATE).getString("device_id", null);
        if (id == null) {
            id = "dev-" + android.os.Build.DEVICE + "-" + android.os.Build.MODEL.replace(' ', '_')
                    + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            ctx.getSharedPreferences("xiaoran_prefs", Context.MODE_PRIVATE).edit()
                .putString("device_id", id).apply();
        }
        return id;
    }

    /** C++ 门禁页点"验证并进入"：异步验卡（保留上次输入的卡密到 prefs）。 */
    public void cardVerify(String key) {
        exec.execute(() -> {
            boolean ok = com.xiaoran.nb.net.XrApi.INSTANCE.verifyCard(key, deviceId());
            ctx.getSharedPreferences("xiaoran_prefs", Context.MODE_PRIVATE).edit()
                    .putString("last_card", key).apply();
            nativeXrCardResult(ok, ok ? "验证通过" : "卡密无效，请重新输入");
        });
    }
    /** C++ 门禁页点"重新检测 Shizuku"：真实探测本地 RPC。 */
    public void shizukuCheck() {
        exec.execute(() -> {
            com.xiaoran.nb.net.ShizukuDetector.Result r = com.xiaoran.nb.net.ShizukuDetector.check(ctx);
            nativeXrShizukuResult(r.ok, r.msg);
        });
    }
    /** C++ 门禁页"去授权 Shizuku"：拉起 Shizuku App（未装则去商店）。 */
    public void openShizuku() {
        try {
            ctx.startActivity(new android.content.Intent().setPackage("moe.shizuku.privileged.api")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            try {
                ctx.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored2) {}
        }
    }
    /** Kotlin 拉取后端服务状态后推给 C++（服务停用/公告/强制更新）。 */
    public void pushService(boolean disabled, String announce, boolean force, String minv, String url) {
        nativeXrService(disabled, announce, force, minv, url);
    }
    /** Kotlin 预填上次输入的卡密（每次开悬浮窗都要重输，但保留已输的值）。 */
    public void preFillCard(String key) { nativeXrPreFillCard(key == null ? "" : key); }
    /** 重新开/重建悬浮窗：回到启动页并清空卡密结果（不自动登录）。 */
    public void resetGate() { nativeXrResetGate(); }

    // ===== 文件/音乐 =====
    private android.media.MediaPlayer mp;
    private android.view.TextureView videoView;
    private android.media.MediaPlayer videoMp;
    private android.view.WindowManager videoWm;

    /** Kotlin 把后端文件/音乐列表推给 C++（"name\turl\n..."）。 */
    public void pushMedia(String filesCsv, String musicCsv) {
        nativeXrPushMedia(filesCsv == null ? "" : filesCsv, musicCsv == null ? "" : musicCsv);
    }
    /** 在线/使用人数推给 C++（显示在资源页顶）。 */
    public void pushStats(int online, int total) { nativeXrStats(online, total); }
    /** 客服 Q&A（"问题\t回答\n..."）推给 C++ 客服页。 */
    public void pushCs(String csv) { nativeXrPushCs(csv == null ? "" : csv); }
    /** C++ 客服页“转人工”：把留言发给后端（自动带当前卡密 + 设备）。 */
    public void csMessage(String text) {
        exec.execute(() -> {
            try {
                String card = ctx.getSharedPreferences("xiaoran_prefs", Context.MODE_PRIVATE).getString("last_card", "");
                com.xiaoran.nb.net.XrApi.INSTANCE.csMessage(card == null ? "" : card, deviceId(), text, "");
            } catch (Throwable ignored) {}
        });
    }
    /** C++ 小染页"视频背景"开关：单独 overlay 播真实视频（清晰、非占位模糊）。 */
    public void videoBg(int on) {
        ui.post(() -> {
            try {
                if (on == 0) { stopVideoInternal(); return; }
                if (videoView != null) return;
                String src = com.xiaoran.nb.net.XrApi.INSTANCE.videoBgUrl();
                if (src == null || src.isEmpty()) src = "/sdcard/Download/xiaoran_bg.mp4";
                videoView = new android.view.TextureView(ctx);
                videoWm = (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                int type = android.os.Build.VERSION.SDK_INT >= 26
                        ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : android.view.WindowManager.LayoutParams.TYPE_PHONE;
                int h = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.5);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT, h, type,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP;
                videoWm.addView(videoView, lp);
                videoMp = new android.media.MediaPlayer();
                videoMp.setDataSource(src);
                videoMp.setSurface(new android.view.Surface(videoView.getSurfaceTexture()));
                videoMp.setLooping(true);
                videoMp.setVolume(0.5f, 0.5f);
                videoMp.start();
            } catch (Throwable ignored) { stopVideoInternal(); }
        });
    }
    private void stopVideoInternal() {
        try { if (videoMp != null) { videoMp.stop(); videoMp.release(); } } catch (Throwable ignored) {}
        videoMp = null;
        if (videoView != null) { try { if (videoWm != null) videoWm.removeView(videoView); } catch (Throwable ignored) {} }
        videoView = null;
    }

    /** C++ 文件页"下载"：入参 "name\turl"，下到 importDir（后端配置），带进度回调。 */
    public void download(String nameTabUrl) {
        String n0 = nameTabUrl, u0 = "";
        int t = nameTabUrl.indexOf('\t');
        if (t > 0) { n0 = nameTabUrl.substring(0, t); u0 = nameTabUrl.substring(t + 1); }
        final String name = n0, url = u0;
        exec.execute(() -> {
            java.io.File dir = new java.io.File(com.xiaoran.nb.net.XrApi.INSTANCE.importDir());
            if (dir == null || !dir.exists()) dir = ctx.getExternalFilesDir("imports");
            boolean ok = com.xiaoran.nb.net.XrApi.INSTANCE.download(url, dir, name, p -> {
                nativeXrDownloadProgress(name, p.floatValue(), p >= 1.0);
                return kotlin.Unit.INSTANCE;
            });
            if (!ok) nativeXrDownloadProgress(name, 0f, false);
        });
    }
    /** C++ 音乐页"播放"：流式播放后端音乐 URL。 */
    public void playMusic(String url) {
        exec.execute(() -> {
            try { stopMusic(); } catch (Throwable ignored) {}
            try {
                mp = new android.media.MediaPlayer();
                mp.setDataSource(url); mp.prepare(); mp.start();
            } catch (Throwable t) { mp = null; }
        });
    }
    /** C++ 音乐页"停止"。 */
    public void stopMusic() {
        exec.execute(() -> {
            if (mp != null) { try { mp.stop(); mp.release(); } catch (Throwable ignored) {} mp = null; }
        });
    }

    // ===== C++ 调回的实例方法（签名须与 md3_jni/md3_ai/md3_tts/md3_fx 的 GetMethodID 严格一致）=====
    public AssetManager getAssets() { return ctx.getAssets(); }

    public void showIme() {
        ui.post(() -> {
            try {
                gl.requestFocus();
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(gl, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) {}
        });
    }
    public void hideIme() {
        ui.post(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(gl.getWindowToken(), 0);
            } catch (Throwable ignored) {}
        });
    }
    public void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        ui.post(() -> {
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {}
        });
    }
    public void setClipboard(String text) {
        if (text == null) return;
        ui.post(() -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("XiaoranNB", text));
            } catch (Throwable ignored) {}
        });
    }
    public String getClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence s = cm.getPrimaryClip().getItemAt(0).getText();
                if (s != null) return s.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public void ttsInit() {
        ui.post(() -> {
            if (tts != null) return;
            tts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    int r = tts.setLanguage(Locale.CHINA);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                        tts.setLanguage(Locale.getDefault());
                    tts.setPitch(pitch); tts.setSpeechRate(rate); ttsReady = true;
                }
            });
        });
    }
    public void ttsSay(String text) {
        if (text == null || text.isEmpty()) return;
        ui.post(() -> {
            if (tts == null) { ttsInit(); return; }
            if (tts != null && ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "xiaoran");
        });
    }
    public void ttsStop() { ui.post(() -> { if (tts != null) tts.stop(); }); }
    public void ttsSetPitch(float p) { pitch = p; ui.post(() -> { if (tts != null) tts.setPitch(p); }); }
    public void ttsSetRate(float r)  { rate = r;  ui.post(() -> { if (tts != null) tts.setSpeechRate(r); }); }

    /** C++ NAV_MIKASA 页勾选/开关/折叠变更 → 交给 Service 决定提示（保持原 toast 行为）。 */
    public void mikasaToggle(String name, boolean checked) {
        if (listener != null) listener.onMikasaToggle(name, checked);
    }
    public interface ToggleListener { void onMikasaToggle(String name, boolean checked); }
    private volatile ToggleListener listener;
    public void setToggleListener(ToggleListener l) { this.listener = l; }

    @Override protected void finalize() { try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {} }

    private class Renderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig cfg) {
            nativeInit(ImguiHost.this, ImguiHost.this.gl.getHolder().getSurface());
        }
        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int w, int h) {
            nativeResize(w, h);
        }
        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            nativeRenderFrame();
        }
    }
}
