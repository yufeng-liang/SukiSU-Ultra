package com.sukisu.ultra.ui.screen.settings.backup

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.sukisu.ultra.data.backup.AutoBackupPolicy
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.BackupRunResult
import com.sukisu.ultra.data.backup.RetentionLimit
import com.sukisu.ultra.data.backup.WebDavPreset
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.data.model.Module
import java.io.File

/**
 * 「备份与恢复」页的两个分页。
 *
 * 分成两页是因为这两件事的落点不同：「备份」页要把选项配好再按下去，「恢复」页要的是一份
 * 能翻的清单。挤在一页里，选项（云端表单 + 位置 + 内容 + 模块列表）一铺开，列表就得往下让位，
 * 想恢复还得先划过一整屏设置。
 */
enum class BackupTab { BACKUP, RESTORE }

@Immutable
data class BackupUiState(
    val loading: Boolean = false,
    /** 当前分页。默认停在「备份」：进这一页多半是要备一份。 */
    val tab: BackupTab = BackupTab.BACKUP,
    /**
     * 勾选的来源（本机 / 云端）。
     *
     * 可以同时勾上：一次「立即备份」就写两份，列表也把两侧合起来列。默认只勾本机——云端
     * 没配好之前它本来也选不了。
     */
    val origins: Set<BackupOrigin> = setOf(BackupOrigin.LOCAL),
    /** 勾选的内容（模块 / 原厂 boot 镜像）。同样可以同时勾上。 */
    val kinds: Set<BackupKind> = setOf(BackupKind.MODULE),
    val rows: List<BackupRow> = emptyList(),
    /**
     * [rows] 按"哪一次备份"分好的组，新的在前。
     *
     * 列表只显示它：一次备份 11 个模块就是 11 行，铺开来把"我最近备了什么"这个问题埋掉。
     * 点进去看具体条目（见 [openGroup]）。
     */
    val groups: List<BackupGroup> = emptyList(),
    /**
     * 正打开着的那一组（点进去的备份详情）。
     *
     * 详情放在这一页里而不是单开一条路由：它要的数据就是这一页已经列出来的那些行，多一条路由
     * 就得把来源/内容再从路由参数里传一遍，还要处理"详情页的 ViewModel 用默认勾选重新列一遍
     * 列表"这种分叉。系统返回键由界面拦一次（见 BackupScreen）。
     */
    val openGroupId: String? = null,
    /**
     * 详情页里勾选的条目（行 id）。
     *
     * 打开一组时默认全勾：进来多半就是"把这一份恢复回去"或"整份导出"，让人从零开始勾一次
     * 是多余的一步。取消勾选是给"只要其中几个模块"那种情况用的。
     */
    val openGroupSelected: Set<String> = emptySet(),
    /**
     * 多选模式下勾中的分组 id。
     *
     * 存 id 而不是分组对象：列表刷新（恢复、删除、云端那份在别处被删）会换出一批新的分组
     * 对象，按对象比会把勾选丢光。勾选里的 id 在列表里找不到就自然不算数（见 [selectedGroups]）。
     */
    val selectedGroupIds: Set<String> = emptySet(),
    /**
     * 已经打包好、等着交给分享面板的文件。
     *
     * 状态里带一个文件是因为界面要拿它拼 Intent：消息通道只有一条字符串，塞不下文件。
     * 界面交出去之后调 [BackupActions.onShareConsumed] 清掉。
     */
    val pendingShare: File? = null,
    /**
     * 正在跑（或刚跑完）的那次备份。
     *
     * 云端上传 boot 要几十秒到几分钟，只有一个不动的按钮用户没法判断是不是卡住了；跑完之后
     * 结果也留在这个弹窗里，而不是几秒就消失的 snackbar——一次备份的结果值得看清。
     */
    val backupRun: BackupRunState? = null,
    val message: String? = null,
    /** 输入框里的地址（可能是还没保存的编辑）。 */
    val cloudUrl: String = "",
    /**
     * 已经保存生效的地址。
     *
     * 和 [cloudUrl] 分开：列表是从已保存的配置读的，"备份存在哪"这句话必须说已保存的那个，
     * 否则用户改完地址没保存就会看到一句谎话。
     */
    val cloudSavedUrl: String = "",
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
     * 已安装的模块，供用户勾选要备份哪几个。
     *
     * 读不到时为空且 [modulesUnavailable] 为真——此时按"全部备份"走，宁可多备也不让
     * 一次读失败变成"什么都没备"。
     */
    val modules: List<Module> = emptyList(),
    /** 勾选中的模块 id。默认全选：绝大多数人是"全都要"。 */
    val selectedModuleIds: Set<String> = emptySet(),
    /** 模块勾选列表是否展开。 */
    val modulesExpanded: Boolean = false,
    /** 模块列表读不出来（没有 root、ksud 出错）。 */
    val modulesUnavailable: Boolean = false,
    /**
     * 列表为空时该说的那句话（已本地化）。
     *
     * 空列表的原因不止一种——"还没有备份，点立即备份"和"本机根本没有原厂镜像可备份"完全是两件事——
     * 界面上什么都不显示的话，用户会以为功能坏了。
     */
    val emptyText: String? = null,
    /** 安装模块成功后是否自动备份一次。默认开。 */
    val autoBackupEnabled: Boolean = true,
    /** 自动备份是否写本机。与手动备份的「备份位置」是两套选择。 */
    val autoBackupLocal: Boolean = true,
    /**
     * 自动备份是否写云端。
     *
     * 云端没配好时界面上这一项是锁住且显示为未勾的（见 [autoBackupTargets]）：勾了也写不出去，
     * 让一个勾选框停在"看着生效其实不生效"的状态比锁住更让人猜。偏好值本身留着，
     * 等地址配好就照它显示。
     */
    val autoBackupCloud: Boolean = true,
    /**
     * 上一次自动备份的结果。
     *
     * 它可能在任何一个界面跑完，所以状态必须从磁盘读回来；null = 还没跑过。
     * 关闭开关后仍然显示——历史是事实，不该被一个开关抹掉。
     */
    val autoBackupRecord: AutoBackupRecord? = null,

    /**
     * 保留额度：模块留最近几次备份、boot 留几次、回滚点每个项目留几份。
     *
     * 以前这三个是代码里的常量，界面上看不见也改不了——用户只能从"我的第六份备份不见了"反推出
     * 有这么个限制。默认值在 [BackupDefaults]，取值范围在 [RetentionLimit]。
     */
    val moduleRetention: Int = BackupDefaults.RETENTION,
    val bootRetention: Int = BackupDefaults.BOOT_RETENTION,
    val rollbackRetention: Int = BackupDefaults.ROLLBACK_RETENTION,
    /** 保留额度那一块是否展开。它是"设一次就不管"的设置，摊开会把备份选项挤下去。 */
    val retentionExpanded: Boolean = false,
) {
    /**
     * 现在按「立即备份」有没有意义。
     *
     * 模块一个都没勾时按下去只会得到"写入 0 项"——那不是结果，是用户还没勾完。位置和内容都
     * 空着同理。列表读不出来时不能拦（那是一次读失败，不是用户的选择）。
     */
    val canBackUp: Boolean
        get() = origins.isNotEmpty() &&
            kinds.isNotEmpty() &&
            (BackupKind.MODULE !in kinds || modulesUnavailable || selectedModuleIds.isNotEmpty())

    /** 两侧都勾上时列表里每行要标出它在哪一侧；只勾一侧时标了也没信息量。 */
    val showsOriginBadge: Boolean get() = origins.size > 1

    /** 稳定的展示顺序（本机在前），集合本身没有顺序。 */
    val orderedOrigins: List<BackupOrigin> get() = origins.sortedBy { it.ordinal }

    /**
     * 自动备份实际会写到哪几处。
     *
     * 界面显示的和数据层执行的是同一套判定（都走 [AutoBackupPolicy.targets]）：云端没配好时
     * 勾着也不算数，所以这里不能只看那两个偏好项——否则会出现"界面说会写云端、实际没写"。
     */
    val autoBackupTargets: AutoBackupPolicy.AutoBackupTargets
        get() = AutoBackupPolicy.targets(autoBackupLocal, autoBackupCloud, cloudConfigured)

    /** 自动备份开着但一个目的地都没勾。界面据此挂一句说明，不然开关开着却什么都没发生。 */
    val autoBackupWritesNothing: Boolean
        get() = autoBackupEnabled && !autoBackupTargets.any

    /** 正打开着的那一组；刷新后这一组没了（被保留策略淘汰）就退回列表。 */
    val openGroup: BackupGroup? get() = groups.firstOrNull { it.id == openGroupId }

    /**
     * 列表是否处在多选模式。
     *
     * 就是"勾了至少一份"，不另设一个开关：长按进来时那一行已经勾上，取消掉最后一份就自然退出。
     * 两个字段各说各话的话，迟早会停在一个"已选 0 份"的操作栏上。
     */
    val selecting: Boolean get() = selectedGroupIds.isNotEmpty()

    /**
     * 多选模式下勾中的那几份，顺序跟着列表走。
     *
     * 从 [groups] 里筛而不是直接拿勾选集合：列表刷新后已经不存在的 id 会被自动排除掉，
     * 不会出现"删一份已经不在列表里的备份"。
     */
    val selectedGroups: List<BackupGroup> get() = groups.filter { it.id in selectedGroupIds }

    /** 勾中的那几份一共几条（确认弹窗和结果消息要说"几项"）。 */
    val selectedEntryCount: Int get() = selectedGroups.sumOf { it.rows.size }

    /** 详情页里勾中的那些条目。 */
    val openGroupSelection: List<BackupRow>
        get() = openGroup?.rows.orEmpty().filter { it.id in openGroupSelected }

    /** 详情页里的"全选"是否处于选中态。 */
    val allGroupEntriesSelected: Boolean
        get() {
            val rows = openGroup?.rows.orEmpty()
            return rows.isNotEmpty() && openGroupSelected.size == rows.size
        }
}

