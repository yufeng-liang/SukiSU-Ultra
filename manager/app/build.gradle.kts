@file:Suppress("UnstableApiUsage")

import com.google.protobuf.gradle.id
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidCompileNdkVersion = rootProject.extra["androidCompileNdkVersion"] as String
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
val managerVersionCode = rootProject.extra["managerVersionCode"] as Int
val managerVersionName = rootProject.extra["managerVersionName"] as String

val isPrBuild = project.findProperty("IS_PR_BUILD")?.toString()?.toBoolean() ?: false

/**
 * 包名默认值的唯一来源是仓库根的 manager-package.properties。
 *
 * 这里刻意不写 "com.xxx" 字面量：内核编译期宏 KSU_PACKAGE_NAME 必须与
 * applicationId 逐字节相同，两处各留一份默认值就是"只改一半"的全部来源。
 * 同一份文件还被 kernel/manager-package.mk 与 userspace/ksud/build.rs 读取。
 */
val managerPackageSingleSource = rootProject.file("../manager-package.properties")

fun managerPackageSingleSourceProperty(key: String): String {
    require(managerPackageSingleSource.isFile) {
        "找不到包名单一真源 ${managerPackageSingleSource.absolutePath}；" +
            "请从仓库根构建，或用 -PKSU_PACKAGE_NAME=<package> 显式指定"
    }
    // 注意：不能写 java.util.Properties —— Gradle Kotlin DSL 里 `java` 会被
    // JavaPluginExtension 的访问器遮蔽，导致 "Unresolved reference 'util'"。
    val properties = Properties()
    managerPackageSingleSource.reader(Charsets.UTF_8).use { properties.load(it) }
    return properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("${managerPackageSingleSource.name} 缺少 $key")
}

val defaultManagerPackageName = managerPackageSingleSourceProperty(
    if (isPrBuild) "KSU_MANAGER_PACKAGE_NAME_PR" else "KSU_MANAGER_PACKAGE_NAME"
)
val defaultManagerName = if (isPrBuild) "SukiSU PR" else "SukiSU"
val managerPackageName = project.findProperty("KSU_PACKAGE_NAME")?.toString() ?: defaultManagerPackageName
val managerName = project.findProperty("KSU_NAME")?.toString() ?: defaultManagerName

// CI 一致性校验入口：打印 Gradle 真正会写进 APK 的 applicationId，
// 由 scripts/check_manager_package.py 与内核侧取值逐字节比对。
tasks.register("printManagerPackageName") {
    description = "Print the effective applicationId for the CI package-name check"
    doLast { println(managerPackageName) }
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        ofNonTest().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

val baseCFlags = listOf(
    "-Wall", "-Qunused-arguments", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
    "-fno-exceptions", "-fno-stack-protector", "-fomit-frame-pointer",
    "-Wno-builtin-macro-redefined", "-Wno-unused-value", "-D__FILE__=__FILE_NAME__"
)
val baseCppFlags = baseCFlags + "-fno-rtti"

android {
    // 这是源码命名空间（R 类 / 编译期包名），与安装到系统里的包名（applicationId）无关，
    // 保持不动即可。安装包名见仓库根 manager-package.properties。
    namespace = "com.sukisu.ultra"

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_CXX_FLAGS_DEBUG=-Og", "-DCMAKE_C_FLAGS_DEBUG=-Og")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    arguments += "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.get().asFile.absolutePath}/symbols"
                    arguments += "-DCMAKE_BUILD_TYPE=Release"

                    val releaseFlags = listOf(
                        "-flto", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
                        "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-Wl,--exclude-libs,ALL"
                    )
                    val configFlags = listOf("-Oz", "-DNDEBUG").joinToString(" ")

                    cppFlags += releaseFlags
                    cFlags += releaseFlags

                    arguments += listOf(
                        "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                        "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=all -s -Wl,--hash-style=sysv -Wl,-z,norelro"
                    )
                }
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        resValues = true
        compose = true
        prefab = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
    }
    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion
    ndkVersion = androidCompileNdkVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        applicationId = managerPackageName

        buildConfigField("boolean", "IS_PR_BUILD", isPrBuild.toString())
        val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        buildConfigField("String", "MANAGER_COMMIT", "\"$commit\"")
        // LKM 指纹：kernel/ 子树的 tree hash。内核编译时会把同一个值编进版本串
        // （v4.3.0-<commit>@<branch>+lkm.<rev>），首页据此判断这次升级是否真的带来了
        // 新的 .ko —— 指纹一致就不催用户再刷一次内核。CI 通过 -PlkmRev 显式传入，
        // 本地构建则自己算。
        val lkmRev = (findProperty("lkmRev") as String?)?.trim()?.takeIf { it.isNotEmpty() }
            ?: providers.exec {
                commandLine("git", "rev-parse", "--short=8", "HEAD:kernel")
            }.standardOutput.asText.get().trim()
        buildConfigField("String", "LKM_REV", "\"$lkmRev\"")
        resValue("string", "app_name", managerName)

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                cFlags += baseCFlags + "-std=c2x"
                cppFlags += baseCppFlags + "-std=c++2b"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "riscv64")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.addAll(listOf("META-INF/**", "kotlin/**", "**.bin"))
    }
}

