// launch_gate.cpp - 小染：启动页(逐行日志文字动画) + 卡密/Shizuku 门禁 + 服务停用 + 强制更新
// "驱动刷入成功"等只是文字效果，不刷任何真实驱动。Shizuku/卡密结果由 Kotlin 经 JNI 推回。
#include "md3_common.h"
#include <cstring>
#include <cstdio>

XPhase g_xPhase = XPhase::LAUNCH;
bool   g_xServiceDisabled = false;
char   g_xAnnounce[256] = {0};
bool   g_xUpdateForce = false;
char   g_xUpdateMin[32] = {0};
char   g_xUpdateUrl[256] = {0};

// 运行时状态（会话内）
static char g_xCardInput[64] = {0};
static char g_xLastCard[64]  = {0};
static bool g_xCardBusy = false;
static char g_xCardMsg[128] = {0};
static bool g_xCardOk = false;
static bool g_xShizukuOk = false;
static char g_xShizukuMsg[128] = {0};
static float g_xLaunchT = 0.0f;

// 启动页逐行日志（纯文字效果）
static const char* kLaunchLines[] = {
    "下方出现 Invalid argument 请再试一次",
    "OPPO / Realme / 一加 需要过签名验证 + 升级到安卓 13",
    "开机一段时间后可能刷不进，自动重启后再刷一遍即可",
    "正在检测是否已经刷入过一次...",
    "驱动刷入成功!", "驱动刷入成功!", "驱动刷入成功!", "驱动刷入成功!",
    "驱动刷入完成!",
    "启动完成 稳定奔放 [进程已结束请返回]",
    "正在尝试授权 Shizuku (请确保已激活)",
};
static const int kLaunchCount = (int)(sizeof(kLaunchLines) / sizeof(kLaunchLines[0]));

void XSetPhase(XPhase p) { g_xPhase = p; }
void XResetGate() {
    g_xPhase = XPhase::LAUNCH;
    g_xCardOk = false; g_xCardMsg[0] = 0; g_xShizukuMsg[0] = 0;
    g_xLaunchT = 0.0f;
}
void XPreFillCard(const char* key) {
    if (key == nullptr) return;
    size_t n = 0; while (key[n] && n + 1 < sizeof(g_xLastCard)) { g_xLastCard[n] = key[n]; n++; }
    g_xLastCard[n] = 0;
    if (g_xCardInput[0] == 0) strncpy(g_xCardInput, g_xLastCard, sizeof(g_xCardInput) - 1);
}

void XNotifyCardResult(bool ok, const char* msg) {
    g_xCardBusy = false; g_xCardOk = ok;
    if (msg) snprintf(g_xCardMsg, sizeof(g_xCardMsg), "%s", msg);
    if (ok && g_xShizukuOk) XSetPhase(XPhase::MAIN);   // 卡密 + Shizuku 都过才进
}
void XNotifyShizukuResult(bool ok, const char* msg) {
    g_xShizukuOk = ok;
    if (msg) snprintf(g_xShizukuMsg, sizeof(g_xShizukuMsg), "%s", msg);
    if (ok && g_xCardOk) XSetPhase(XPhase::MAIN);
}
void XNotifyService(bool disabled, const char* announce, bool force, const char* minVersion, const char* url) {
    g_xServiceDisabled = disabled;
    if (announce) snprintf(g_xAnnounce, sizeof(g_xAnnounce), "%s", announce);
    g_xUpdateForce = force;
    if (minVersion) snprintf(g_xUpdateMin, sizeof(g_xUpdateMin), "%s", minVersion);
    if (url) snprintf(g_xUpdateUrl, sizeof(g_xUpdateUrl), "%s", url);
}

bool XGateActive() {
    return g_xServiceDisabled || (g_xPhase != XPhase::MAIN);
}

static void DrawLaunchLog() {
    const Md3Palette& P = CurPalette();
    float dt = ImGui::GetIO().DeltaTime;
    g_xLaunchT += dt;
    int shown = (int)(g_xLaunchT / 0.30f);           // 每 0.3s 出一行
    if (shown > kLaunchCount) shown = kLaunchCount;
    // 显示最后最多 6 行（滚动日志感）
    int from = shown > 6 ? shown - 6 : 0;
    ImGui::Spacing();
    for (int i = from; i < shown; i++) {
        bool isDrive = (strcmp(kLaunchLines[i], "驱动刷入成功!") == 0) ||
                       (strcmp(kLaunchLines[i], "驱动刷入完成!") == 0);
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(isDrive ? P.Primary : P.OnSurfaceVariant));
        ImGui::TextUnformatted(kLaunchLines[i]);
        ImGui::PopStyleColor();
    }
    // 全部播完 + 暂停 0.8s → 进卡密页（也可点跳过）
    if (shown >= kLaunchCount && g_xLaunchT > kLaunchCount * 0.30f + 0.8f) XSetPhase(XPhase::CARD);
    if (Md3Button(TL("跳过 → 输入卡密", "Skip → Card Key"), ImVec2(220, 44))) XSetPhase(XPhase::CARD);
}

