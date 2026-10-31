package com.sukisu.ultra.ui.screen.settings.backup

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.sukisu.ultra.data.backup.AutoBackupPolicy
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.BackupRunResult
import com.sukisu.ultra.data.backup.CloudProfileBook
import com.sukisu.ultra.data.backup.CloudSlots
import com.sukisu.ultra.data.backup.KernelKmi
import com.sukisu.ultra.data.backup.PartitionSnapshotMeta
import com.sukisu.ultra.data.backup.RetentionLimit
import com.sukisu.ultra.data.backup.WebDavPreset
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
    /** 恢复任务专属进度；null 表示没有正在运行的恢复。 */
    val restoreRun: RestoreRunState? = null,
    /** 当前分页。默认停在「备份」：进这一页多半是要备一份。 */
    val tab: BackupTab = BackupTab.BACKUP,
    /**
     * 备份位置勾选（本机 / 云端）——只决定「立即备份」写到哪。
     *
     * 可以同时勾上：一次「立即备份」就写两份。默认只勾本机——云端没配好之前它本来也选不了。
     * 恢复列表不跟着这个勾选走：列表始终把本机和（已配置时）云端全量列出来（见
     * [BackupViewModel.refresh]），"备份写到哪"和"能恢复什么"是两件事。
     */
    val origins: Set<BackupOrigin> = setOf(BackupOrigin.LOCAL),
    /**
     * 勾选的内容（模块 / 分区快照 / 仓库源配置）。同样可以同时勾上。
     *
     * 仓库源配置默认勾上：它就几 KB，去重规则保证源没变时重复备份不产生新条目——而"卸载
     * 重装管理器后仓库源全没了"恰恰是用户最常吃的一次亏，默认兜住比让人记得勾划算。
     */
    val kinds: Set<BackupKind> = setOf(BackupKind.MODULE, BackupKind.REPO_CONFIG),
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
     * 详情页里被收起来的那些来源（本机 / 云端）。
     *
     * 本机与云端各有一份时，两侧分成两段显示，每段自己收放：只想看云端那份时，不必把本机那
     * 几行也摊在屏幕上。默认全部展开——进这一页就是来看"这一份里有什么"的，先收起来等于把答
     * 案藏起来；只有条目多（一次装十几个模块）时才需要把按钮和列表拉回同一屏。
     *
     * 存"收起来的"而不是"展开的"：默认是展开，用空集当默认值就不必在每个入口重新灌一遍初值。
     */
    val openGroupCollapsed: Set<BackupOrigin> = emptySet(),
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
     * 云端上传分区快照要几十秒到几分钟，只有一个不动的按钮用户没法判断是不是卡住了；跑完之后
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
     * 当前在配哪一家（[CloudProfileBook] 的槽位名，见 [CloudSlots]）。
     *
     * 表单里那三个框显示的是**这一家**记着的值，不是"全局唯一一份"。芯片的高亮也看它——
     * 原来靠地址去比对模板反推，用户改过地址（Nextcloud 的模板带 `USERNAME`，必须改）之后
     * 就推不出来了，芯片会莫名其妙全灭。
     */
    val cloudSlot: String = CloudSlots.CUSTOM,
    /**
     * 记着可用配置的槽位（[CloudProfileBook.remembered]）：芯片上给这几个打点。
     *
     * 不打点的话，用户切到别家再切回来只能靠记忆确认"我到底配没配过这家"。
     */
    val cloudRememberedSlots: Set<String> = emptySet(),
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
     * 空列表的原因不止一种——「还没有备份，点立即备份」和「云端清单读不出来」完全是两件事——
     * 界面上什么都不显示的话，用户会以为功能坏了。内容由 ViewModel 在刷新后按结果给，
     * 不再按备份内容分型（恢复列表与勾选解耦后，「没勾分区」和列表里有没有分区无关）。
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
     * 保留额度：模块留最近几次备份、分区快照留几次。
     *
     * 以前这两个是代码里的常量，界面上看不见也改不了——用户只能从"我的第六份备份不见了"反推出
     * 有这么个限制。默认值在 [BackupDefaults]，取值范围在 [RetentionLimit]。
     */
    val moduleRetention: Int = BackupDefaults.RETENTION,
    val partitionRetention: Int = BackupDefaults.PARTITION_RETENTION,

    /**
     * 分区快照是否 gzip 压缩。
     *
     * 默认开：boot 分区 96MB 压完约 20MB，云端备份的上传时间差好几倍。关掉只在一种场景有意义
     * ——想直接拿 `.img` 去别的刷机工具里用（那类工具多半不认 .gz）。
     */
    val partitionCompressed: Boolean = BackupDefaults.PARTITION_COMPRESSED,
    /** 保留额度那一块是否展开。它是"设一次就不管"的设置，摊开会把备份选项挤下去。 */
    val retentionExpanded: Boolean = false,
) {
    /**
     * 现在按「立即备份」有没有意义。
     *
     * 模块一个都没勾时按下去只会得到"写入 0 项"——那不是结果，是用户还没勾完。位置和内容都
     * 空着同理。列表读不出来时不能拦（那是一次读失败，不是用户的选择）；一个模块都没装时
     * 同理——没有可勾的对象，不能因此把分区快照 / 仓库源那两项也一起锁死。
     */
    val canBackUp: Boolean
        get() = origins.isNotEmpty() &&
            kinds.isNotEmpty() &&
            (
                BackupKind.MODULE !in kinds ||
                    modulesUnavailable ||
                    modules.isEmpty() ||
                    selectedModuleIds.isNotEmpty()
                )

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

    /**
     * 同上，集合形态——「自动备份位置」那个下拉框用它。
     *
     * 判定仍然只有 [autoBackupTargets] 一处：下拉框指着哪一档，数据层就写哪几处，不会出现
     * "框里说云端、实际没写"。
     */
    val autoBackupOrigins: Set<BackupOrigin>
        get() = buildSet {
            if (autoBackupTargets.local) add(BackupOrigin.LOCAL)
            if (autoBackupTargets.cloud) add(BackupOrigin.CLOUD)
        }

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

    /**
     * 打开的这一份备份里，某一侧（本机 / 云端）的勾选情况。
     *
     * 两侧分开成两段之后，"全选"是按段算的：分母是这一侧的行数，不是整组的行数。放在这里而不是
     * 两个主题各算一遍——各写一份，迟早有一边把分母写成整组，于是"本机 1/1"被读成"整组 1/4"，
     * 那一侧的勾选框再也画不出全选。
     */
    fun picksOf(origin: BackupOrigin): OriginPicks {
        val rows = openGroup?.rows.orEmpty().filter { it.origin == origin }
        return OriginPicks(picked = rows.count { it.id in openGroupSelected }, total = rows.size)
    }
}

