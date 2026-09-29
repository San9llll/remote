plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "lo.naui"
    compileSdk = 37

    defaultConfig {
        applicationId = "lo.naui"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"

        // 只打 arm64-v8a。
        // 一来机器就是 arm64，多带别的 ABI 纯属白占体积；
        // 二来 Android 15+ 对 native 库有 16KB page size 要求，
        // 混进旧的其它 ABI 库最容易在启动时炸在这儿。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 带日志的那版，包名多一层，可以和正式版装在同一台手机上
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "VERBOSE_LOG", "true")
            resValue("string", "app_name", "Nakour·调试")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            // 正式版不带日志
            buildConfigField("boolean", "VERBOSE_LOG", "false")
            resValue("string", "app_name", "Nakour")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // resValue（按构建类型给应用名）要显式打开
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.backdrop)
    // Shizuku：给没 root、但有 adb 的人用（识别 + 之后拿系统能力）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.capsule)

    implementation(libs.kotlinx.coroutines.android)
}
