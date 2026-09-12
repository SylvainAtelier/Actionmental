pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
/**
 * 覆盖 AGP 自带的 R8。
 *
 * AGP 8.13.1 捆绑的 R8 8.13.6 里那份 kotlin-metadata 还不认识 Kotlin 2.3 写出的
 * 元数据版本，于是每编译一个带 @Metadata 的类就打一句
 * "An error occurred when parsing kotlin metadata"（CI 一次发布刷了 88 行）。
 * R8 读不出元数据就只能保守处理，与之相关的优化（如 Kotlin 特有的 lambda /
 * data class 收缩）会静默退化，所以这不只是日志噪声。
 *
 * 8.13.23 是同一 R8 主线的最新补丁版，只换 R8 不动 AGP，兼容性风险最小。
 * 升级 AGP 时把这里一并升到对应主线的最新补丁版，或在 R8 已内置新版
 * kotlin-metadata 后删掉整个 block。
 *
 * 注意：必须写在 settings 里。本项目用 plugins DSL 应用 AGP，插件由 settings
 * 的 buildscript classloader 加载，写在根 build.gradle.kts 里是不生效的。
 */
buildscript {
    repositories {
        google()
    }
    dependencies {
        classpath("com.android.tools:r8:8.13.23")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Actionmental"
include(":app")
