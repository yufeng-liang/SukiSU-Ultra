package com.sukisu.ultra.data.repository

interface SettingsRepository {
    var uiMode: String
    var appLanguage: String
    var checkUpdate: Boolean
    var checkModuleUpdate: Boolean
    var alternativeIcon : Boolean
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
    var moduleRepoSortOrder: Int
    var superuserShowSystemApps: Boolean
    var superuserShowOnlyPrimaryUserApps: Boolean
    var superuserSortOption: Int
    var suLogFilters: Set<String>?
    var showFullStatus: Boolean
    var autoJailbreak: Boolean
    var useSoftReboot: Boolean

    /** WebDAV 云端备份设置。三个凭据属性对外都是明文，密文只落在 SharedPreferences 里。 */
    var webDavUrl: String
    var webDavUser: String
    var webDavPassword: String
    var backupCloudEnabled: Boolean
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
     * 保留额度：模块留最近几次备份、boot 留几次、回滚点每个项目留几份。
     *
     * 三个都在 `RetentionLimit` 的范围里，读出来就钳过——这些数字躺在 SharedPreferences 里，
     * 手改过、从老版本升上来都可能越界，而越界的额度是"备份完立刻把成果删光"这种事故。
     */
    var backupRetention: Int
    var backupBootRetention: Int
    var backupRollbackRetention: Int

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

    suspend fun getSulogStatus(): String
    suspend fun getSulogPersistValue(): Long?
    fun setSulogEnabled(enabled: Boolean): Boolean

    suspend fun getAdbRootStatus(): String
    suspend fun getAdbRootPersistValue(): Long?
    fun setAdbRootEnabled(enabled: Boolean): Boolean

    fun isDefaultUmountModules(): Boolean
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    /**
     * 投稿功能的全局开关，默认开。
     *
     * 关掉之后两处入口一起消失：安装本地模块时不再弹「分享这个模块」的提示，
     * 模块列表里也不再显示投稿按钮。这是用户唯一能一次性关掉投稿打扰的地方。
     */
    fun isModuleContributionEnabled(): Boolean
    fun setModuleContributionEnabled(enabled: Boolean)

    fun isLkmMode(): Boolean

    fun execKsudFeatureSave()
}
