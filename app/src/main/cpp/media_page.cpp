// media_page.cpp - 小染：文件/音乐页（列表 + 下载进度条 + 播放）
#include "md3_common.h"
#include <cstring>
#include <cstdio>

struct MediaItem { char name[96]; char url[320]; float pct; bool done; };
static MediaItem g_files[96]; static int g_fileN = 0;
static MediaItem g_music[96]; static int g_musicN = 0;
static int s_tab = 0;   // 0=文件 1=音乐

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
        Md3Card("##mitem", ImVec4(P.SurfaceContainerHigh.x, P.SurfaceContainerHigh.y, P.SurfaceContainerHigh.z, 1));
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

void PageFilesMusic() {
    PageHeader(TL("文件 / 音乐", "Files / Music"),
               TL("来自后端的下载与在线播放", "server files & online music"));
    const char* items[2] = { TL("文件", "Files"), TL("音乐", "Music") };
    Md3SubNav("##fm", items, 2, &s_tab);
    ImGui::Spacing();
    if (s_tab == 0) RenderList(g_files, g_fileN, false);
    else            RenderList(g_music, g_musicN, true);
}
