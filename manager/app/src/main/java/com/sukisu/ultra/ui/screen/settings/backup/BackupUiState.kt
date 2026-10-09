package com.sukisu.ultra.ui.screen.settings.backup

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.WebDavPreset
import com.sukisu.ultra.data.backup.WebDavPresets

@Immutable
data class BackupUiState(
    val loading: Boolean = false,
    val origin: BackupOrigin = BackupOrigin.LOCAL,
    val kind: BackupKind = BackupKind.MODULE,
    val rows: List<BackupRow> = emptyList(),
    val message: String? = null,
    val cloudUrl: String = "",
    val cloudUser: String = "",
    val cloudPass: String = "",
    val cloudConfigured: Boolean = false,
    /**
     * 用户刚点的那个服务商预设的提示。
     *
     * 填地址只是第一步，"去哪生成应用密码"才是小白卡住的地方，所以点预设必须把这句话显示出来。
     * 存在状态里而不是从 URL 反推：Nextcloud 的模板带 `USERNAME` 占位符，用户改完地址那一刻
     * 恰恰是最需要提示的时候。
     */
    @StringRes val cloudPresetHintRes: Int? = null,
    /**
     * 云端表单是否展开。
     *
     * 默认收起：配置是一次性的事，而这块表单（说明 + 四个预设 + 三个输入框 + 两个按钮）铺开会
     * 把列表挤到屏幕外。收起时只占一行，标题右边直接写着当前填的地址。
     */
    val cloudExpanded: Boolean = false,
    /**
     * 当前高亮的服务商预设。
     *
     * 点了哪个预设哪个就亮着——否则用户点完只能从地址栏里的域名反推刚才点的是谁。手动改地址
     * 不取消高亮（改完多半还是那家的地址），重开页面时按地址再认一次（[WebDavPresets.match]）。
     */
    val selectedPreset: WebDavPreset? = null,
    /**
     * 列表为空时该说的那句话（已本地化）。
     *
     * 空列表有两种完全不同的原因——"还没有备份，点立即备份"和"本机根本没有原厂镜像可备份"——
     * 界面上什么都不显示的话，用户会以为功能坏了。
     */
    val emptyText: String? = null,
    /** 安装模块成功后是否自动备份一次。默认开。 */
    val autoBackupEnabled: Boolean = true,
    /**
     * 上一次自动备份的结果。
     *
     * 它可能在任何一个界面跑完，所以状态必须从磁盘读回来；null = 还没跑过。
     * 关闭开关后仍然显示——历史是事实，不该被一个开关抹掉。
     */
    val autoBackupRecord: AutoBackupRecord? = null,
)

@Immutable
data class BackupActions(
    val onBack: () -> Unit,
    val onSelectOrigin: (BackupOrigin) -> Unit,
    val onSelectKind: (BackupKind) -> Unit,
    val onBackup: () -> Unit,
    val onRestore: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onImport: () -> Unit,
    val onSetAutoBackup: (Boolean) -> Unit,
    val onToggleCloud: () -> Unit,
    val onSelectPreset: (WebDavPreset) -> Unit,
    val onUrlChange: (String) -> Unit,
    val onUserChange: (String) -> Unit,
    val onPassChange: (String) -> Unit,
    val onTestCloud: () -> Unit,
    val onSaveCloud: () -> Unit,
)
