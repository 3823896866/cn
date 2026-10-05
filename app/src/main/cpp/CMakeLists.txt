# 小染自动注入 —— imgui-md3 MD3 ImGui 悬浮窗引擎
# 自适应：
#   * 有 aarch64 的 Live2D Core 静态库 → 编真实 Live2D（3D 立绘完整显示）；否则用 live2d_stub（可编译、立绘显占位）
#   * 有 aarch64 的 freetype 静态库 → 启用 FreeType 中文字体；否则用 ImGui 内置光栅（仍可编译）
cmake_minimum_required(VERSION 3.22)
project("xiaoran_imgui" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ANDROID_STL c++_static CACHE STRING "" FORCE)

set(IMGUI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/imgui-1.91.8)
set(FREETYPE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/freetype/libfreetype.a)
set(FREETYPE_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/freetype/include)
set(CUBISM_CORE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/live2d/Core/lib/android/arm64-v8a/libLive2DCubismCore.a)

# ---- FreeType（可选）----
if(EXISTS ${FREETYPE_LIB})
    set(HAVE_FREETYPE ON)
    message(STATUS "[xiaoran] FreeType 静态库存在，启用中文字体")
else()
    set(HAVE_FREETYPE OFF)
    message(STATUS "[xiaoran] 未找到 freetype/libfreetype.a，用 ImGui 内置光栅字体")
endif()

# ---- Live2D（可选）----
if(EXISTS ${CUBISM_CORE_LIB})
    set(HAVE_LIVE2D ON)
    message(STATUS "[xiaoran] Live2D Core 静态库存在，编真实 3D 立绘")
else()
    set(HAVE_LIVE2D OFF)
    message(STATUS "[xiaoran] 未找到 Cubism Core 静态库，立绘用占位 stub")
endif()

add_library(imgui STATIC
    ${IMGUI_DIR}/imgui.cpp
    ${IMGUI_DIR}/imgui_draw.cpp
    ${IMGUI_DIR}/imgui_tables.cpp
    ${IMGUI_DIR}/imgui_widgets.cpp
    ${IMGUI_DIR}/imgui_demo.cpp
    ${IMGUI_DIR}/backends/imgui_impl_android.cpp
    ${IMGUI_DIR}/backends/imgui_impl_opengl3.cpp
)
target_include_directories(imgui PUBLIC ${IMGUI_DIR} ${IMGUI_DIR}/backends)
if(HAVE_FREETYPE)
    target_sources(imgui PRIVATE ${IMGUI_DIR}/misc/freetype/imgui_freetype.cpp)
    target_include_directories(imgui PUBLIC ${IMGUI_DIR}/misc/freetype ${FREETYPE_INCLUDE_DIR})
    target_compile_definitions(imgui PUBLIC IMGUI_ENABLE_FREETYPE)
endif()
target_compile_definitions(imgui PUBLIC IMGUI_IMPL_OPENGL_ES2)

add_library(imguidemo SHARED
    md3_jni.cpp
    mikasa_page.cpp
    media_page.cpp
    launch_gate.cpp
    md3_tts.cpp
    md3_fx.cpp
    md3_theme.cpp
    md3_i18n.cpp
    md3_icons.cpp
    md3_anim.cpp
    md3_controls.cpp
    md3_log.cpp
    md3_uianim.cpp
    md3_motionblur.cpp
    md3_uicon.cpp
    md3_pages.cpp
    md3_ai.cpp
    md3_net.cpp
    md3_mcp.cpp
    md3_common.h
)
target_link_libraries(imguidemo imgui android EGL GLESv2 log c++_static c++abi)

# ---- Live2D / stub ----
if(HAVE_LIVE2D)
    file(GLOB_RECURSE CUBISM_SRC ${CMAKE_CURRENT_SOURCE_DIR}/live2d/Framework/*.cpp)
    list(FILTER CUBISM_SRC EXCLUDE REGEX "/(D3D9|D3D11|Metal|Vulkan)/")
    add_library(cubism STATIC ${CUBISM_SRC})
    target_include_directories(cubism PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/Framework
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/Core/include)
    target_compile_definitions(cubism PUBLIC CSM_TARGET_ANDROID_ES2)
    target_link_libraries(cubism PUBLIC ${CUBISM_CORE_LIB} GLESv2 log)

    add_library(live2dapp STATIC
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/LAppAllocator.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/LAppDefine.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/LAppPal.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/TextureLoader.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/stb_image_impl.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/Live2DManager.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/live2d/Live2DModel.cpp)
    target_include_directories(live2dapp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/live2d)
    target_link_libraries(live2dapp PUBLIC cubism GLESv2 log)

    target_sources(imguidemo PRIVATE md3_live2d.cpp)
    target_link_libraries(imguidemo live2dapp)
else()
    target_sources(imguidemo PRIVATE live2d_stub.cpp ${CMAKE_CURRENT_SOURCE_DIR}/live2d/stb_image_impl.cpp)
endif()

# ---- FreeType 链接 ----
if(HAVE_FREETYPE)
    target_link_libraries(imguidemo ${FREETYPE_LIB} -lz)
endif()
