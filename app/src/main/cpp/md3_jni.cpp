// md3_jni.cpp - JNI entry points, event queue, asset loading, and ImGui lifecycle
#include "md3_common.h"
#include <cmath>
#include "imgui_impl_android.h"
#include "imgui_impl_opengl3.h"
#ifdef IMGUI_ENABLE_FREETYPE
#include "imgui_freetype.h"
#endif

ANativeWindow*    g_window = nullptr;
std::mutex        g_queueMutex;
std::deque<TouchEvent> g_touchQueue;
std::deque<InputEvent> g_inputQueue;
std::atomic<bool> g_initialized{false};
std::vector<char> g_fontBytes;
jobject           g_activityRef = nullptr;
ImGuiContext*     g_mainCtx = nullptr;
JavaVM*           g_jvm = nullptr;
jmethodID         g_showImeMethod = nullptr;
jmethodID         g_hideImeMethod = nullptr;

bool LoadAsset(AAssetManager* am, const char* name, std::vector<char>& out) {
    AAsset* a = AAssetManager_open(am, name, AASSET_MODE_BUFFER);
    if (a == nullptr) { LOGE("asset %s not found", name); return false; }
    off_t len = AAsset_getLength(a);
    out.resize(static_cast<size_t>(len));
    off_t got = AAsset_read(a, out.data(), len);
    AAsset_close(a);
    LOGI("asset %s: %ld/%ld bytes", name, (long) got, (long) len);
    return got == len && !out.empty();
}

void DrainQueues(ImGuiIO& io) {
    std::lock_guard<std::mutex> lock(g_queueMutex);
    for (const TouchEvent& ev : g_touchQueue) {
        switch (ev.action) {
        case 0: io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
                io.AddMousePosEvent(ev.x, ev.y);
                io.AddMouseButtonEvent(0, true); break;
        case 1: io.AddMousePosEvent(ev.x, ev.y);
                io.AddMouseButtonEvent(0, false); break;
        case 2: io.AddMousePosEvent(ev.x, ev.y); break;
        default: break;
        }
    }
    g_touchQueue.clear();
    for (const InputEvent& ev : g_inputQueue) {
        if (ev.type == EV_CHAR) {
            io.AddInputCharacter(ev.ch);
        } else if (ev.type == EV_BACKSPACE) {
            io.AddKeyEvent(ImGuiKey_Backspace, true);
            io.AddKeyEvent(ImGuiKey_Backspace, false);
        }
    }
    g_inputQueue.clear();
}

void SetPlatformImeDataFn(ImGuiContext*, ImGuiViewport*, ImGuiPlatformImeData* data) {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env != nullptr && g_showImeMethod != nullptr && g_hideImeMethod != nullptr) {
        if (data->WantVisible) env->CallVoidMethod(g_activityRef, g_showImeMethod);
        else                    env->CallVoidMethod(g_activityRef, g_hideImeMethod);
    }
    if (attached) g_jvm->DetachCurrentThread();
}