/**
 * 某一侧条目的勾选情况，供详情页那一段的标题行画勾选框。
 */
@Immutable
data class OriginPicks(
    /** 这一侧勾上了几条。 */
    val picked: Int,
    /** 这一侧一共几条。 */
    val total: Int,
) {
    /** 这一侧全都勾上了。空的一段不算——没东西可勾的段不该画成"全选"。 */
    val allSelected: Boolean get() = total > 0 && picked == total

    /** 勾了一部分：标题行的勾选框画成半选，收起来也看得出刚挑过。 */
    val partial: Boolean get() = picked in 1 until total
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
 * 从分区行们的边车 meta 里拿快照的内核版本（`uname -r`）。
 *
 * 同一次快照的每份 meta 共享同一个内核版本：读到第一份非空就停，[readMeta] 只对还没读出
 * 结果的行调用——避免为一次确认把云端的三份 meta 全拉一遍。全都没有（旧快照没记这个字段、
 * 导入的归档、meta 读失败）返回 null，恢复确认按「KMI 未知」走普通路径。
 *
 * 抽成纯函数（meta 读取由调用方注入）是为了能上 JVM 单测：真机路径要 root shell 和 WebDAV。
 */
internal suspend fun snapshotKernelReleaseOf(
    partitions: List<BackupRow>,
    readMeta: suspend (BackupRow) -> PartitionSnapshotMeta?,
): String? = partitions.firstNotNullOfOrNull { row ->
    readMeta(row)?.kernelRelease?.takeIf { it.isNotBlank() }
}

/**
 * 详情页里勾选/取消一行之后的下一个勾选集合。
 *
 * 分区快照的各行是**联动**的：一次快照是「内核 ↔ ksu.ko ↔ ramdisk」同刻的配对集，
 * 只刷回其中一两个会把配对拆散（内核和模块 vermagic 对不上、root 直接失效）——所以点任何
 * 一行分区，整组分区（跨本机/云端两侧）一起勾上或一起取消。内容层面的「只刷一份」仍由
 * [oneCopyPerContent] 去重保证：两侧都勾时只刷本机那份。
 *
 * 其他行（模块、仓库源）维持单选：单独勾选本来就是它们的正当用法。
 *
 * 提成纯函数是为了能上 JVM 单测——这正是 [pruneSelection] 的先例。
 */
internal fun entryToggleSelection(
    selected: Set<String>,
    rows: List<BackupRow>,
    rowId: String,
): Set<String> {
    val row = rows.firstOrNull { it.id == rowId } ?: return selected
    if (row.kind != BackupKind.PARTITION) {
        return if (rowId in selected) selected - rowId else selected + rowId
    }
    val partitionIds = rows.filter { it.kind == BackupKind.PARTITION }.mapTo(mutableSetOf()) { it.id }
    // 有一行没勾上就整组补齐；已经全勾了才整组取消——checkbox 的点击语义按「当前是否全选」走。
    return if (partitionIds.any { it !in selected }) {
        selected + partitionIds
    } else {
        selected - partitionIds
    }
}

/**
 * 恢复确认前的内核版本比对结果：快照拍它时的 `uname -r` 和当前系统的 `uname -r`。
 *
 * 只有能**认定**两侧 KMI 不同（[KernelKmi] 解析两边都成功且键不一致）才会构造它；
 * 界面把两个版本号摆进警告框，决定权留给用户——同 KMI 的月度 OTA 之后刷旧快照是正当
 * 操作，数据层不该硬拒。
 */
@Immutable
data class PartitionKmiCheck(
    /** 快照拍摄时的 `uname -r`。 */
    val snapshotRelease: String,
    /** 当前系统的 `uname -r`。 */
    val currentRelease: String,
)

/**
 * 备份弹窗的状态：正在跑哪个目标、推了多少字节、多快，跑完则是结果。
 */
@Immutable
data class RestoreRunState(
    val current: Int,
    val total: Int,
    val label: String,
    val done: Boolean = false,
    val cancelled: Boolean = false,
    /**
     * 跑完之后的结果话术（"已恢复 3 项" / "已恢复 2 项 · 1 项失败"）。
     *
     * 结果留在弹窗里，而不是只发一条几秒就消失的 snackbar：恢复是一串 root 操作，
     * 用户按完就去干别的了，回来看时弹窗必须自己说清动了几个、错了几个。
     */
    val result: String = "",
    /** 失败明细（哪一项、为什么），与结果分开列，弹窗里逐行显示。 */
    val failures: List<String> = emptyList(),
    /**
     * 恢复的内容里有没有要重启才生效的（模块、分区快照要；仓库源只换 prefs 不要）。
     * 弹窗据此把"重启生效"连着重启入口一起递到眼前——这条话只挂在页面 snackbar 上时，
     * 用户弹窗一关、眼睛还停在弹窗原来的位置，提示等于没说。
     */
    val needsReboot: Boolean = false,
)

@Immutable
data class BackupRunState(
    /** 第几个目标（1 起），和 [total] 一起显示"2/4"。 */
    val current: Int,
    val total: Int,
    /**
     * 正在备哪一类内容。
     *
     * 目标是"内容"不是"位置 × 内容"：同一批模块打包一遍就写到勾选的每一处，所以来源不再是目标的
     * 一部分——写到哪一处由 [storage] 在传输阶段报出来。
     */
    val kind: BackupKind,
    val sentBytes: Long = 0,
    /** 这次目标的归档总字节数；0 表示还不知道（没有可传的东西）。 */
    val totalBytes: Long = 0,
    /** 这个目标里的第几个归档（1 起）、一共几个；0 表示还没开始报。 */
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    /**
     * 正在写哪个后端（[com.sukisu.ultra.data.backup.BackupStorage.id]）。
     *
     * 一次备份依次写每一个勾选的来源，而字节数是跨来源累计的、不回头：不把当前这一处报出来，
     * 用户看不出本机已经写完、正在传云端。打包阶段还没有后端可讲，那时是空串。
     */
    val storage: String = "",
    /**
     * 还在打包：源正在把模块打成 zip / 把原厂镜像读出来，字节进度还没开始。
     *
     * 打包这一段没有任何字节数可报，却常常是整个备份里最慢的一段。它原来在界面上就是"进度条停在
     * 0、下面写着 0 B / 0 B"——看着跟卡死一样。有了这个标记，弹窗那一段改画不确定进度并写明
     * "正在打包第几个"（见 [packedIndex]）。
     */
    val packing: Boolean = false,
    /** 打包到第几个归档、共几个；0 表示还不知道（源还没开口）。 */
    val packedIndex: Int = 0,
    val packedCount: Int = 0,
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
    /**
     * 跑完之后的结果表（正常跑完才有；取消时只有 [result] 那一句话）。
     *
     * 弹窗把它排成"来源分组 + 内容/结果两列"：以前是一串
     * "本地 模块：这 10 项与已有备份内容相同，未重复备份 · 云端 模块：已备份 10 项 · …"，
     * 同一句跳过说明重复到每一行，用户得逐行读才知道哪边写了、哪边没写。
     */
    val resultTable: BackupResult? = null,
    /**
     * 这一次是用户取消的。
     *
     * 和"失败"分开：取消是用户自己要的，结果里不该出现失败清单，也不该再报平均速度——那份只
     * 传了一半，算出来的数字没错，但读起来像一次成绩。
     */
    val cancelled: Boolean = false,
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

/**
 * 结果表里的一行：这一次往哪一侧（[origin]）备了什么（[kind]），结果如何（[outcome]）。
 *
 * 一行一个目标，弹窗按来源分组、排成"内容 / 结果"两列。短句只说这一格的结果
 * （[com.sukisu.ultra.ui.util.BackupText.outcomeCell]），失败原因排在表下面——
 * 那是整行宽度的东西，挤进右列会把列宽撑歪。
 */
@Immutable
data class BackupResultRow(
    val origin: BackupOrigin,
    val kind: BackupKind,
    val outcome: String,
)

/**
 * 一次备份跑完之后的全部结果。
 *
 * 表格只装"哪一侧、哪类内容、结果如何"；失败清单和释义分开拿，是因为它们在界面上不占表格的列
 * （失败原因整行宽度、释义只出现一次）。
 */
@Immutable
data class BackupResult(
    val rows: List<BackupResultRow>,
    /** 表下面的失败清单（已本地化、已去重）。 */
    val failures: List<String> = emptyList(),
    /** 表下面那条释义，没有就不写。 */
    val note: String? = null,
)

@Immutable
data class BackupActions(
    val onBack: () -> Unit,
    /** 切到「备份」或「恢复」分页。 */
    val onSelectTab: (BackupTab) -> Unit,
    val onToggleOrigin: (BackupOrigin) -> Unit,
    /** 整批指定写到哪几处（下拉框那三项：本地 / 云端 / 本地+云端）。 */
    val onSelectOrigins: (Set<BackupOrigin>) -> Unit,
    val onToggleKind: (BackupKind) -> Unit,
    val onBackup: () -> Unit,
    /** 点开一次备份看详情。**不在这里恢复任何东西**——列表行点一下就恢复等于给误触点了个火。 */
    val onOpenGroup: (BackupGroup) -> Unit,
    val onCloseGroup: () -> Unit,
    /** 详情页：展开/收起某一侧的条目（点那一段的标题行）。 */
    val onToggleGroupOrigin: (BackupOrigin) -> Unit,
    /** 详情页：勾选/取消一个条目。 */
    val onToggleGroupEntry: (String) -> Unit,
    /** 详情页：把某一侧的条目一次全勾上 / 全取消。 */
    val onSetOriginEntries: (BackupOrigin, Boolean) -> Unit,
    /** 恢复勾中的那些条目。分区快照的那次二次确认由界面在调它之前问。 */
    val onRestoreSelected: () -> Unit,
    /** 导出/分享勾中的那些条目（一条给原样归档，多条打成一个 zip）。 */
    val onShareSelected: () -> Unit,
    /**
     * 删掉勾中的那些条目。界面先问一次。
     *
     * 跟着勾选走而不是"删整组"：本机与云端各有一份时，"删掉整组"和"删掉我勾的这一份"是两件
     * 事，只勾了本地的人心里想的是本地那份（见 BackupViewModel.deleteChecked）。
     */
    val onDeleteChecked: () -> Unit,
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
    /** 取消正在跑的备份：已经写好的那几份留着，传了一半的那份停下。 */
    val onCancelBackupRun: () -> Unit,
    val onCancelRestore: () -> Unit,
    val onDismissRestoreRun: () -> Unit,
    val onImport: () -> Unit,
    val onSetAutoBackup: (Boolean) -> Unit,
    /** 自动备份写到哪几处：和手动备份的「备份位置」同一套三档（本地 / 云端 / 本地+云端）。 */
    val onSelectAutoBackupOrigins: (Set<BackupOrigin>) -> Unit,
    /** 保留额度：模块 / 分区快照各几次。值由数据层钳过，界面传原值即可。 */
    val onSetModuleRetention: (Int) -> Unit,
    val onSetPartitionRetention: (Int) -> Unit,

    /** 分区快照是否压缩。 */
    val onSetPartitionCompressed: (Boolean) -> Unit,
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
