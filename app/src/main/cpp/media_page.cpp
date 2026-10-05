// media_page.cpp - 小染：文件 / 音乐 / 客服页（列表 + 下载进度 + 播放 + 客服 Q&A + 转人工 + 在线人数）
#include "md3_common.h"
#include <cstring>
#include <cstdio>

struct MediaItem { char name[96]; char url[320]; float pct; bool done; };
static MediaItem g_files[96]; static int g_fileN = 0;
static MediaItem g_music[96]; static int g_musicN = 0;
static int s_tab = 0;   // 0=文件 1=音乐 2=客服

// —— 在线 / 使用人数（Kotlin 灌入）——
static int g_statsOnline = 0, g_statsTotal = 0;
void XrPushStats(int online, int total) { g_statsOnline = online; g_statsTotal = total; }

// —— 客服 Q&A ——
struct CsItem { char q[128]; char a[320]; };
static CsItem g_cs[128]; static int g_csN = 0;
static char g_csMsg[512] = {0};
static char g_csSent[64] = {0};
void XrPushCs(const char* csv) {
    g_csN = 0;
    if (csv == nullptr) return;
    char buf[8192]; strncpy(buf, csv, sizeof(buf) - 1); buf[sizeof(buf) - 1] = 0;
    char* line = buf;
    while (*line && g_csN < 128) {
        char* nl = strchr(line, '\n'); if (nl) *nl = 0; else nl = line + strlen(line);
        char* tab = strchr(line, '\t');
        char* q = line; char* a = "";
        if (tab && tab > line) { *tab = 0; q = line; a = tab + 1; }
        if (q[0]) {
            CsItem it;
            strncpy(it.q, q, sizeof(it.q) - 1); it.q[sizeof(it.q) - 1] = 0;
            strncpy(it.a, a, sizeof(it.a) - 1); it.a[sizeof(it.a) - 1] = 0;
            g_cs[g_csN++] = it;
        }
        if (!nl || nl == line) break;
        line = nl + 1;
    }
}

static void LoadCsv(const char* csv, MediaItem* arr, int& n, int cap) {
    n = 0;
    if (csv == nullptr) return;
    char buf[8192]; strncpy(buf, csv, sizeof(buf) - 1); buf[sizeof(buf)-1] = 0;
    char* line = buf;
    while (*line && n < cap) {
        char* nl = strchr(line, '\n');
        if (nl) *nl = 0; else nl = line + strlen(line);
        char* tab = strchr(line, '\t');
        if (tab && tab > line && (tab - line) < 95 && (nl - tab) > 0) {
            *tab = 0;
            MediaItem it; it.name[0] = 0; it.pct = 0; it.done = false;
            strncpy(it.name, line, 95);
            strncpy(it.url, tab + 1, sizeof(it.url) - 1); it.url[319] = 0;
            arr[n++] = it;
        }
        if (!nl || nl == line) break;
        line = nl + 1;
    }
}

void XrPushMedia(const char* filesCsv, const char* musicCsv) {
    LoadCsv(filesCsv, g_files, g_fileN, 96);
    LoadCsv(musicCsv, g_music, g_musicN, 96);
}

void XrSetDownloadProgress(const char* name, float pct, bool done) {
    for (int i = 0; i < g_fileN; i++) {
        if (strcmp(g_files[i].name, name) == 0) {
            g_files[i].pct = pct; g_files[i].done = done;
            return;
        }
    }
}

