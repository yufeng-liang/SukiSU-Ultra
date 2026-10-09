package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.model.Module

/**
 * 模块勾选列表里每一行显示什么。
 *
 * 纯函数：模块名可能为空（module.prop 里没写 name）、版本可能是 ksud 填的 "Unknown"，
 * 这两种"没什么可显示"的情况都只能靠单测钉住，在界面里没法验证。
 */
object ModuleOptionText {

    /** 名字为空时退回 id——列表里一行空白等于这一项不存在。 */
    fun title(module: Module): String = module.name.trim().ifBlank { module.id }

    /**
     * 副标题：版本 + 已禁用。
     *
     * 两段都可能有、可能没有；都没有时返回 null，让调用方不渲染这一行，而不是留一行空白。
     */
    fun summary(module: Module, disabledLabel: String): String? {
        val version = module.version.trim().takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, true) }
        val disabled = disabledLabel.takeIf { !module.enabled }
        return listOfNotNull(version, disabled).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    private const val UNKNOWN = "Unknown"
}