/**
 * 刷新后勾选里还作数的那些 id。
 *
 * 列表一刷新就换出一批新的分组（云端那份在别处被删了、旧的一份被保留策略淘汰了），勾选里会
 * 留下已经不存在的 id。留着不会删错东西——[BackupUiState.selectedGroups] 只在当前列表里找——
 * 但"已选 3 份"会和屏幕上勾中的行数对不上，所以每次刷新都剪一遍。
 */
internal fun pruneSelection(selected: Set<String>, groups: List<BackupGroup>): Set<String> =
    selected intersect groups.mapTo(mutableSetOf()) { it.id }

/**
 * 备份弹窗的状态：正在跑哪个目标、推了多少字节、多快，跑完则是结果。
 */
@Immutable
data class BackupRunState(
    /** 第几个目标（1 起），和 [total] 一起显示"2/4"。 */
    val current: Int,
    val total: Int,
    val origin: BackupOrigin,
    val kind: BackupKind,
    val sentBytes: Long = 0,
    /** 这次目标的归档总字节数；0 表示还不知道（没有可传的东西）。 */
    val totalBytes: Long = 0,
    /** 这个目标里的第几个归档（1 起）、一共几个；0 表示还没开始报。 */
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val speedBytesPerSecond: Long = 0,
    /**
     * 跑完之后这次目标的平均速度（总字节 ÷ 耗时）。
     *
     * 和 [speedBytesPerSecond] 分开：那个是瞬时值，上传掉速或卡住时才是要看的东西；跑完还留着
     * 一个"最后的瞬时速度"没意义，用户想知道的是"这份 96MB 的镜像到底传了多久、平均多快"。
     */
    val averageBytesPerSecond: Long = 0,
    /** 已经跑完，[result] 里是结果。 */
    val done: Boolean = false,
    val result: String? = null,
) {
    /** 进度条要的值；总字节还不知道时给 0，界面按不确定进度画。 */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

@Immutable
data class BackupTargetResult(
    val origin: BackupOrigin,
    val kind: BackupKind,
    val result: BackupRunResult,
)

@Immutable
data class BackupActions(
    val onBack: () -> Unit,
    /** 切到「备份」或「恢复」分页。 */
    val onSelectTab: (BackupTab) -> Unit,
    val onToggleOrigin: (BackupOrigin) -> Unit,
    val onToggleKind: (BackupKind) -> Unit,
    val onBackup: () -> Unit,
    /** 点开一次备份看详情。**不在这里恢复任何东西**——列表行点一下就恢复等于给误触点了个火。 */
    val onOpenGroup: (BackupGroup) -> Unit,
    val onCloseGroup: () -> Unit,
    /** 详情页：勾选/取消一个条目。 */
    val onToggleGroupEntry: (String) -> Unit,
    val onSetAllGroupEntries: (Boolean) -> Unit,
    /** 恢复勾中的那些条目。boot 的那次二次确认由界面在调它之前问。 */
    val onRestoreSelected: () -> Unit,
    /** 导出/分享勾中的那些条目（一条给原样归档，多条打成一个 zip）。 */
    val onShareSelected: () -> Unit,
    /** 删掉这一整组备份。界面先问一次。 */
    val onDeleteGroup: () -> Unit,
    /** 长按一行进入多选模式，并把这一行勾上。 */
    val onStartSelection: (BackupGroup) -> Unit,
    /** 多选模式下点一行：勾上 / 取消。 */
    val onToggleGroupSelection: (BackupGroup) -> Unit,
    /** 退出多选模式（一份都不删）。 */
    val onClearSelection: () -> Unit,
    /** 删掉勾中的那几份备份。界面先问一次。 */
    val onDeleteSelected: () -> Unit,
    /** 分享文件已经交给系统了。 */
    val onShareConsumed: () -> Unit,
    /** 关掉备份进度/结果弹窗。 */
    val onDismissBackupRun: () -> Unit,
    val onImport: () -> Unit,
    val onSetAutoBackup: (Boolean) -> Unit,
    /** 自动备份写到哪：本机 / 云端各一个。 */
    val onSetAutoBackupLocal: (Boolean) -> Unit,
    val onSetAutoBackupCloud: (Boolean) -> Unit,
    /** 保留额度：模块 / boot / 回滚点各几次。值由数据层钳过，界面传原值即可。 */
    val onSetModuleRetention: (Int) -> Unit,
    val onSetBootRetention: (Int) -> Unit,
    val onSetRollbackRetention: (Int) -> Unit,
    /** 保留额度那一块的展开/收起。 */
    val onToggleRetention: () -> Unit,
    val onToggleCloud: () -> Unit,
    val onToggleModules: () -> Unit,
    val onToggleModule: (String) -> Unit,
    val onSetAllModules: (Boolean) -> Unit,
    val onSelectPreset: (WebDavPreset) -> Unit,
    val onUrlChange: (String) -> Unit,
    val onUserChange: (String) -> Unit,
    val onPassChange: (String) -> Unit,
    val onTestCloud: () -> Unit,
    val onSaveCloud: () -> Unit,
)
