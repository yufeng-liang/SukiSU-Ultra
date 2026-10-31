package com.sukisu.ultra.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.topjohnwu.superuser.ShellUtils
import com.sukisu.ultra.Natives
import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.CredentialCipher
import com.sukisu.ultra.data.backup.KeystoreKeyProvider
import com.sukisu.ultra.data.backup.RetentionLimit
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.magica.BootCompletedReceiver
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.screen.module.ModuleNameSort
import com.sukisu.ultra.ui.screen.modulerepo.RepoSort
import com.sukisu.ultra.ui.util.BgStorage
import com.sukisu.ultra.ui.util.BuiltinAppIcons
import com.sukisu.ultra.ui.util.execKsud
import com.sukisu.ultra.ui.util.getFeaturePersistValue
import com.sukisu.ultra.ui.util.getFeatureValue
import com.sukisu.ultra.ui.util.getFeatureStatus
import com.sukisu.ultra.ui.util.LocaleHelper
import java.security.SecureRandom

private const val SETTINGS_PREFS = "settings"
private const val KEY_USE_SOFT_REBOOT = "soft_reboot"
private const val KEY_AUTO_DELETE_MODULE_ZIP = "auto_delete_module_zip"
private const val KEY_KEEP_MODULE_ZIP = "keep_module_zip"
internal const val KEY_CUSTOM_HOME_TITLE = "custom_home_title"

/** Prefer soft reboot: always in jailbreak mode, or when the setting is enabled. */
fun isSoftRebootPreferred(): Boolean =
    Natives.isLateLoadMode || ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_SOFT_REBOOT, false)

/**
 * 投稿开关的存储约定。
 *
 * 这两条是纯数据，单独放出来是为了让单测能在 JVM 上直接锁住（[SettingsRepositoryImpl]
 * 要 Android 的 SharedPreferences 才能跑）：key 一旦改名，老用户的选择会静默回到默认值；
 * 默认值一旦翻面，没表过态的用户会在一次升级里集体失去或得到投稿入口。
 */
object ModuleContributionPrefs {
    const val KEY = "module_contribution_enabled"

    /**
     * 投稿功能的总体闸门：为 true 时模块页入口、安装后提示、设置页开关一起隐藏，
     * 开关无论存成什么都不生效。2026-10-25 起放开（真机 Turnstile、v2 口令三处对齐、
     * 一次真实投稿均已验证通过）；再出问题改回 true 即可整体下线，存储约定保持不动。
     */
    const val SUSPENDED = false

    /** 默认关：投稿入口只给主动打开过的人，不默认打扰所有用户。 */
    const val DEFAULT = false

    /**
     * 默认值翻面那版发布的 UTC 时间。首次运行新版时查 [android.content.pm.PackageInfo.firstInstallTime]：
     * 在这之前装过 App 的是存量用户，保留默认开；之后才装的按新默认关。
     * 存量用户的结论会立刻落盘成 key，之后每次都直接读 key，迁移只发生一次。
     */
    const val UPGRADE_CUTOFF_MILLIS = 1791763200000L  // 2026-10-12T00:00:00Z

    /** prefs 里已有 key：用户的显式选择永远赢。 */
    fun resolve(contains: Boolean, stored: Boolean = DEFAULT): Boolean =
        if (contains) stored else DEFAULT

    /**
     * prefs 里没有 key 时的迁移判定（纯函数，单测锁住）。
     * firstInstallTime 为 0（查询失败）按存量处理、fail-open 保留入口——新装一定查得到，
     * 查不到的几乎只能是装了很久的设备；宁多显示一个入口，不静音真实用户。
     */
    fun resolveUpgrade(firstInstallTime: Long, cutoff: Long = UPGRADE_CUTOFF_MILLIS): Boolean =
        firstInstallTime < cutoff
}

/**
 * 模块仓库页免责说明的存储约定。
 *
 * 和 [ModuleContributionPrefs] 一样把 key 与默认值单独放出来，好让单测在 JVM 上锁住：
 * 判据只有一句"没勾过就弹"，但它决定了每次进入模块仓库页要不要打断用户，改错一次就是
 * 全体用户每次都被弹（或反过来，该弹的再没人看见）。
 */
