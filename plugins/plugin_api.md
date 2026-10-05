# 插件接口文档（plugin_api.md）

本工程采用 **Kotlin 主程序 + Lua 插件** 的设计，插件通过标准 Lua 脚本编写，
放在 `/sdcard/Download/plugins/` 下即可热加载（改脚本不用重编译 APK）。

---

## 一、Lua 引擎接入（Kotlin 侧一次性配置）

### 1. 添加依赖

`app/build.gradle` 的 dependencies 增加：

```kotlin
implementation 'org.luaj:luaj-jse:3.0.1'
```

### 2. 创建插件加载器

新建 `app/src/main/java/com/mikasa/ui/plugin/LuaPluginLoader.kt`：

```kotlin
package com.mikasa.ui.plugin

import android.content.Context
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File

object LuaPluginLoader {

    private var globals: Globals? = null

    /** 加载 plugins 目录下所有 .lua 脚本 */
    fun loadPlugins(context: Context) {
        val dir = File("/sdcard/Download/plugins")
        if (!dir.exists()) dir.mkdirs()
        globals = JsePlatform.standardGlobals()
        dir.listFiles { f -> f.name.endsWith(".lua") }?.forEach { file ->
            runCatching {
                globals?.load(file.readText(), file.name)?.call()
            }
        }
    }

    /** 调用 Lua 中定义的函数，比如 onFunctionClick("功能一") */
    fun call(name: String, vararg args: Any): LuaValue? {
        val g = globals ?: return null
        return runCatching {
            val fn = g.get(name)
            if (fn.isfunction()) {
                fn.call(*args.map { CoerceJavaToLua.coerce(it) }.toTypedArray())
            } else null
        }.getOrNull()
    }
}
```

### 3. 在悬浮窗服务中调用

`FloatingWindowService.kt` 的 `onCreate()` 里初始化：

```kotlin
LuaPluginLoader.loadPlugins(this)
```

功能项点击时把事件转发给插件：

```kotlin
// 在 FunctionAdapter 的 onToggle 回调里加一行：
adapter.onToggle = { name, checked ->
    showFloatToast("${if (checked) "已开启" else "已关闭"} · $name")
    LuaPluginLoader.call("onFunctionToggle", name, checked)   // ← 通知插件
}
```

---

## 二、Lua 插件标准接口（插件作者看这里）

插件脚本只要实现了下面这些函数，就会被 App 自动调用：

| Lua 函数 | 调用时机 | 参数 | 说明 |
|---|---|---|---|
| `onLoad()` | App 启动 / 插件加载时 | 无 | 初始化（注册菜单等） |
| `onFunctionToggle(name, checked)` | 用户勾选/开关某个功能 | 功能名(string)、开/关(boolean) | 响应面板功能操作 |
| `onPanelOpened()` | 悬浮窗面板打开时 | 无 | 可刷新数据 |
| `onMusicChanged(song, artist, playing)` | 音乐状态变化时 | 歌名、歌手、是否播放 | 可联动显示歌词等 |

### 示例插件骨架

```lua
-- 插件入口
function onLoad()
    print("插件已加载")
    gg.toast("插件已加载")
end

-- 用户操作了面板功能
function onFunctionToggle(name, checked)
    if checked then
        gg.toast("开启: " .. name)
    else
        gg.toast("关闭: " .. name)
    end
end

-- 面板打开
function onPanelOpened()
    print("面板已打开")
end
```

---

## 三、给 Lua 暴露的 App 能力（Lua 里可调用的 API）

| Lua 调用 | 作用 |
|---|---|
| `App.toast(msg)` | 弹出悬浮提示 |
| `App.getFunctionState(name)` | 查询功能开关状态（true/false） |
| `App.setFunctionState(name, checked)` | 修改面板功能状态 |
| `App.getMusic()` | 获取当前播放歌曲信息 |
| `Mem.search(value)` | 内存搜索（整型） |
| `Mem.read(addr, size)` | 读内存 |
| `Mem.write(addr, value)` | 写内存 |

Kotlin 侧把这些能力注册进 Lua 全局表：

```kotlin
// 注册 App 能力
globals.set("App", CoerceJavaToLua.coerce(AppBridge(context)))
globals.set("Mem", CoerceJavaToLua.coerce(MemBridge()))
```

其中 `AppBridge` / `MemBridge` 是你在 Kotlin 里实现的桥接类，
把上面的函数实现为公开方法即可被 Lua 直接调用。

---

## 四、内存搜索/修改（Mem 桥接示例）

```kotlin
/** 内存操作桥接（示例框架，真实使用需结合具体进程权限） */
class MemBridge {

    /** 整型搜索，返回匹配地址列表 */
    fun search(value: Int): List<Long> {
        // 这里演示：返回空列表，实际开发时对接
        // /proc/pid/mem 或 GameGuardian 的 RPC 能力
        return emptyList()
    }

    /** 读内存（1/2/4/8 字节） */
    fun read(address: Long, size: Int): Long = 0L

    /** 写内存 */
    fun write(address: Long, value: Long): Boolean = false
}
```

> 注意：在 Android 上直接读写其他进程内存需要 **root 权限** 或使用
> GameGuardian / Shizuku 等框架提供的能力，请按实际场景实现。

---

## 五、分发插件

1. 把写好的 `.lua` 文件放到手机 `/sdcard/Download/plugins/`。
2. 重启 App（或重开悬浮窗）即自动加载，无需重新打包 APK。
3. 想随 APK 一起分发：把脚本放进 `app/src/main/assets/plugins/`，
   并在 `LuaPluginLoader.loadPlugins()` 里先解压 assets 到 Download 目录。

---

*文档完。写插件 → 丢进 /sdcard/Download/plugins/ → 重启生效，就这么简单。*