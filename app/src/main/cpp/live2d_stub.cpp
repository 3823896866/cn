// live2d_stub.cpp —— 当缺少 aarch64 的 libLive2DCubismCore.a 时，CMake 用本文件顶替 Live2D，
// 使工程仍可编译（立绘显示"已精简"文案）。Core .a 就位后 CMake 会自动改用真实 Live2D。
#include "md3_common.h"
namespace Live2D {
    bool  Show() { return false; }
    void  SetShow(bool v) { (void)v; }
    bool  Ready() { return false; }
    float Scale() { return 1.0f; }
    void  SetScale(float v) { (void)v; }
    float HeadGain() { return 30.0f; }
    void  SetHeadGain(float v) { (void)v; }
    bool  GazeFollow() { return false; }
    void  SetGazeFollow(bool v) { (void)v; }
    bool  AutoBlink() { return true; }
    void  SetAutoBlink(bool v) { (void)v; }
    bool  Talking() { return false; }
    void  SetTalkManual(bool v) { (void)v; }
    bool  TalkManual() { return false; }
    void  SetTalking(bool v) { (void)v; }
    const char* StatusText() { return "立绘：未包含 Live2D Cubism（缺 Core 静态库），此区显示静态立绘图。"; }
    GLuint Tex() { return 0; }
    bool   BoundsOk() { return false; }
    void   BoundsNorm(float* x0, float* y0, float* x1, float* y1) { if (x0) *x0 = 0; if (y0) *y0 = 0; if (x1) *x1 = 1; if (y1) *y1 = 1; }
    void   LastCanvasRect(ImVec2* a, ImVec2* b) { if (a) *a = ImVec2(0, 0); if (b) *b = ImVec2(1, 1); }
    void   GazeAt(float nx, float ny) { (void)nx; (void)ny; }
    void  TriggerIdle() {}
    void  TriggerTap() {}
    void  TriggerExpression() {}
    void  SetThinking(bool v) { (void)v; }
    bool  Thinking() { return false; }
    void  OnFrame() {}
    void  OnContextLost() {}
    void  DrawStandPanel(float px, float py, float pw, float ph) { (void)px; (void)py; (void)pw; (void)ph; }
}