object ModuleRepoDisclaimerPrefs {
    const val KEY = "module_repo_disclaimer_accepted"

    /** 默认没接受：免责说明至少要完整出现过一次。 */
    const val DEFAULT = false

    /** 只有明确勾选"不再提示"才不再弹；其余情况（含从未打开过）一律每次进入都弹。 */
    fun shouldShow(accepted: Boolean = DEFAULT): Boolean = !accepted
}

/** Delete module ZIPs downloaded by the manager once they have been installed successfully. */
fun isAutoDeleteModuleZipEnabled(): Boolean =
    ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_DELETE_MODULE_ZIP, false)

/**
 * 装完模块是否把原始 zip 留一份（见 [com.sukisu.ultra.data.modulezip.ModuleZipArchive]）。
 *
 * 默认 true：这个开关存在的意义就是省掉「去文件管理器里翻包」，默认关掉等于没做。
 * 与 [isAutoDeleteModuleZipEnabled] 是两件事：那个删的是用户自己挑的源文件，
 * 这个留的是 app 私有目录里的一份副本，两者互不影响。
 */
fun isKeepModuleZipEnabled(): Boolean =
    ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_KEEP_MODULE_ZIP, true)

class SettingsRepositoryImpl : SettingsRepository {

    private companion object {
        private const val INTENT_TOKEN_KEY = "intent_token"
        private val secureRandom = SecureRandom()
    }

    private val prefs by lazy {
        ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    }

    override var customBackgroundUri: String
        get() = prefs.getString("custom_bg_uri", "") ?: ""
        set(value) = prefs.edit { putString("custom_bg_uri", value) }

    override var customBackgroundUpdatedAt: Long
        get() = prefs.getLong("custom_bg_updated_at", 0L)
        set(value) = prefs.edit { putLong("custom_bg_updated_at", value) }

    override var useSystemWallpaper: Boolean
        get() = prefs.getBoolean("use_system_wallpaper", false)
        set(value) = prefs.edit { putBoolean("use_system_wallpaper", value) }

    override var extractColorFromBackground: Boolean
        get() = prefs.getBoolean("extract_color_from_background", false)
        set(value) = prefs.edit { putBoolean("extract_color_from_background", value) }

    override var cardBackgroundAlphaPercent: Int
        get() = prefs.getInt("card_bg_alpha_percent", 60)
        set(value) = prefs.edit { putInt("card_bg_alpha_percent", value) }

    override var customBackgroundBlurPercent: Int
        get() = prefs.getInt(
            "custom_bg_blur_percent",
            BgStorage.defaultBackgroundBlurPercent(ksuApp),
        )
        set(value) = prefs.edit {
            putInt(
                "custom_bg_blur_percent",
                value.coerceIn(BgStorage.BACKGROUND_BLUR_PERCENT_MIN, BgStorage.BACKGROUND_BLUR_PERCENT_MAX),
            )
        }

    override var customBackgroundScrimPercent: Int
        get() = prefs.getInt("custom_bg_scrim_percent", BgStorage.DEFAULT_BACKGROUND_SCRIM)
        set(value) = prefs.edit {
            putInt(
                "custom_bg_scrim_percent",
                value.coerceIn(BgStorage.BACKGROUND_SCRIM_MIN, BgStorage.BACKGROUND_SCRIM_MAX),
            )
        }

    override var backgroundAppearanceExpanded: Boolean
        get() = prefs.getBoolean("background_appearance_expanded", true)
        set(value) = prefs.edit { putBoolean("background_appearance_expanded", value) }

