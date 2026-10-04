// mikasa_page.cpp - 小染集成：NAV_MIKASA 页
// 承载原悬浮窗的功能列表（折叠组/勾选/开关/子项）+ 灵动岛状态条。
// 数据由 Kotlin 经 JNI 灌入：
//   * nativePushXrFunctions -> MikasaPushFunctions（行协议，非 JSON）
//   * nativePushXrStatus    -> MikasaPushStatus
// 勾选/开关变更 -> MikasaNotifyToggle -> Java host.mikasaToggle(name, checked)（保持原 toast 行为）
#include "md3_common.h"
#include <vector>
#include <string>
#include <cstring>
#include <cstdio>
#include <cstdlib>

static const int MI_MAX_NAME = 48;

struct MiItem {
    char  name[MI_MAX_NAME] = {0};
    int   type = 0;          // 0 ITEM / 1 GROUP / 2 GROUP_CHILD / 3 SWITCH
    bool  on = false;
    bool  expanded = false;  // 仅 GROUP 用
};
struct MiTab  { char label[MI_MAX_NAME] = {0}; std::vector<MiItem> items; };
struct MiNav  { char label[MI_MAX_NAME] = {0}; std::vector<MiTab> tabs; int curTab = 0; };

struct MiStatus {
    char time[16] = {0}; char fps[16] = {0}; char temp[24] = {0};
    char battery[16] = {0}; char song[96] = {0};
};

static std::vector<MiNav> g_miNavs;
static int g_miCurNav = 0;
static MiStatus g_miStatus;

static void ClipName(char* dst, size_t cap, const char* src) {
    size_t n = 0;
    while (src[n] && n + 1 < cap) { dst[n] = src[n]; n++; }
    dst[n] = 0;
}

// 行协议（\n 分隔）：
//   N <navLabel>            进入新导航页
//   T <tabLabel>            当前导航下新 Tab
//   I <name> <0|1>          ITEM（勾选）
//   G <name> <0|1>          GROUP（折叠组标题，0|1=是否展开）
//   C <name> <0|1>          GROUP_CHILD（子项勾选）
//   S <name> <0|1>          SWITCH（开关）
void MikasaPushFunctions(const char* text) {
    g_miNavs.clear();
    if (text == nullptr || *text == 0) return;

    char line[256]; size_t li = 0;
    const char* p = text;
    auto flush = [&]() {
        line[li] = 0;
        if (li == 0) return;
        char tag = line[0];
        const char* body = line + 1;
        while (*body == ' ') body++;
        int flags = 0;
        char nameBuf[MI_MAX_NAME] = {0};
        const char* lastSp = nullptr;
        for (const char* s = body; *s; s++) if (*s == ' ') lastSp = s;
        if (lastSp && lastSp[1]) {                       // 有 " 数字" 尾标
            size_t nl = (size_t)(lastSp - body);
            memcpy(nameBuf, body, nl < MI_MAX_NAME ? nl : MI_MAX_NAME - 1);
            nameBuf[nl < MI_MAX_NAME ? nl : MI_MAX_NAME - 1] = 0;
            flags = (lastSp[1] == '1') ? 1 : 0;
        } else {
            ClipName(nameBuf, MI_MAX_NAME, body);
        }
        switch (tag) {
        case 'N': {
            char nm[MI_MAX_NAME]; ClipName(nm, MI_MAX_NAME, nameBuf);
            g_miNavs.push_back(MiNav());
            g_miNavs.back().label = nm;
            g_miNavs.back().tabs.push_back(MiTab());
            break;
        }
        case 'T': {
            if (g_miNavs.empty()) break;
            char nm[MI_MAX_NAME]; ClipName(nm, MI_MAX_NAME, nameBuf);
            g_miNavs.back().tabs.push_back(MiTab());
            g_miNavs.back().tabs.back().label = nm;
            break;
        }
        case 'I': case 'G': case 'C': case 'S': {
            if (g_miNavs.empty() || g_miNavs.back().tabs.empty()) break;
            MiItem it;
            ClipName(it.name, MI_MAX_NAME, nameBuf);
            it.type = (tag == 'I') ? 0 : (tag == 'G') ? 1 : (tag == 'C') ? 2 : 3;
            it.on = (flags != 0);
            it.expanded = (flags != 0);
            g_miNavs.back().tabs.back().items.push_back(it);
            break;
        }
        default: break;
        }
        li = 0;
    };

    for (; *p; p++) {
        char ch = *p;
        if (ch == '\n') { flush(); continue; }
        if (li + 1 < sizeof(line)) line[li++] = ch;
    }
    flush();

    if (g_miCurNav >= (int)g_miNavs.size()) g_miCurNav = 0;
    if (!g_miNavs.empty()) {
        MiNav& n = g_miNavs[g_miCurNav];
        if (n.curTab >= (int)n.tabs.size()) n.curTab = 0;
    }
}