void CleanupImGui() {
    g_initialized = false;
    ImGui_ImplAndroid_Shutdown();
    ImGui_ImplOpenGL3_Shutdown();
    if (ImGui::GetCurrentContext() != nullptr) ImGui::DestroyContext();
    if (g_window != nullptr) { ANativeWindow_release(g_window); g_window = nullptr; }
    g_fontBytes.clear();
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_touchQueue.clear();
    g_inputQueue.clear();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeInit(JNIEnv* env, jobject obj,
                                               jobject jactivity, jobject surface) {
    if (g_initialized.load()) {
        LOGI("nativeInit: already initialized, cleaning up first");
        CleanupImGui();
    }
    MotionBlur::OnContextLost();   // 新 GL 上下文：旧纹理/FBO 句柄全部作废
    Live2D::OnContextLost();       // 立绘同理：整条链路等下一帧重建
    env->GetJavaVM(&g_jvm);
    g_activityRef = env->NewGlobalRef(jactivity);
    g_window = ANativeWindow_fromSurface(env, surface);
    LOGI("nativeInit: window=%p", g_window);
    PushLog(LOG_INIT, "nativeInit: window=%p", g_window);

    jclass cls = env->GetObjectClass(jactivity);
    g_showImeMethod = env->GetMethodID(cls, "showIme", "()V");
    g_hideImeMethod = env->GetMethodID(cls, "hideIme", "()V");

    IMGUI_CHECKVERSION();
    g_mainCtx = ImGui::CreateContext();
    PushLog(LOG_INIT, "ImGui 上下文已创建 (v1.91.8)");
    ImGuiIO& io = ImGui::GetIO();
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
    io.IniFilename = nullptr;
    io.ConfigDebugHighlightIdConflicts = false;

    jmethodID getAssets = env->GetMethodID(cls, "getAssets",
                                           "()Landroid/content/res/AssetManager;");
    if (getAssets != nullptr) {
        jobject assetsObj = env->CallObjectMethod(jactivity, getAssets);
        if (assetsObj != nullptr) {
            AAssetManager* am = AAssetManager_fromJava(env, assetsObj);
            if (am != nullptr) {
                if (LoadAsset(am, FONT_FILE, g_fontBytes)) {
                    PushLog(LOG_OK, "字体文件已读取 (%ld bytes)", (long)g_fontBytes.size());
                    ImFontConfig cfg;
                    cfg.FontDataOwnedByAtlas = false;
                    cfg.OversampleH = 1;
                    cfg.OversampleV = 1;
                    cfg.PixelSnapH = false;
                    cfg.SizePixels = FONT_SIZE;
#ifdef IMGUI_ENABLE_FREETYPE
                    cfg.FontBuilderFlags = ImGuiFreeTypeBuilderFlags_NoAutoHint |
                                           ImGuiFreeTypeBuilderFlags_LightHinting;
#endif
                    ImFont* f = io.Fonts->AddFontFromMemoryTTF(
                        g_fontBytes.data(), static_cast<int>(g_fontBytes.size()),
                        FONT_SIZE, &cfg, GLYPH_RANGES);
                    if (f != nullptr) {
                        io.FontDefault = f;
                        LOGI("SourceHanSansCN-Bold loaded (%.0fpx, FreeType)", FONT_SIZE);
                        PushLog(LOG_OK, "思源黑体粗体已加载 (32px, FreeType)");
                    } else {
                        PushLog(LOG_ERR, "AddFontFromMemoryTTF 失败");
                    }
                    ImFontConfig iconCfg;
                    iconCfg.FontDataOwnedByAtlas = false;
                    iconCfg.MergeMode = true;
                    iconCfg.OversampleH = 1;
                    iconCfg.OversampleV = 1;
                    io.Fonts->AddFontDefault(&iconCfg);
                    PushLog(LOG_OK, "ImGui 内置图标字形已合并入图集");
                } else {
                    PushLog(LOG_ERR, "字体文件缺失: %s", FONT_FILE);
                }
            }
            env->DeleteLocalRef(assetsObj);
        }
    }

    ImGui::GetPlatformIO().Platform_SetImeDataFn = SetPlatformImeDataFn;
    SetupMD3Theme();
    PushLog(LOG_OK, "Material Design 3 主题已应用 (5 套配色 x 2 明暗)");

    if (!ImGui_ImplAndroid_Init(g_window)) LOGE("ImGui_ImplAndroid_Init failed");
    if (!ImGui_ImplOpenGL3_Init()) LOGE("ImGui_ImplOpenGL3_Init failed");
    PushLog(LOG_INFO, "GLSurfaceView + OpenGL ES 2 渲染管线就绪");
    PushLog(LOG_INFO, "软键盘 IME 回调已注册 (InputConnection)");
    PushLog(LOG_INFO, "触摸事件队列已启用 (锁保护)");
    PushLog(LOG_WARN, "NDK 27.3 r27d 编译, ABI: arm64-v8a");
    PushLog(LOG_INFO, "FreeType 2.13.2 静态库已链接");
    PushLog(LOG_OK, "Avates 初始化完成");

    const ImVec4& surf = CurPalette().Surface;
    glClearColor(surf.x, surf.y, surf.z, 0.0f);
    g_initialized = true;
    LOGI("nativeInit done, font count=%d", io.Fonts->Fonts.Size);
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeResize(JNIEnv*, jobject*, jint width, jint height) {
    if (!g_initialized.load()) return;
    glViewport(0, 0, width, height);
    MotionBlur::SetScreenSize(width, height);
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeRenderFrame(JNIEnv*, jobject*) {
    if (!g_initialized.load()) return;
    if (g_mainCtx != nullptr) ImGui::SetCurrentContext(g_mainCtx);
    ImGuiIO& io = ImGui::GetIO();

    const float dtFrame = io.DeltaTime > 0.0f ? io.DeltaTime : 0.016f;
    Lic::Update(dtFrame);
    // 运动模糊开启时：整帧先画进全分辨率 FBO，最后再做一次拖影混合
    Fx::Update(io.DeltaTime > 0.0f ? io.DeltaTime : 0.016f, io.DisplaySize.x, io.DisplaySize.y);
    Live2D::OnFrame();   // ① 立绘：更新动作/物理/眨眼 + 离屏渲染到自己的 FBO（自带 GL 状态恢复）

    const bool blurOn = MotionBlur::Enabled();
    if (blurOn) MotionBlur::BeginCapture();
    // BeginCapture 可能因上下文丢失/资源重建而没进 FBO → 这时走普通清屏路径
    if (!MotionBlur::Capturing()) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        const ImVec4& surf = CurPalette().Surface;
        glClearColor(surf.x, surf.y, surf.z, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    DrainQueues(io);
    UiAnim::Update(io.DeltaTime);        // 音量键驱动的折叠 / 渐显
    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();
    RenderDemoFrame();
    ImGui::Render();
    ImDrawData* dd = ImGui::GetDrawData();
    if (UiAnim::HardHidden() && UiAnim::Hidden()) {
        if (!MotionBlur::Capturing()) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            const ImVec4& sp = CurPalette().Surface;
            glClearColor(sp.x, sp.y, sp.z, 0.0f);
            glClear(GL_COLOR_BUFFER_BIT);
        }
    } else {
        UiAnim::TransformDrawData(dd);   // 折叠/渐显顶点变换
        Lic::ApplyShake(dd);
        ImGui_ImplOpenGL3_RenderDrawData(dd);
    }

    if (blurOn) MotionBlur::EndCaptureAndPresent();
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeShutdown(JNIEnv* env, jobject*) {
    LOGI("nativeShutdown");
    CleanupImGui();
    if (g_activityRef != nullptr) {
        env->DeleteGlobalRef(g_activityRef);
        g_activityRef = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeOnTouch(JNIEnv*, jobject*, jint action, jfloat x, jfloat y) {
    if (!g_initialized.load()) return;
    if (UiAnim::BlocksInput()) return;      // 折起隐藏后不再响应普通点击
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_touchQueue.push_back({action, x, y});
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeOnKey(JNIEnv*, jobject*, jint keyCode, jboolean down) {
    if (!g_initialized.load()) return;
    // 音量键: 24=音量+ (渐显显示 UI), 25=音量- (折叠隐藏 UI)
    // Java 侧已 return true 吃掉事件, 所以系统音量不会跟着变
    if (keyCode == 24 || keyCode == 25) {
        UiAnim::OnVolumeKey(keyCode, down == JNI_TRUE);
        return;
    }
    ImGuiIO& io = ImGui::GetIO();
    ImGuiKey key = ImGuiKey_None;
    switch (keyCode) {
    case 66: key = ImGuiKey_Enter; break;
    case 67: key = ImGuiKey_Backspace; break;
    case 61: key = ImGuiKey_Tab; break;
    case 111: key = ImGuiKey_Escape; break;
    case 62: key = ImGuiKey_Space; break;
    case 123: key = ImGuiKey_LeftArrow; break;
    case 124: key = ImGuiKey_RightArrow; break;
    default: break;
    }
    if (key != ImGuiKey_None) io.AddKeyEvent(key, down == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeOnChar(JNIEnv*, jobject*, jint unicodeChar) {
    if (!g_initialized.load()) return;
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_inputQueue.push_back({EV_CHAR, (unsigned int) unicodeChar});
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeOnCommitText(JNIEnv* env, jobject*, jstring text) {
    if (!g_initialized.load()) return;
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr) return;
    std::lock_guard<std::mutex> lock(g_queueMutex);
    for (const char* p = chars; *p != 0; ) {
        unsigned int c; int len;
        if ((*p & 0x80) == 0) { c = (unsigned char)*p; len = 1; }
        else if ((*p & 0xE0) == 0xC0) { c = (*p & 0x1F) << 6 | (*(p+1) & 0x3F); len = 2; }
        else if ((*p & 0xF0) == 0xE0) { c = (*p & 0x0F) << 12 | (*(p+1) & 0x3F) << 6 | (*(p+2) & 0x3F); len = 3; }
        else if ((*p & 0xF8) == 0xF0) { c = (*p & 0x07) << 18 | (*(p+1) & 0x3F) << 12 | (*(p+2) & 0x3F) << 6 | (*(p+3) & 0x3F); len = 4; }
        else { p++; continue; }
        g_inputQueue.push_back({EV_CHAR, c});
        p += len;
    }
    env->ReleaseStringUTFChars(text, chars);
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeOnDeleteChar(JNIEnv*, jobject*) {
    if (!g_initialized.load()) return;
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_inputQueue.push_back({EV_BACKSPACE, 0});
}

// ---- 小染集成：Kotlin 推送功能列表 / 灵动岛状态 ----
JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativePushXrFunctions(JNIEnv* env, jobject, jstring s) {
    const char* c = (s != nullptr) ? env->GetStringUTFChars(s, nullptr) : nullptr;
    if (c != nullptr) { MikasaPushFunctions(c); env->ReleaseStringUTFChars(s, c); }
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativePushXrStatus(JNIEnv* env, jobject, jstring s) {
    const char* c = (s != nullptr) ? env->GetStringUTFChars(s, nullptr) : nullptr;
    if (c != nullptr) { MikasaPushStatus(c); env->ReleaseStringUTFChars(s, c); }
}

// 小染：Kotlin 异步结果 / 服务状态 推回 C++
JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrCardResult(JNIEnv* env, jobject, jboolean ok, jstring msg) {
    const char* m = (msg != nullptr) ? env->GetStringUTFChars(msg, nullptr) : nullptr;
    XNotifyCardResult(ok == JNI_TRUE, m ? m : "");
    if (m) env->ReleaseStringUTFChars(msg, m);
}
JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrShizukuResult(JNIEnv* env, jobject, jboolean ok, jstring msg) {
    const char* m = (msg != nullptr) ? env->GetStringUTFChars(msg, nullptr) : nullptr;
    XNotifyShizukuResult(ok == JNI_TRUE, m ? m : "");
    if (m) env->ReleaseStringUTFChars(msg, m);
}
JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrService(JNIEnv* env, jobject,
        jboolean disabled, jstring announce, jboolean force, jstring minv, jstring url) {
    auto s = [&](jstring j) -> const char* { return j ? env->GetStringUTFChars(j, nullptr) : nullptr; };
    const char* a = s(announce); const char* mn = s(minv); const char* u = s(url);
    XNotifyService(disabled == JNI_TRUE, a ? a : "", force == JNI_TRUE, mn ? mn : "", u ? u : "");
    if (a) env->ReleaseStringUTFChars(announce, a);
    if (mn) env->ReleaseStringUTFChars(minv, mn);
    if (u) env->ReleaseStringUTFChars(url, u);
}
JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrPreFillCard(JNIEnv* env, jobject, jstring key) {
    const char* c = (key != nullptr) ? env->GetStringUTFChars(key, nullptr) : nullptr;
    if (c) { XPreFillCard(c); env->ReleaseStringUTFChars(key, c); }
}

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrResetGate(JNIEnv*, jobject) { XResetGate(); }

JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrPushMedia(JNIEnv* env, jobject, jstring files, jstring music) {
    const char* f = files ? env->GetStringUTFChars(files, nullptr) : nullptr;
    const char* m = music ? env->GetStringUTFChars(music, nullptr) : nullptr;
    XrPushMedia(f, m);
    if (f) env->ReleaseStringUTFChars(files, f);
    if (m) env->ReleaseStringUTFChars(music, m);
}
JNIEXPORT void JNICALL
Java_com_xiaoran_nb_imgui_ImguiHost_nativeXrDownloadProgress(JNIEnv* env, jobject, jstring name, jfloat pct, jboolean done) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    XrSetDownloadProgress(n ? n : "", pct, done == JNI_TRUE);
    if (n) env->ReleaseStringUTFChars(name, n);
}

} // extern "C"

// C++ -> Java：勾选/开关/折叠变更 → host.mikasaToggle(name, checked)
void MikasaNotifyToggle(const char* name, bool checked) {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jclass  cls = env->GetObjectClass(g_activityRef);
    jmethodID mid = env->GetMethodID(cls, "mikasaToggle", "(Ljava/lang/String;Z)V");
    jstring jname = env->NewStringUTF(name ? name : "");
    env->CallVoidMethod(g_activityRef, mid, jname, checked ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_jvm->DetachCurrentThread();
}

// ---- 小染：C++ → Java 宿主（走 g_activityRef）----
static void CallHost(JNIEnv* env, jclass cls, const char* mname, const char* sig) {
    jmethodID mid = env->GetMethodID(cls, mname, sig);
    if (mid != nullptr) env->CallVoidMethod(g_activityRef, mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
}
static void CallHostStr(JNIEnv* env, jclass cls, const char* mname, const char* str) {
    jmethodID mid = env->GetMethodID(cls, mname, "(Ljava/lang/String;)V");
    if (mid != nullptr) env->CallVoidMethod(g_activityRef, mid, env->NewStringUTF(str ? str : ""));
    if (env->ExceptionCheck()) env->ExceptionClear();
}
void XHostCardVerify(const char* key) {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHostStr(env, env->GetObjectClass(g_activityRef), "cardVerify", key);
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostShizukuCheck() {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHost(env, env->GetObjectClass(g_activityRef), "shizukuCheck", "()V");
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostOpenShizuku() {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHost(env, env->GetObjectClass(g_activityRef), "openShizuku", "()V");
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostOpenUrl(const char* url) {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHostStr(env, env->GetObjectClass(g_activityRef), "openUrl", url);
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostDownload(const char* name, const char* url) {
    // 一次两个参数 (name, url)：拼成 "name\turl" 交给宿主 download()
    char buf[448]; snprintf(buf, sizeof(buf), "%s\t%s", name ? name : "", url ? url : "");
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHostStr(env, env->GetObjectClass(g_activityRef), "download", buf);
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostPlayMusic(const char* url) {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHostStr(env, env->GetObjectClass(g_activityRef), "playMusic", url);
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostStopMusic() {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    CallHost(env, env->GetObjectClass(g_activityRef), "stopMusic", "()V");
    if (attached) g_jvm->DetachCurrentThread();
}
void XHostVideoBg(int on) {
    if (g_jvm == nullptr || g_activityRef == nullptr) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (g_jvm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return; attached = true;
    }
    jmethodID mid = env->GetMethodID(env->GetObjectClass(g_activityRef), "videoBg", "(I)V");
    if (mid != nullptr) env->CallVoidMethod(g_activityRef, mid, on);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_jvm->DetachCurrentThread();
}
