# 第三方插件目录（可修改）

本目录是给开发者/修改者准备的**插件示例**，随便改、随便玩。

```
plugins/
├── README.md             ← 本文件（插件说明）
├── plugin_api.md         ← 插件接口文档（怎么接入 App）
└── example_plugin.lua    ← Lua 示例插件（GG 修改器风格，可随意修改）
```

## 快速开始

1. 打开 `example_plugin.lua`，这是完整可运行的示例。
2. 里面封装了：
   - `UI` 菜单 / 提示
   - 内存搜索、读取、写入框架
   - 悬浮窗功能开关演示
3. 直接改函数里的内容就能变成你自己的插件。

## 怎么把 Lua 插件跑进 App（进阶）

App 目前是 Kotlin 写的，要执行 Lua 需要加一个 Lua 引擎：

```kotlin
// app/build.gradle 增加依赖
implementation 'org.luaj:luaj-jse:3.0.1'
```

然后在 `FloatingWindowService.kt` 里加载插件：

```kotlin
// 读取插件脚本
val lua = Globals()
lua.load(File("/sdcard/Download/plugins/example_plugin.lua").readText()).call()
// 调用 Lua 里的函数
val f = lua.get("onFunctionClick") as LuaFunction
f.call(CoerceJavaToLua.coerce("功能一"))
```

详见 `plugin_api.md`。

> 提示：插件脚本放 `/sdcard/Download/plugins/` 目录，App 启动时自动加载，改脚本不用重编译 APK。