// 状态行协议：`time 12:34` / `fps 60` / `temp 36.5℃` / `battery 80%` / `song 歌名`
void MikasaPushStatus(const char* text) {
    if (text == nullptr || *text == 0) return;
    const char* p = text;
    while (*p) {
        const char* nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) : strlen(p);
        if (len >= 5) {
            char key[16] = {0}; size_t kl = 0;
            while (kl < len && p[kl] != ' ' && kl < 15) key[kl++] = p[kl];
            const char* val = (kl + 1 <= len) ? p + kl + 1 : "";
            if      (strcmp(key, "time") == 0)      ClipName(g_miStatus.time,    sizeof(g_miStatus.time),    val);
            else if (strcmp(key, "fps") == 0)       ClipName(g_miStatus.fps,     sizeof(g_miStatus.fps),     val);
            else if (strcmp(key, "temp") == 0)      ClipName(g_miStatus.temp,    sizeof(g_miStatus.temp),    val);
            else if (strcmp(key, "battery") == 0)   ClipName(g_miStatus.battery, sizeof(g_miStatus.battery), val);
            else if (strcmp(key, "song") == 0)      ClipName(g_miStatus.song,    sizeof(g_miStatus.song),    val);
        }
        if (!nl) break;
        p = nl + 1;
    }
}

static void RenderMiStatusStrip() {
    const Md3Palette& P = CurPalette();
    std::string s;
    s += g_miStatus.time[0] ? g_miStatus.time : "--";
    if (g_miStatus.fps[0])     { s += "   ·   "; s += g_miStatus.fps;     s += " fps"; }
    if (g_miStatus.temp[0])    { s += "   ·   "; s += g_miStatus.temp; }
    if (g_miStatus.battery[0]) { s += "   ·   "; s += g_miStatus.battery; }
    if (g_miStatus.song[0])    { s += "   ·   "; s += g_miStatus.song; }
    ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.OnSurfaceVariant));
    ImGui::TextUnformatted(s.c_str());
    ImGui::PopStyleColor();
}

static void RenderMiItems(std::vector<MiItem>& items) {
    bool groupOpen = false;
    for (size_t i = 0; i < items.size(); i++) {
        MiItem& it = items[i];
        switch (it.type) {
        case 1: {  // GROUP
            char lbl[MI_MAX_NAME + 4];
            snprintf(lbl, sizeof(lbl), "%s  %s", it.expanded ? "▾" : "▸", it.name);
            if (Md3Button(lbl, ImVec2(0, 40))) { it.expanded = !it.expanded; MikasaNotifyToggle(it.name, it.expanded); }
            groupOpen = it.expanded;
            break;
        }
        case 2: {  // GROUP_CHILD
            if (!groupOpen) break;
            ImGui::Indent(16.0f);
            if (Md3Checkbox(it.name, &it.on)) MikasaNotifyToggle(it.name, it.on);
            ImGui::Unindent(16.0f);
            break;
        }
        case 3: {  // SWITCH
            char lbl[MI_MAX_NAME + 12];
            snprintf(lbl, sizeof(lbl), "%s · %s", it.on ? "开" : "关", it.name);
            if (Md3Button(lbl, ImVec2(0, 40))) { it.on = !it.on; MikasaNotifyToggle(it.name, it.on); }
            break;
        }
        default: {  // ITEM
            if (Md3Checkbox(it.name, &it.on)) MikasaNotifyToggle(it.name, it.on);
        }
        }
        ImGui::Dummy(ImVec2(0, 4.0f));
    }
}

void PageMikasaFunctions() {
    PageHeader(TL("小染自动注入 · 悬浮窗功能", "Xiaoran Auto-Inject · Floating Functions"),
               TL("合并自悬浮球 / 功能面板 / 灵动岛", "merged: ball / panel / island"));
    RenderMiStatusStrip();
    ImGui::Spacing();

    static int s_videoBg = 0;
    char vlbl[32];
    snprintf(vlbl, sizeof(vlbl), s_videoBg ? "关闭视频背景" : "开启视频背景");
    if (Md3Button(vlbl, ImVec2(0, 40))) { s_videoBg = !s_videoBg; XHostVideoBg(s_videoBg); }

    if (g_miNavs.empty()) {
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(CurPalette().OnSurfaceVariant));
        ImGui::TextUnformatted(TL("（等待推送功能列表…）", "(waiting for the function list…)"));
        ImGui::PopStyleColor();
        return;
    }

    std::vector<const char*> navLabels;
    for (auto& n : g_miNavs) navLabels.push_back(n.label);
    Md3SubNav("##mi_navs", navLabels.data(), (int)navLabels.size(), &g_miCurNav);
    ImGui::Spacing();

    MiNav& nav = g_miNavs[g_miCurNav];
    std::vector<const char*> tabLabels;
    for (auto& t : nav.tabs) tabLabels.push_back(t.label);
    Md3SubNav("##mi_tabs", tabLabels.data(), (int)tabLabels.size(), &nav.curTab);
    ImGui::Spacing();

    MiTab& tab = nav.tabs[nav.curTab];
    if (tab.items.empty()) {
        ImGui::TextUnformatted(TL("（该页暂无功能）", "(no functions on this tab)"));
        return;
    }
    ImGui::BeginChild("##mi_list", ImVec2(0, 0), ImGuiChildFlags_None,
                      ImGuiWindowFlags_AlwaysVerticalScrollbar);
    RenderMiItems(tab.items);
    ImGui::EndChild();
}