static void DrawServiceDisabled() {
    const Md3Palette& P = CurPalette();
    ImGui::Spacing(); ImGui::Spacing();
    ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.Primary));
    ImGui::TextUnformatted(TL("服务已停用", "Service disabled"));
    ImGui::PopStyleColor();
    ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.OnSurfaceVariant));
    ImGui::TextUnformatted(TL("（管理员已关闭，暂时无法使用）", "(disabled by admin, unavailable)"));
    if (g_xAnnounce[0]) ImGui::TextUnformatted(g_xAnnounce);
    if (g_xUpdateUrl[0]) {
        if (Md3Button(TL("打开更新/说明", "Open update/info"), ImVec2(220, 44))) XHostOpenUrl(g_xUpdateUrl);
    }
    ImGui::PopStyleColor();
}

static void DrawCardGate() {
    const Md3Palette& P = CurPalette();
    static bool s_chk = false;
    if (!s_chk) { s_chk = true; if (!g_xShizukuOk) XHostShizukuCheck(); }  // 进卡密页自动检测一次
    ImGui::Spacing();
    // Shizuku 真实检测门禁
    ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(g_xShizukuOk ? P.Primary : P.OnSurfaceVariant));
    ImGui::TextUnformatted(g_xShizukuOk
        ? TL("✔ Shizuku 权限已就绪", "Shizuku ready")
        : TL("正在检测 Shizuku 权限…", "checking Shizuku…"));
    if (g_xShizukuMsg[0]) ImGui::TextUnformatted(g_xShizukuMsg);
    ImGui::PopStyleColor();
    if (!g_xShizukuOk) {
        if (Md3Button(TL("去授权 Shizuku", "Grant Shizuku"), ImVec2(200, 44))) XHostOpenShizuku();
        ImGui::SameLine();
        if (Md3Button(TL("重新检测", "Re-check"), ImVec2(140, 44))) XHostShizukuCheck();
    }

    // 卡密输入（每次进都要输，保留上次输入的卡密）
    ImGui::Spacing();
    ImGui::TextUnformatted(TL("输入卡密进入功能", "Enter card key to continue"));
    char label[96];
    snprintf(label, sizeof(label), "%s", g_xCardBusy ? TL("验证中…", "verifying…") : TL("卡密", "Card Key"));
    if (ImGui::InputText(label, g_xCardInput, sizeof(g_xCardInput))) {
        g_xCardMsg[0] = 0; g_xCardOk = false;
    }
    if (Md3Button(TL("验证并进入", "Verify & Enter"), ImVec2(0, 46))) {
        if (g_xCardInput[0] == 0) { snprintf(g_xCardMsg, sizeof(g_xCardMsg), "请输入卡密"); return; }
        g_xCardBusy = true; snprintf(g_xCardMsg, sizeof(g_xCardMsg), "正在验证…");
        XHostCardVerify(g_xCardInput);
    }
    if (g_xCardMsg[0]) {
        bool ok = g_xCardOk;
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(ok ? P.Primary : P.Tertiary));
        ImGui::TextUnformatted(g_xCardMsg);
        ImGui::PopStyleColor();
    }
    // 强制更新提示
    if (g_xUpdateForce && g_xUpdateUrl[0]) {
        ImGui::Spacing();
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.Tertiary));
        ImGui::TextUnformatted(TL("检测到需要更新的版本", "a required update is available"));
        if (Md3Button(TL("立即更新", "Update now"), ImVec2(0, 44))) XHostOpenUrl(g_xUpdateUrl);
        ImGui::PopStyleColor();
    }
}

void XDrawGate() {
    // 公告条（若有）
    if (g_xAnnounce[0] && g_xPhase != XPhase::LAUNCH && !g_xServiceDisabled) {
        ImGui::PushStyleColor(ImGuiCol_ChildBg, Md3U32(CurPalette().PrimaryContainer));
        ImGui::BeginChild("##announce", ImVec2(0, 34), ImGuiChildFlags_None,
                          ImGuiWindowFlags_NoScrollbar);
        ImGui::TextUnformatted(g_xAnnounce);
        ImGui::EndChild();
        ImGui::PopStyleColor();
    }
    if (g_xServiceDisabled) { DrawServiceDisabled(); return; }
    switch (g_xPhase) {
    case XPhase::LAUNCH: DrawLaunchLog(); break;
    case XPhase::CARD:   DrawCardGate();  break;
    default: break;
    }
}
