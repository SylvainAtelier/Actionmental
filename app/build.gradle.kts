import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 发布签名材料的两条来源，优先级从上到下：
 *   1. .local/signing/keystore.properties（本地出包用，已 gitignore）
 *   2. 四个环境变量（CI 用，由 release.yml 从 Secret 还原后写入 GITHUB_ENV）
 * 两者都没有时 release 不签名，只能自己装来调试，不能分发。
 */
val localSigningDir = rootProject.file(".local/signing")
val keystoreProperties = Properties().apply {
    val propertiesFile = localSigningDir.resolve("keystore.properties")
    if (propertiesFile.exists()) FileInputStream(propertiesFile).use { load(it) }
}

fun secret(key: String, envName: String): String? =
    keystoreProperties.getProperty(key) ?: providers.environmentVariable(envName).orNull

/**
 * versionCode 由 versionName 推导：`1.2.3` → `10203`。
 *
 * 这样版本号只有一个来源 —— tag。本地和 CI 只要传同一个 versionName，出来的
 * versionCode 必然相同，两边的产物才谈得上「是同一个包」。
 * 早先 CI 用 workflow 的 run_number，本地怎么传都对不上，重跑一次 workflow
 * 也会让同一份代码得到不同的 versionCode。
 *
 * 仍然单调递增，所以降级保护还在，以后要上架商店也不用改。
 */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".")
    val numbers = parts.mapNotNull { it.toIntOrNull() }
    if (parts.size != 3 || numbers.size != 3 || numbers.any { it < 0 }) {
        throw GradleException("versionName 必须是 MAJOR.MINOR.PATCH 三段数字，收到的是 '" + name + "'")
    }
    val (major, minor, patch) = numbers
    // 每段留两位，超了就会串位：1.0.100 和 1.1.0 会算成同一个数。
    if (minor > 99 || patch > 99) {
        throw GradleException("versionName 的 MINOR 与 PATCH 不能超过 99，收到的是 '" + name + "'")
    }
    return major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.actionmental"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.actionmental"
        // Tile.setSubtitle 是磁贴三态显示的核心，需要 API 29；
        // 而 wm ignore-orientation-request 实际要 Android 12+，低版本只会显示 UNKNOWN。
        minSdk = 29
        targetSdk = 36
        // versionName 由 CI 从 tag 算出后用 -P 传入，本地不传就走默认值。
        // versionCode 跟着它推导，不单独传 —— 两边只要 versionName 一致，包就一致。
        // -PversionCode 仍然认，是给「设备上装着更高版本号」这种局面留的逃生口。
        val appVersionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
        versionName = appVersionName
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: versionCodeOf(appVersionName)
        resourceConfigurations += listOf("zh-rCN", "en")
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = secret("storeFile", "ANDROID_KEYSTORE_PATH")
            if (storePath != null) {
                val configuredStore = File(storePath)
                storeFile = if (configuredStore.isAbsolute) configuredStore else localSigningDir.resolve(storePath)
                storePassword = secret("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = secret("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 包名与应用名都带后缀，debug 与 release 可以在同一台设备上并存。
            // 这对本应用尤其必要：无障碍服务和快捷设置磁贴都按应用名列出，
            // 两个同名条目会让人分不清正在调试哪一个（见 src/debug/res/values/strings.xml）。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有签名材料时留空而不是退回 debug 签名——签名一旦不一致，
            // 用户覆盖安装必然「签名冲突」。
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // local.properties 是本机文件且已 gitignore；lint 的 PropertyEscape 建议与其自身校验互相矛盾。
        disable += "PropertyEscape"
        warningsAsErrors = false
        abortOnError = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