base {
    archivesName.set(
        "${managerName.replace(" ", "_")}_${managerVersionName}_${managerVersionCode}"
    )
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    implementation(libs.androidx.webkit)

    implementation(libs.lsposed.cxx)

    implementation(libs.hiddenapibypass)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)

    implementation(libs.reorderable)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.material.kolor)

    implementation(libs.appiconloader)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.protobuf.kotlin.lite)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

val kpmToolsDir = layout.buildDirectory.dir("generated/kpmTools")
val downloadKpmTools = tasks.register("downloadKpmTools") {
    description = "Download the latest KPM tools to bundle in the APK"
    outputs.dir(kpmToolsDir)
    // Keep the downloaded tools in the build directory. Once both files are
    // present, subsequent builds use the local copies without contacting GitHub.
    outputs.upToDateWhen {
        val root = kpmToolsDir.get().asFile
        root.resolve("jniLibs/arm64-v8a/libkptools.so").isFile &&
            root.resolve("assets/kpm/kpimg").isFile
    }
    doLast {
        val baseUrl = "https://raw.githubusercontent.com/ShirkNeko/SukiSU_patch/refs/heads/main/kpm"
        val downloads = listOf("kptools-android", "kpimg").associateWith { name ->
            val target = temporaryDir.resolve(name)
            val url = "$baseUrl/$name"
            try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 20_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "SukiSU-Manager-build")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Cannot download $name: HTTP ${connection.responseCode}"
                    }
                    val expectedSize = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    check(target.length() >= 1024 && (expectedSize < 0 || target.length() == expectedSize)) {
                        "Incomplete KPM tool download: $name"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (networkError: Exception) {
                logger.warn("Java HTTPS download failed for $name; retrying with curl", networkError)
                val process = ProcessBuilder("curl", "--fail", "--location", "--retry", "3", "--silent", "--show-error", "--output", target.absolutePath, url)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0) { "Cannot download $name with curl: ${output.trim()}" }
                check(target.length() >= 1024) { "Incomplete KPM tool download: $name" }
            }
                if (name == "kptools-android") {
                    val header = target.inputStream().use { it.readNBytes(4) }
                    check(header.contentEquals(byteArrayOf(0x7f.toByte(), 0x45, 0x4c, 0x46))) {
                        "Downloaded kptools-android is not an ELF file"
                    }
                    logger.warn("kptools-android architecture check skipped; verify it is ARM64 before releasing")
                }
                logger.lifecycle("Downloaded $name (${target.length()} bytes)")
                target
            }
        // Publish only after both downloads have passed validation.
        val root = kpmToolsDir.get().asFile
        downloads.forEach { (name, source) ->
            val relativePath = if (name == "kptools-android") "jniLibs/arm64-v8a/libkptools.so" else "assets/kpm/kpimg"
            val output = root.resolve(relativePath)
            check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Cannot create $output" }
            source.copyTo(output, overwrite = true)
        }
    }
}
android.sourceSets.getByName("main").apply {
    // The generated directories are populated by downloadKpmTools before preBuild.
    // Register them directly to avoid the deprecated srcDir(Any) overload.
    assets.directories.add(kpmToolsDir.get().dir("assets").asFile.toString())
    // Native libraries are extracted to an executable location by Android.
    jniLibs.directories.add(kpmToolsDir.get().dir("jniLibs").asFile.toString())
}
tasks.named("preBuild").configure { dependsOn(downloadKpmTools) }
