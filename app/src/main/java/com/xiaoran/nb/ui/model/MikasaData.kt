package com.xiaoran.nb.ui.model

/** 资源文件（带分区标签） */
data class ResourceFile(val name: String, val size: String, val zone: String)

/**
 * 小染自动注入 —— 共享数据
 * 悬浮窗与启动器共用同一份文件分区 / 注入方式 / 文案。
 */
object MikasaData {
    const val APP_NAME = "小染自动注入"
    const val VERSION = "小染pro"

    /** 美化区文件（美化页引用） */
    val beautyFiles = listOf(
        ResourceFile("角色立绘_小染.zip", "12.4MB", "立绘"),
        ResourceFile("悬浮球皮肤_液态玻璃.zip", "2.1MB", "皮肤"),
        ResourceFile("音效包_击杀提示音.zip", "5.6MB", "音效"),
        ResourceFile("图标资源_导航栏.zip", "1.8MB", "图标")
    )

    /** 功能区文件（功能页引用） */
    val functionFiles = listOf(
        ResourceFile("注入插件_示例.lua", "4.2KB", "插件")
    )

    /** 注入方式：默认导入 / pak导入 */
    val injectModes = listOf("默认导入", "pak导入")

    /** 主页公告 */
    val announcement = "欢迎来到小染自动注入 v小染pro。本版新增 液态玻璃界面、卡密验证与 默认/pak 双模式注入。感谢使用！"

    /** 公告列表（测试：本地多条，可刷新切换；后端接入后可改为实时拉取） */
    val announcements = listOf(
        "欢迎来到小染自动注入 v小染pro。感谢使用！",
        "本版新增 液态玻璃界面、卡密验证与 默认/pak 双模式注入。",
        "测试公告 3：后端接入后可实时更新（当前为本地占位）。",
        "安全提醒：请使用官方渠道下载，防止篡改。"
    )

    /** 卡密验证进度步骤（本地模拟，无后端） */
    val verifySteps = listOf("链接服务器", "链接成功", "验证卡密", "验证成功")
}
