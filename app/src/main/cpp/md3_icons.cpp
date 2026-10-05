// md3_icons.cpp - Hand-drawn MD3 style vector icons
#include "md3_common.h"

void DrawIconHome(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    dl->AddLine(ImVec2(c.x - r, c.y), ImVec2(c.x, c.y - r), col, t);
    dl->AddLine(ImVec2(c.x, c.y - r), ImVec2(c.x + r, c.y), col, t);
    dl->AddRect(ImVec2(c.x - r * 0.72f, c.y), ImVec2(c.x + r * 0.72f, c.y + r), col, r * 0.14f, 0, t);
    ImVec2 doorMin(c.x - r * 0.24f, c.y + r * 0.28f);
    ImVec2 doorMax(c.x + r * 0.24f, c.y + r);
    if (selected) dl->AddRectFilled(doorMin, doorMax, col, r * 0.1f);
    else          dl->AddRect(doorMin, doorMax, col, r * 0.1f, 0, t);
}

void DrawIconTarget(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    dl->AddCircle(c, r, col, 32, t);
    dl->AddCircle(c, r * 0.58f, col, 24, t);
    dl->AddCircleFilled(c, r * 0.24f, col, 16);
    ImVec2 arrowTip(c.x + r * 0.14f, c.y - r * 0.14f);
    ImVec2 arrowTail(c.x + r * 1.15f, c.y - r * 1.15f);
    dl->AddLine(arrowTail, arrowTip, col, t);
    float fw = r * 0.22f;
    dl->AddLine(arrowTail, ImVec2(arrowTail.x - fw, arrowTail.y), col, t);
    dl->AddLine(arrowTail, ImVec2(arrowTail.x, arrowTail.y + fw), col, t);
    if (selected) dl->AddCircleFilled(ImVec2(c.x + r * 0.5f, c.y - r * 0.5f), r * 0.08f, col, 8);
}

void DrawIconGlobe(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    dl->AddCircle(c, r, col, 40, t);
    dl->AddEllipse(c, ImVec2(r * 0.42f, r), col, 0.0f, 32, t);
    dl->PathClear();
    dl->PathArcTo(c, r * 0.98f, -2.6f, -0.54f, 24);
    dl->PathStroke(col, 0, t);
    dl->PathClear();
    dl->PathArcTo(c, r * 0.98f, 0.54f, 2.6f, 24);
    dl->PathStroke(col, 0, t);
    dl->AddLine(ImVec2(c.x - r, c.y), ImVec2(c.x + r, c.y), col, t);
    if (selected) dl->AddCircleFilled(c, r * 0.1f, col, 10);
}

void DrawIconTerminal(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    ImVec2 wmin(c.x - r, c.y - r * 0.8f);
    ImVec2 wmax(c.x + r, c.y + r * 0.8f);
    dl->AddRect(wmin, wmax, col, r * 0.14f, 0, t);
    dl->AddLine(ImVec2(wmin.x, wmin.y + r * 0.32f), ImVec2(wmax.x, wmin.y + r * 0.32f), col, t);
    float px = c.x - r * 0.62f, py = c.y + r * 0.12f;
    dl->AddLine(ImVec2(px, py - r * 0.24f), ImVec2(px + r * 0.3f, py), col, t);
    dl->AddLine(ImVec2(px + r * 0.3f, py), ImVec2(px, py + r * 0.24f), col, t);
    ImVec2 curMin(px + r * 0.44f, py + r * 0.14f);
    ImVec2 curMax(px + r * 0.86f, py + r * 0.28f);
    if (selected) dl->AddRectFilled(curMin, curMax, col, r * 0.04f);
    else          dl->AddRect(curMin, curMax, col, r * 0.04f, 0, t);
}

// 小染：合并悬浮窗入口图标——带 "M" 的圆徽，选定时圆心加填点
void DrawIconMikasa(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    dl->AddCircle(c, r, col, 40, t);
    float lx = c.x - r * 0.5f, rx = c.x + r * 0.5f;
    float ty = c.y - r * 0.55f, by = c.y + r * 0.55f;
    float midY = c.y + r * 0.12f;
    dl->AddLine(ImVec2(lx, by), ImVec2(lx, ty), col, t * 1.2f);
    dl->AddLine(ImVec2(rx, by), ImVec2(rx, ty), col, t * 1.2f);
    dl->AddLine(ImVec2(lx, ty), ImVec2(c.x, midY), col, t * 1.2f);
    dl->AddLine(ImVec2(c.x, midY), ImVec2(rx, ty), col, t * 1.2f);
    if (selected) dl->AddCircleFilled(c, r * 0.10f, col, 10);
}

// 小染：文件/音乐 图标——文件夹 + 音符
void DrawIconFiles(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    float lw = r * 0.9f;
    ImVec2 top(c.x - lw * 0.5f, c.y - r * 0.45f);
    dl->AddLine(top, ImVec2(top.x + lw * 0.4f, top.y), col, t);
    dl->AddLine(ImVec2(top.x + lw * 0.4f, top.y), ImVec2(top.x + lw * 0.4f, top.y + r * 0.25f), col, t);
    dl->AddRect(ImVec2(top.x, top.y + r * 0.25f), ImVec2(top.x + lw, top.y + r * 0.25f + r * 0.6f), col, r * 0.14f, 0, t);
    float nx = c.x + r * 0.35f, ny = c.y + r * 0.35f;
    dl->AddCircleFilled(ImVec2(nx, ny), r * 0.14f, col, 12);
    dl->AddLine(ImVec2(nx + r * 0.14f, ny), ImVec2(nx + r * 0.14f, ny - r * 0.6f), col, t * 0.8f);
    if (selected) dl->AddCircleFilled(ImVec2(c.x - r * 0.6f, c.y + r * 0.5f), r * 0.06f, col, 8);
}

void DrawIconSettings(ImDrawList* dl, ImVec2 c, float r, ImU32 col, bool selected) {
    float t = r * 0.16f;
    const int teeth = 8;
    dl->AddCircle(c, r * 0.62f, col, 32, t);
    for (int i = 0; i < teeth; i++) {
        float a = (float)i / teeth * 6.2831853f;
        ImVec2 p1(c.x + cosf(a) * r * 0.62f, c.y + sinf(a) * r * 0.62f);
        ImVec2 p2(c.x + cosf(a) * r * 0.95f, c.y + sinf(a) * r * 0.95f);
        dl->AddLine(p1, p2, col, t * 1.3f);
    }
    if (selected) dl->AddCircleFilled(c, r * 0.28f, col, 20);
    else          dl->AddCircle(c, r * 0.28f, col, 20, t);
}
