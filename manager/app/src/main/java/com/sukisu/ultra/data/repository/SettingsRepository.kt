package com.sukisu.ultra.data.repository

interface SettingsRepository {
    var customBackgroundUri: String
    var customBackgroundUpdatedAt: Long
    /** Use the launcher wallpaper as the app background instead of [customBackgroundUri]. */
    var useSystemWallpaper: Boolean
    /** Derive the theme key color from the active background image. */
    var extractColorFromBackground: Boolean
    var cardBackgroundAlphaPercent: Int
    var customBackgroundBlurPercent: Int
    var customBackgroundScrimPercent: Int
    var uiMode: String
    var appLanguage: String
    var checkUpdate: Boolean
    // 本 fork 不检查 CI 构建（见 Downloader.checkNewVersion：只看 release）。
    var checkModuleUpdate: Boolean
    var autoDeleteModuleZip: Boolean
    var alternativeIcon : Boolean

    /** Id of the selected launcher icon (see [com.sukisu.ultra.ui.util.BuiltinAppIcons]). */
    var appIconId: String

    /**
     * 安装模块时把原始 zip 留一份，供上传页直接复用。
     *
     * 默认 true：这是「不用再去文件管理器里找包」的前提；关掉后已经留存的那份不动，
     * 只是不再留新的（清理由上传页顶栏那个入口负责）。
     */
    var keepModuleZip: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableSwipeDismiss: Boolean
    var pagerInterceptionMode: Int
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var enableNavigationBadge: Boolean
    var navigationRailExpanded: Boolean
    var pageScale: Float
    var moduleDescriptionMaxLines: Int
    var enableWebDebugging: Boolean
    var moduleSortEnabledFirst: Boolean
    var moduleSortActionFirst: Boolean
    var moduleSortWebUiFirst: Boolean
    var moduleSortZygiskFirst: Boolean
    var moduleSortMetamoduleFirst: Boolean
    var moduleSortNameOrder: String
    var moduleSortManual: Boolean
    var moduleManualOrder: String
    /** 模块仓库页排序方式，存 [com.sukisu.ultra.ui.screen.modulerepo.RepoSort] 的 ordinal。 */
    var moduleRepoSortOrder: Int

    /**
     * 模块仓库页的免责说明是否已被勾选"不再提示"。
     *
     * 默认 false：没勾过就每次进入模块仓库页都弹一次（见 [ModuleRepoDisclaimerPrefs]）。
     */
    var moduleRepoDisclaimerAccepted: Boolean
    var superuserShowSystemApps: Boolean
    var superuserShowOnlyPrimaryUserApps: Boolean
    var superuserSortOption: Int
    var suLogFilters: Set<String>?
    var showFullStatus: Boolean
    var hideHomeWarnings: Boolean
    var autoJailbreak: Boolean
    var useSoftReboot: Boolean

    /** WebDAV 云端备份设置。三个凭据属性对外都是明文，密文只落在 SharedPreferences 里。 */
    var webDavUrl: String
    var webDavUser: String
    var webDavPassword: String
    var backupCloudEnabled: Boolean

    /**
     * 各家服务商各记一份的云端配置（含没保存的草稿），JSON 原文，密码在里面是密文。
     *
     * 与上面那三个属性分工明确：上面那三个是**正在生效**的那一份，这里是"别家填到哪儿了"。
     * 编解码在 [com.sukisu.ultra.data.backup.CloudProfileBookJson]。
     */
    var backupCloudProfiles: String

    var backupAutoAfterInstall: Boolean

    /**
     * 自动备份写到哪几处。与「备份位置」那对勾选是两回事：那边管手动备份这一次，这边管
     * 无人值守的那一次——用户可能愿意手动传云端，但不想让后台悄悄上传。
     *
     * 默认都为真，正好是加这对开关之前的行为（本地必写，云端配了就写）。
     */
    var backupAutoLocal: Boolean
    var backupAutoCloud: Boolean

    /**
     * 保留额度：模块留最近几次备份、分区快照留几次。
     *
     * 两个都在 `RetentionLimit` 的范围里，读出来就钳过——这些数字躺在 SharedPreferences 里，
     * 手改过、从老版本升上来都可能越界，而越界的额度是"备份完立刻把成果删光"这种事故。
     */
    var backupRetention: Int
    var backupPartitionRetention: Int

    /**
     * 分区快照是否 gzip 压缩，默认开。
     *
     * boot 分区 96MB，压缩后约 20MB；云端备份按上传体积算时间，这个开关直接影响一等就是多久。
     * 默认开是因为它几乎没有代价：多花几秒 CPU，换上传体积少四分之三。
     */
    var backupPartitionCompressed: Boolean

    /**
     * 上一次自动备份的结果（JSON，见 `AutoBackupRecordJson`）。
     *
     * 自动备份跑完时用户早已离开刷入页，这条记录是唯一能告诉他"那次到底备上了没有"的东西，
     * 所以它得跨进程存活。空串表示还没跑过。
     */
    var backupAutoLastRecord: String

    val intentToken: String

    suspend fun getSuCompatStatus(): String
    suspend fun getSuCompatPersistValue(): Long?
    fun isSuEnabled(): Boolean
    fun setSuEnabled(enabled: Boolean): Boolean
    fun setSuCompatModePref(mode: Int)
    fun getSuCompatModePref(): Int

    suspend fun getKernelUmountStatus(): String
    fun isKernelUmountEnabled(): Boolean
    fun setKernelUmountEnabled(enabled: Boolean): Boolean

    suspend fun getSelinuxHideStatus(): String
    fun isSelinuxHideEnabled(): Boolean
    fun setSelinuxHideEnabled(enabled: Boolean): Int

    suspend fun getAvcSpoofStatus(): String
    fun isAvcSpoofEnabled(): Boolean
    fun setAvcSpoofEnabled(enabled: Boolean): Boolean

    suspend fun getMountHideStatus(): String
    fun isMountHideEnabled(): Boolean
    fun setMountHideEnabled(enabled: Boolean): Int

    suspend fun getSulogStatus(): String
    suspend fun getSulogPersistValue(): Long?
    suspend fun getSulogValue(): Long?
    fun setSulogEnabled(enabled: Boolean): Boolean

    suspend fun getAdbRootStatus(): String
    suspend fun getAdbRootPersistValue(): Long?
    suspend fun getAdbRootValue(): Long?
    fun setAdbRootEnabled(enabled: Boolean): Boolean

    suspend fun getShellSuAdbOnlyStatus(): String
    suspend fun getShellSuAdbOnlyValue(): Long?
    suspend fun getShellSuAdbOnlyPersistValue(): Long?
    fun setShellSuAdbOnlyEnabled(enabled: Boolean): Boolean

    fun isDefaultUmountModules(): Boolean
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    /**
     * 投稿功能的全局开关，默认关（存量升级用户由迁移逻辑保留为开）。
     *
     * 关掉之后两处入口一起消失：安装本地模块时不再弹「分享这个模块」的提示，
     * 模块列表里也不再显示投稿按钮。这是用户唯一能一次性关掉投稿打扰的地方。
     */
    fun isModuleContributionEnabled(): Boolean
    fun setModuleContributionEnabled(enabled: Boolean)

    fun isLkmMode(): Boolean

    fun execKsudFeatureSave()
}
