plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "lo.naui"
    compileSdk = 37

    // 终端要真 PTY，只能靠 native 里的 forkpty
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "lo.naui"
        minSdk = 26
        // 故意留在 28：Android 10 起，targetSdk>=29 的 app 禁止 exec 自己的数据目录，
        // 那 termux 的二进制（全在 $PREFIX/bin 下）就一个都跑不起来。
        // termux 官方也是这么绕的，代价是上不了 Play。
        targetSdk = 28
        versionCode = 67
        versionName = "0.67.0"

        // 只打 arm64-v8a。
        // 一来机器就是 arm64，多带别的 ABI 纯属白占体积；
        // 二来 Android 15+ 对 native 库有 16KB page size 要求，
        // 混进旧的其它 ABI 库最容易在启动时炸在这儿。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // 只编 C，不需要 C++ 运行时
                arguments += listOf("-DANDROID_STL=none")
            }
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

    // targetSdk 故意留在 28（绕开 Android 10+ 的 exec 限制），
    // 但 lint 会拿"上架 Google Play 要 33"来卡我们 —— 自己用的，关掉这条。
    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
        checkReleaseBuilds = false
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

    // LSPosed 模块用的 Xposed API。
    // compileOnly：只编译期要，**不打进 APK** —— 运行时那些类由框架提供。
    compileOnly("de.robv.android.xposed:api:82")
}
