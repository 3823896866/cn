-- ============================================================
-- 三笠美化 MikasaUI — 第三方 Lua 插件示例
-- GG 修改器风格，完整可运行，随便改！
--
-- 功能演示：
--   1. onLoad()           插件加载入口
--   2. onFunctionToggle   响应悬浮窗面板勾选/开关
--   3. Mem 内存搜索/读写框架（GG 风格）
--   4. UI 菜单与提示封装
--
-- 使用：丢进 /sdcard/Download/plugins/ 目录，重启 App 生效
-- ============================================================

-- ---------- 全局配置（可修改） ----------
PLUGIN_NAME    = "三笠美化示例插件"
PLUGIN_VERSION = "1.0"
AUTHOR         = "Mikasa Team"

-- ---------- 1. 加载入口 ----------
function onLoad()
    print("[插件] " .. PLUGIN_NAME .. " v" .. PLUGIN_VERSION .. " 已加载")
    gg.toast("✅ " .. PLUGIN_NAME .. " 已加载")
end

-- ---------- 2. 面板功能事件（App 回调） ----------
-- 用户勾选/开关悬浮窗里的功能时触发
-- name    : 功能名（如"功能一"、"按钮示例"）
-- checked : true=开启 false=关闭
function onFunctionToggle(name, checked)
    local state = checked and "开启" or "关闭"
    gg.toast("「" .. name .. "」已" .. state)

    -- ===== 在这里写你的功能逻辑 =====
    -- 示例：根据功能名做不同的事
    if name == "功能一" then
        if checked then
            print("功能一 生效")
        end
    elseif name == "功能二" then
        -- TODO: 你的功能
    end
    -- =================================
end

-- ---------- 3. 内存搜索 / 读取 / 写入（GG 风格框架） ----------
-- 搜索整型数值，返回匹配地址列表
function MemSearch(value, region)
    gg.clearResults()
    local opts = {}
    if region then
        opts = { ["region"] = region }  -- 0=ca,1=cb,2=cd,3=Cha,4=Jh,5=O,6=Xa,7=As,8=B,9=So
    end
    gg.searchNumber(value, gg.TYPE_DWORD, false, gg.SIGN_EQUAL, 0, -1, 0, opts)
    local count = gg.getResultCount()
    gg.toast("搜索到 " .. count .. " 个结果")
    return count
end

-- 读取地址值（size: 1=BYTE 2=WORD 4=DWORD 8=QWORD）
function MemRead(address, size)
    local t = gg.getValues({ { address = address, flags = size or gg.TYPE_DWORD } })
    return t[1].value
end

-- 写入地址值
function MemWrite(address, value, size)
    gg.setValues({ { address = address, flags = size or gg.TYPE_DWORD, value = value } })
    return true
end

-- ---------- 4. UI 菜单与提示封装 ----------
-- 弹出选择菜单（列表项自动分页）
function ShowMenu(title, items)
    local choices = {}
    for i, v in ipairs(items) do
        table.insert(choices, i .. ". " .. v)
    end
    local sel = gg.choice(choices, nil, title)
    if sel == nil then
        return 0
    end
    gg.toast("你选择了: " .. items[sel])
    return sel
end

-- 确认框
function Ask(msg)
    return gg.isVisible() and gg.alert(msg, "确定", "取消") == 1
end

-- 悬浮提示
function Toast(msg)
    gg.toast(msg)
end

-- ---------- 5. 演示：菜单测试 ----------
-- 在 GG 悬浮窗里点菜单 → 悬浮窗 → 运行脚本，然后执行：
function main()
    local menu = {
        "搜 索演示", "读 取演示", "写 入演示", "退 出"
    }
    local sel = ShowMenu("三笠美化示例插件", menu)
    if sel == 1 then
        MemSearch(100, 0)
    elseif sel == 2 then
        gg.toast("示例地址值: " .. tostring(MemRead(0x0, gg.TYPE_DWORD)))
    elseif sel == 3 then
        MemWrite(0x0, 999, gg.TYPE_DWORD)
        gg.toast("已写入")
    end
end

-- 允许在 GG 中直接运行
if gg then
    -- 在 GG 中运行本脚本时自动执行 main
end
