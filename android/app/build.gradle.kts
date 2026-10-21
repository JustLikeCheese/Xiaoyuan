import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 版本号和桌面端共用 backend.py 里的 APP_VERSION，发版时只改一处
val appVersion: String = Regex("^APP_VERSION = \"(.+)\"", RegexOption.MULTILINE)
    .find(rootDir.resolve("../backend.py").readText())?.groupValues?.get(1)
    ?: error("backend.py 里找不到 APP_VERSION")

val minSdkVersion = 26

android {
    namespace = "io.github.justlikecheese.xiaoyuan"
    ndkVersion = "30.0.16248370"   // 只用来编译保活守护（src/main/cpp/guardd.c）
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.justlikecheese.xiaoyuan"
        minSdk = minSdkVersion
        targetSdk = 36
        versionName = appVersion
        versionCode = appVersion.split(".").map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 还没有正式签名：先用调试签名，能直接覆盖安装调试版（Compose 调试版很卡，测流畅度要用发布版）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // 保活守护是可执行文件，要解压到安装目录（lib/）里才能被 root、shell 执行
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * 用 NDK 的 clang 把保活守护 src/main/cpp/guardd.c 编译成各架构的可执行文件。命名成 libguardd.so 是为了让它
 * 作为“原生库”打包进 APK 的 lib/<abi>/，安装时解压到应用的原生库目录（只读，别的应用改不了）。只依赖 libc。
 */
abstract class BuildGuardd : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val os = System.getProperty("os.name").lowercase()
        val host = when {
            os.startsWith("windows") -> "windows-x86_64"
            os.startsWith("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val clang = ndkDir.get().file("toolchains/llvm/prebuilt/$host/bin/clang" + if (host.startsWith("windows")) ".exe" else "").asFile
        val abis = mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "armv7a-linux-androideabi",
            "x86_64" to "x86_64-linux-android",
        )
        for ((abi, triple) in abis) {
            val out = outputDir.get().dir(abi).file("libguardd.so").asFile
            out.parentFile.mkdirs()
            exec.exec {
                commandLine(
                    clang.absolutePath, "--target=$triple${minSdk.get()}", "-O2", "-Wall", "-Wextra", "-Wno-sign-compare", "-Werror",
                    "-fPIE", "-pie", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections", "-s",
                    "-o", out.absolutePath, source.get().asFile.absolutePath,
                )
            }
        }
    }
}

val buildGuardd = tasks.register<BuildGuardd>("buildGuardd") {
    source.set(layout.projectDirectory.file("src/main/cpp/guardd.c"))
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    minSdk.set(minSdkVersion)
    outputDir.set(layout.buildDirectory.dir("generated/guardd"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildGuardd, BuildGuardd::outputDir)
}

tasks.withType<Test>().configureEach {
    // 协议测试连仓库里的 tests/mock_portal.py，和桌面端共用同一个假门户
    systemProperty("mockPortal", rootDir.resolve("../tests/mock_portal.py").absolutePath)
    systemProperty("python", providers.environmentVariable("PYTHON").getOrElse("python"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.android.embedded)   // 账号迁移：扫码、生成二维码
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