static void RenderList(MediaItem* arr, int n, bool isMusic) {
    const Md3Palette& P = CurPalette();
    if (n == 0) {
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.OnSurfaceVariant));
        ImGui::TextUnformatted(isMusic ? TL("（后端暂无音乐）", "(no music on server)")
                                       : TL("（后端暂无文件）", "(no files on server)"));
        ImGui::PopStyleColor();
        return;
    }
    for (int i = 0; i < n; i++) {
        MediaItem& it = arr[i];
        Md3Card("##mitem", P.SurfaceContainerHigh);
        ImGui::Text("%s", it.name);
        if (isMusic) {
            if (Md3Button("播放", ImVec2(0, 40))) XHostPlayMusic(it.url);
            ImGui::SameLine();
            if (Md3Button("停止", ImVec2(0, 40))) XHostStopMusic();
        } else {
            if (it.done) {
                ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.Primary));
                ImGui::TextUnformatted(TL("✔ 已下载", "downloaded"));
                ImGui::PopStyleColor();
            } else if (it.pct > 0) {
                char buf[32]; snprintf(buf, sizeof(buf), "%.0f%%", it.pct * 100);
                ImGui::ProgressBar(it.pct, ImVec2(0, 6.0f), buf);
            } else if (Md3Button("下载", ImVec2(0, 40))) {
                XHostDownload(it.name, it.url);
            }
        }
        Md3CardEnd();
    }
}

static void RenderCs() {
    const Md3Palette& P = CurPalette();
    if (g_csN == 0) {
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.OnSurfaceVariant));
        ImGui::TextUnformatted(TL("（后端暂无客服问答）", "no Q&A yet on server"));
        ImGui::PopStyleColor();
    }
    for (int i = 0; i < g_csN; i++) {
        Md3Card("##cs", P.SurfaceContainerHigh);
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.Primary));
        ImGui::SetWindowFontScale(0.9f);
        ImGui::TextUnformatted("Q:");
        ImGui::SetWindowFontScale(1.0f);
        ImGui::PopStyleColor();
        ImGui::SameLine();
        ImGui::TextWrapped("%s", g_cs[i].q);
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.OnSurface));
        ImGui::TextWrapped("%s", g_cs[i].a);
        ImGui::PopStyleColor();
        Md3CardEnd();
    }
    // 转人工 / 留言
    ImGui::Spacing();
    Md3Card("##cstx", P.SurfaceContainerHigh);
    ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.OnSurfaceVariant));
    ImGui::SetWindowFontScale(0.85f);
    ImGui::TextUnformatted(TL("转人工 / 留言（自动带当前卡密与设备）", "Send to support (auto card + device)"));
    ImGui::SetWindowFontScale(1.0f);
    ImGui::PopStyleColor();
    ImGui::InputText("##csmsg", g_csMsg, sizeof(g_csMsg));
    if (Md3Button(TL("发送", "Send"), ImVec2(0, 40))) {
        XHostCsMessage(g_csMsg[0] ? g_csMsg : "");
        g_csMsg[0] = 0;
        snprintf(g_csSent, sizeof(g_csSent), "%s", TL("已发送，等待回复", "sent, waiting"));
    }
    if (g_csSent[0]) {
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(P.Primary));
        ImGui::TextUnformatted(g_csSent);
        ImGui::PopStyleColor();
    }
    Md3CardEnd();
}

void PageFilesMusic() {
    PageHeader(TL("文件 / 音乐 / 客服", "Files · Music · Support"),
               TL("来自后端的下载、在线播放与客服", "server files, online music & support"));
    // 在线 / 使用人数（来自后端）
    if (g_statsOnline || g_statsTotal) {
        char b[80];
        snprintf(b, sizeof(b), "%s %d  ·  %s %d",
                 TL("在线", "online"), g_statsOnline,
                 TL("使用", "used"), g_statsTotal);
        ImGui::PushStyleColor(ImGuiCol_Text, Md3U32(CurPalette().OnSurfaceVariant));
        ImGui::SetWindowFontScale(0.85f);
        ImGui::TextUnformatted(b);
        ImGui::SetWindowFontScale(1.0f);
        ImGui::PopStyleColor();
    }
    const char* items[3] = { TL("文件", "Files"), TL("音乐", "Music"), TL("客服", "Support") };
    Md3SubNav("##fm", items, 3, &s_tab);
    ImGui::Spacing();
    if (s_tab == 0) RenderList(g_files, g_fileN, false);
    else if (s_tab == 1) RenderList(g_music, g_musicN, true);
    else RenderCs();
}