    override var customHomeTitle: String
        get() = prefs.getString(KEY_CUSTOM_HOME_TITLE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_HOME_TITLE, value.trim()) }

    override var uiMode: String
        get() = prefs.getString("ui_mode", null) ?: initialUiMode().also { mode ->
            prefs.edit { putString("ui_mode", mode) }
        }
        set(value) = prefs.edit { putString("ui_mode", value) }

    // Fresh installs default to Material; upgrades that never picked a mode keep the previous Miuix default.
    private fun initialUiMode(): String {
        val freshInstall = runCatching {
            val info = ksuApp.packageManager.getPackageInfo(ksuApp.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(false)
        return if (freshInstall) UiMode.DEFAULT_VALUE else UiMode.Miuix.value
    }

    override var appLanguage: String
        get() = LocaleHelper.getCurrentLanguage(ksuApp)
        set(value) = LocaleHelper.setLanguage(ksuApp, value)

    override var checkUpdate: Boolean
        get() = prefs.getBoolean("check_update", true)
        set(value) = prefs.edit { putBoolean("check_update", value) }

    override var checkModuleUpdate: Boolean
        get() = prefs.getBoolean("module_check_update", true)
        set(value) = prefs.edit { putBoolean("module_check_update", value) }

    override var autoDeleteModuleZip: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE_MODULE_ZIP, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_DELETE_MODULE_ZIP, value) }

    override var keepModuleZip: Boolean
        get() = prefs.getBoolean(KEY_KEEP_MODULE_ZIP, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_MODULE_ZIP, value) }

    override var alternativeIcon : Boolean
        get() = prefs.getBoolean("use_alt_icon", false)
        set(value) = prefs.edit { putBoolean("use_alt_icon", value)}

    override var appIconId: String
        get() = prefs.getString("app_icon_id", null)
            ?: if (prefs.getBoolean("use_alt_icon", false)) {
                BuiltinAppIcons.KERNELSU_ID
            } else {
                BuiltinAppIcons.DEFAULT_ID
            }
        // Commit synchronously: changing components can restart the process before an async write lands.
        set(value) = prefs.edit(commit = true) {
            putString("app_icon_id", value)
            // Keep the legacy flag in sync so downgrading still restores the KernelSU icon.
            putBoolean("use_alt_icon", value == BuiltinAppIcons.KERNELSU_ID)
        }

    override var appIconDynamicColor: Boolean
        get() = prefs.getBoolean("app_icon_dynamic_color", false)
        set(value) = prefs.edit(commit = true) { putBoolean("app_icon_dynamic_color", value) }

    override var themeMode: Int
        get() = prefs.getInt("color_mode", 0)
        set(value) = prefs.edit { putInt("color_mode", value) }

    override var miuixMonet: Boolean
        get() = prefs.getBoolean("miuix_monet", false)
        set(value) = prefs.edit { putBoolean("miuix_monet", value) }

    override var keyColor: Int
        get() = prefs.getInt("key_color", 0)
        set(value) = prefs.edit { putInt("key_color", value) }

    override var colorStyle: String
        get() = prefs.getString("color_style", PaletteStyle.TonalSpot.name) ?: PaletteStyle.TonalSpot.name
        set(value) = prefs.edit { putString("color_style", value) }

    override var colorSpec: String
        get() = prefs.getString("color_spec", ColorSpec.SpecVersion.SPEC_2025.name) ?: ColorSpec.SpecVersion.SPEC_2025.name
        set(value) = prefs.edit { putString("color_spec", value) }

    override var enablePredictiveBack: Boolean
        get() = prefs.getBoolean("enable_predictive_back", false)
        set(value) = prefs.edit { putBoolean("enable_predictive_back", value) }

    // 默认关闭：横滑返回会和列表里的横滑操作抢手势，用户没主动开就不该接管。
    override var enableSwipeDismiss: Boolean
        get() = prefs.getBoolean("enable_swipe_dismiss", false)
        set(value) = prefs.edit { putBoolean("enable_swipe_dismiss", value) }

    override var pagerInterceptionMode: Int
        get() = prefs.getInt("pager_interception_mode", 1)
        set(value) = prefs.edit { putInt("pager_interception_mode", value.coerceIn(0, 2)) }

    override var enableBlur: Boolean
        get() = prefs.getBoolean("enable_blur", false)
        set(value) = prefs.edit { putBoolean("enable_blur", value) }

    override var enableFloatingBottomBar: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar", false)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar", value) }

    override var enableFloatingBottomBarBlur: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar_blur", false)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar_blur", value) }

    override var enableNavigationBadge: Boolean
        get() = prefs.getBoolean("enable_navigation_badge", true)
        set(value) = prefs.edit { putBoolean("enable_navigation_badge", value) }

    override var navigationRailExpanded: Boolean
        get() = prefs.getBoolean("nav_rail_expanded", false)
        set(value) = prefs.edit { putBoolean("nav_rail_expanded", value) }

    override var pageScale: Float
        get() = prefs.getFloat("page_scale", 1.0f)
        set(value) = prefs.edit { putFloat("page_scale", value) }

    override var moduleDescriptionMaxLines: Int
        get() = prefs.getInt("module_description_max_lines", 4)
        set(value) = prefs.edit { putInt("module_description_max_lines", value) }

    override var enableWebDebugging: Boolean
        get() = prefs.getBoolean("enable_web_debugging", false)
        set(value) = prefs.edit { putBoolean("enable_web_debugging", value) }

    override var moduleSortEnabledFirst: Boolean
        get() = prefs.getBoolean("module_sort_enabled_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_enabled_first", value) }

    override var moduleSortActionFirst: Boolean
        get() = prefs.getBoolean("module_sort_action_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_action_first", value) }

    override var moduleSortWebUiFirst: Boolean
        get() = prefs.getBoolean("module_sort_webui_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_webui_first", value) }

    override var moduleSortZygiskFirst: Boolean
        get() = prefs.getBoolean("module_sort_zygisk_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_zygisk_first", value) }

    override var moduleSortMetamoduleFirst: Boolean
        get() = prefs.getBoolean("module_sort_metamodule_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_metamodule_first", value) }

    override var moduleSortNameOrder: String
        get() = ModuleNameSort.fromStored(prefs.getString("module_sort_name_order", null)).name
        set(value) = prefs.edit { putString("module_sort_name_order", value) }

    override var moduleSortManual: Boolean
        get() = prefs.getBoolean("module_sort_manual", false)
        set(value) = prefs.edit { putBoolean("module_sort_manual", value) }

    override var moduleManualOrder: String
        get() = prefs.getString("module_manual_order", "") ?: ""
        set(value) = prefs.edit { putString("module_manual_order", value) }

    override var moduleRepoSortOrder: Int
        get() = prefs.getInt("module_repo_sort_order", RepoSort.UPDATED.ordinal)
        set(value) = prefs.edit { putInt("module_repo_sort_order", value) }

    override var moduleRepoDisclaimerAccepted: Boolean
        get() = prefs.getBoolean(ModuleRepoDisclaimerPrefs.KEY, ModuleRepoDisclaimerPrefs.DEFAULT)
        set(value) = prefs.edit { putBoolean(ModuleRepoDisclaimerPrefs.KEY, value) }

    override var superuserShowSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit { putBoolean("show_system_apps", value) }

    override var superuserShowOnlyPrimaryUserApps: Boolean
        get() = prefs.getBoolean("show_only_primary_user_apps", false)
        set(value) = prefs.edit { putBoolean("show_only_primary_user_apps", value) }

    override var superuserSortOption: Int
        get() = prefs.getInt("superuser_sort_option", 0)
        set(value) = prefs.edit { putInt("superuser_sort_option", value) }

    override var suLogFilters: Set<String>?
        get() = prefs.getStringSet("sulog_filters", null)?.toSet()
        set(filters) = prefs.edit { putStringSet("sulog_filters", filters) }

    override var showFullStatus: Boolean
        get() = prefs.getBoolean("show_fingerprint", true)
        set(value) = prefs.edit { putBoolean("show_fingerprint", value) }

    override var hideHomeWarnings: Boolean
        get() = prefs.getBoolean("hide_home_warnings", false)
        set(value) = prefs.edit { putBoolean("hide_home_warnings", value) }

    override var autoJailbreak: Boolean
        get() = prefs.getBoolean("auto_jailbreak", false)
        set(value) {
            runCatching {
                ksuApp.packageManager.setComponentEnabledSetting(
                    ComponentName(ksuApp, BootCompletedReceiver::class.java),
                    if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure {
                Log.e("Settings", "failed to change boot receiver state to $value", it)
            }
            prefs.edit {
                putBoolean("auto_jailbreak", value)
            }
        }

    override var useSoftReboot: Boolean
        get() = prefs.getBoolean(KEY_USE_SOFT_REBOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SOFT_REBOOT, value) }

    override var webDavUrl: String
        get() = prefs.getString("backup_webdav_url", "") ?: ""
        set(value) = prefs.edit { putString("backup_webdav_url", value) }

    override var webDavUser: String
        get() = prefs.getString("backup_webdav_user", "") ?: ""
        set(value) = prefs.edit { putString("backup_webdav_user", value) }

    /** 落盘的是密文；解密失败（如 Keystore 密钥被系统清掉）当作空串，避免整页崩。 */
    override var webDavPassword: String
        get() = prefs.getString("backup_webdav_password", "")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { CredentialCipher.decrypt(KeystoreKeyProvider.key(), it) }.getOrDefault("") } ?: ""
        set(value) {
            // 加密同样可能失败（系统密钥库不可用），而这是一次属性赋值：让它抛出去就是点"保存"
            // 直接崩，密码还只留在内存里。存不进去就当没配密码——撤掉旧密文，让读回来的值和实际
            // 能用的凭据一致，屏幕上的那句"密码没能存进去"才说得通。明文绝不落进 prefs。
            val ciphertext = runCatching { CredentialCipher.encrypt(KeystoreKeyProvider.key(), value) }.getOrNull()
            prefs.edit {
                if (value.isBlank() || ciphertext == null) remove("backup_webdav_password")
                else putString("backup_webdav_password", ciphertext)
            }
        }

    override var backupCloudEnabled: Boolean
        get() = prefs.getBoolean("backup_cloud_enabled", false)
        set(value) = prefs.edit { putBoolean("backup_cloud_enabled", value) }

    override var backupCloudProfiles: String
        get() = prefs.getString("backup_cloud_profiles", "") ?: ""
        set(value) = prefs.edit {
            if (value.isBlank()) remove("backup_cloud_profiles") else putString("backup_cloud_profiles", value)
        }

    override var backupAutoAfterInstall: Boolean
        get() = prefs.getBoolean("backup_auto_after_install", true)
        set(value) = prefs.edit { putBoolean("backup_auto_after_install", value) }

    override var backupAutoLocal: Boolean
        get() = prefs.getBoolean("backup_auto_local", true)
        set(value) = prefs.edit { putBoolean("backup_auto_local", value) }

    override var backupAutoCloud: Boolean
        get() = prefs.getBoolean("backup_auto_cloud", true)
        set(value) = prefs.edit { putBoolean("backup_auto_cloud", value) }

    override var backupRetention: Int
        get() = RetentionLimit.clampModule(prefs.getInt("backup_retention", BackupDefaults.RETENTION))
        set(value) = prefs.edit { putInt("backup_retention", RetentionLimit.clampModule(value)) }

    override var backupPartitionRetention: Int
        get() = RetentionLimit.clampPartition(
            prefs.getInt("backup_partition_retention", BackupDefaults.PARTITION_RETENTION),
        )
        set(value) = prefs.edit {
            putInt("backup_partition_retention", RetentionLimit.clampPartition(value))
        }

    override var backupPartitionCompressed: Boolean
        get() = prefs.getBoolean("backup_partition_compressed", BackupDefaults.PARTITION_COMPRESSED)
        set(value) = prefs.edit { putBoolean("backup_partition_compressed", value) }

    override var backupAutoLastRecord: String
        get() = prefs.getString("backup_auto_last_record", "") ?: ""
        set(value) = prefs.edit {
            if (value.isBlank()) remove("backup_auto_last_record") else putString("backup_auto_last_record", value)
        }

    override val intentToken: String
        get() {
        val existing = prefs.getString(INTENT_TOKEN_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val token = ByteArray(32).also(secureRandom::nextBytes)
            .joinToString(separator = "") { "%02x".format(it) }
        prefs.edit { putString(INTENT_TOKEN_KEY, token) }
        return token
    }

    override suspend fun getSuCompatStatus(): String = getFeatureStatus("su_compat")

    override suspend fun getSuCompatPersistValue(): Long? = getFeaturePersistValue("su_compat")

    override fun isSuEnabled(): Boolean = Natives.isSuEnabled()

    override fun setSuEnabled(enabled: Boolean): Boolean = Natives.setSuEnabled(enabled)

    override fun setSuCompatModePref(mode: Int) = prefs.edit { putInt("su_compat_mode", mode) }

    override fun getSuCompatModePref(): Int = prefs.getInt("su_compat_mode", 0)

    override suspend fun getKernelUmountStatus(): String = getFeatureStatus("kernel_umount")

    override fun isKernelUmountEnabled(): Boolean = Natives.isKernelUmountEnabled()

    override fun setKernelUmountEnabled(enabled: Boolean): Boolean = Natives.setKernelUmountEnabled(enabled)

    override suspend fun getSelinuxHideStatus(): String = getFeatureStatus("selinux_hide")

    override fun isSelinuxHideEnabled(): Boolean = Natives.isSelinuxHideEnabled()

    override fun setSelinuxHideEnabled(enabled: Boolean): Int = Natives.setSelinuxHideEnabled(enabled)

    override suspend fun getAvcSpoofStatus(): String = getFeatureStatus("avc_spoof")
    override fun isAvcSpoofEnabled(): Boolean = Natives.isAvcSpoofEnabled()
    override fun setAvcSpoofEnabled(enabled: Boolean): Boolean = Natives.setAvcSpoofEnabled(enabled)

    override suspend fun getMountHideStatus(): String = getFeatureStatus("mount_hide")

    override fun isMountHideEnabled(): Boolean = Natives.isMountHideEnabled()

    override fun setMountHideEnabled(enabled: Boolean): Int = Natives.setMountHideEnabled(enabled)

    override suspend fun getSulogStatus(): String = getFeatureStatus("sulog")

    override suspend fun getSulogPersistValue(): Long? = getFeaturePersistValue("sulog")

    override suspend fun getSulogValue(): Long? = getFeatureValue("sulog")

    override fun setSulogEnabled(enabled: Boolean): Boolean = execKsud("feature set sulog ${if (enabled) 1 else 0}", true)

    override suspend fun getAdbRootStatus(): String = getFeatureStatus("adb_root")

    override suspend fun getAdbRootPersistValue(): Long? = getFeaturePersistValue("adb_root")

    override suspend fun getAdbRootValue(): Long? = getFeatureValue("adb_root")

    override fun setAdbRootEnabled(enabled: Boolean): Boolean =
        if (execKsud("feature set adb_root ${if (enabled) 1 else 0}", true)) {
            ShellUtils.fastCmd("setprop ctl.restart adbd")
            true
        } else {
            false
        }

    override suspend fun getShellSuAdbOnlyStatus(): String = getFeatureStatus("shell_su_adb_only")

    override suspend fun getShellSuAdbOnlyValue(): Long? = getFeatureValue("shell_su_adb_only")

    override suspend fun getShellSuAdbOnlyPersistValue(): Long? = getFeaturePersistValue("shell_su_adb_only")

    override fun setShellSuAdbOnlyEnabled(enabled: Boolean): Boolean =
        execKsud("feature set shell_su_adb_only ${if (enabled) 1 else 0}", true)

    override fun isDefaultUmountModules(): Boolean = Natives.isDefaultUmountModules()

    override fun setDefaultUmountModules(enabled: Boolean): Boolean = Natives.setDefaultUmountModules(enabled)

    override fun isModuleContributionEnabled(): Boolean {
        if (prefs.contains(ModuleContributionPrefs.KEY)) {
            return prefs.getBoolean(ModuleContributionPrefs.KEY, ModuleContributionPrefs.DEFAULT)
        }
        // 没有 key：先按安装时间做一次升级迁移再落盘，之后永远走上面的分支。
        // 存量用户（翻面前就装过）保留默认开；查不到安装时间也按存量 fail-open。
        val enabled = ModuleContributionPrefs.resolveUpgrade(firstInstallTime())
        prefs.edit { putBoolean(ModuleContributionPrefs.KEY, enabled) }
        return enabled
    }

    private fun firstInstallTime(): Long = runCatching {
        ksuApp.packageManager.getPackageInfo(ksuApp.packageName, 0).firstInstallTime
    }.getOrDefault(0L)

    override fun setModuleContributionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(ModuleContributionPrefs.KEY, enabled) }
    }

    override fun isLkmMode(): Boolean = Natives.isLkmMode

    override fun execKsudFeatureSave() {
        execKsud("feature save", true)
    }
}
