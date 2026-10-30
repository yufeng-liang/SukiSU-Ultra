plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 26
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
// 版本号的唯一真源是 CI 的 determine-version job：它按同一公式算好后同时传给
// kernel（KSU_VERSION）和 ksud（KSU_VERSION_CODE），manager 再把同一个数当
// versionCode，三处才会一致。本地直接跑 gradle 时没有这个 property，才回退到数 git。
extra["managerVersionCode"] = providers.gradleProperty("ksuVersionCode").orNull?.toIntOrNull()
    ?: getVersionCode()
extra["managerVersionTag"] = getVersionTag()
extra["managerVersionName"] = getVersionName(extra["managerVersionCode"] as Int)

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    val major = 4
    val end = 2815
    return major * 10000 + commitCount - end
}

/**
 * 品牌标签，不含版本号。APK 输出名与模块脚本的 KSU_VER 环境变量用它：
 * 那两处要的是干净字符串（文件名里版本号本来就在末尾，KSU_VER 会进 shell 变量）。
 */
fun getVersionTag(): String {
    return "mod"
}

/**
 * 版本名，即装完之后系统「应用信息」里显示的那一串 —— 所以带上版本号，
 * 写成 `mod (41284)`。它只进 AndroidManifest 的 versionName，别拿它去拼
 * 文件名字或 KSU_VER：那两处用 [getVersionTag]，否则 APK 会变成
 * `SukiSU_mod (41284)_41284-release.apk`，同一个数字出现两遍。
 *
 * @param versionCode 与 versionName 一起进 manifest 的那个 versionCode，
 *   APK 文件名与首页展示也用它，保证四处口径一致。
 */
fun getVersionName(versionCode: Int): String {
    return "${getVersionTag()} ($versionCode)"
}