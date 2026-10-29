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
extra["managerVersionName"] = getVersionName()

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
 * 版本名只做品牌标识，版本号不重复进来：APK 输出名规则是
 * `SukiSU_<versionName>_<versionCode>`，而 versionCode（4*10000 + 提交数 - 2815）
 * 每次提交都会变，塞进 versionName 只会让文件名出现两遍同样的数字。
 * 版本号在文件名末尾和首页「mod (41150-xxx)」里各出现一次，够了。
 */
fun getVersionName(): String {
    return "mod"
}