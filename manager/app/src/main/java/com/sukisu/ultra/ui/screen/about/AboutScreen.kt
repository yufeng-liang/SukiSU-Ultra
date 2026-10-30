package com.sukisu.ultra.ui.screen.about

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.BuildConfig
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.theme.Shapes
import com.sukisu.ultra.ui.theme.ThemeController
import com.sukisu.ultra.ui.theme.Typography
import com.sukisu.ultra.ui.theme.animateAsState
import com.sukisu.ultra.ui.theme.rememberKernelSUColorScheme
import com.sukisu.ultra.ui.util.BuiltinAppIcons
import com.sukisu.ultra.ui.viewmodel.SettingsViewModel

// Miuix's own default accent, used when the Miuix skin runs without Monet or a custom key color.
private val MiuixDefaultSeed = Color(0xFF3482FF)

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val viewModel = viewModel<SettingsViewModel>()
    val settingsState by viewModel.uiState.collectAsStateWithLifecycle()
    val htmlString = stringResource(
        id = R.string.about_source_code,
        "<b><a href=\"https://github.com/yufeng-liang/SukiSU-Ultra\">GitHub</a></b>",
        "<b><a href=\"https://t.me/SukiKSU\">Telegram</a></b>",
    )
    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        // 版本名只有品牌标识（"mod"），版本号单独补进括号。别把它塞进
        // build.gradle.kts 的 getVersionName()：那个值同时进 APK 文件名
        // （SukiSU_<versionName>_<versionCode>）和模块脚本的 KSU_VER 环境变量，
        // 带括号会同时弄脏这两处。
        versionName = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        icon = BuiltinAppIcons.find(settingsState.appIconId),
        links = extractLinks(htmlString),
        checkUpdate = settingsState.checkUpdate,
        checkModuleUpdate = settingsState.checkModuleUpdate,
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = uriHandler::openUri,
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
    )

    when (LocalUiMode.current) {
        UiMode.Material -> AboutScreenMaterial(state, actions)
        // Miuix 皮肤有自己的关于页：动态背景 + logo 视差淡出 + 磨砂卡片。
        // 上游 59d4a58f 曾把它删掉、改用「Material 页套 MiuixSkinMaterialTheme」顶替，
        // 结果 Miuix 皮肤下既丢了动态背景，也丢了更新检查开关的入口。
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
    }
}

/**
 * 把 Material 组件包进 Miuix 皮肤的配色里。
 *
 * 贡献者墙、名字墙这些是从 Material 关于页里抽出来的共用组件，它们读
 * [MaterialTheme] 的 shapes / colorScheme / typography。Miuix 关于页要用它们，
 * 就必须先套一层这里，否则取到的是 Material 默认配色，跟 Miuix 的深色底对不上。
 */
@Composable
internal fun MiuixSkinMaterialTheme(content: @Composable () -> Unit) {
    val appSettings = remember { ThemeController.getAppSettings() }
    val colorMode = appSettings.colorMode
    val darkTheme = colorMode.isDark || (colorMode.isSystem && isSystemInDarkTheme())
    val seedColor = when {
        appSettings.keyColor != 0 -> Color(appSettings.keyColor)
        colorMode.isMonet -> Color.Unspecified
        else -> MiuixDefaultSeed
    }
    val colorScheme = rememberKernelSUColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        isAmoled = colorMode.isAmoled,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )

    MaterialExpressiveTheme(
        colorScheme = colorScheme.animateAsState(),
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes,
        typography = Typography,
        content = content,
    )
}
