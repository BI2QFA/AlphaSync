import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "io.github.bi2qfa.alphasync"
    // ★ compileSdk 维持 37.1：实测（2026-10-04）材料3 1.5.0-alpha28 与 compose 1.13
    //   alpha 线的 13 个 AAR 元数据强制要求 ≥37/37.1 —— 降到 36 会被 checkAarMetadata 拒绝。
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // ★ 发布包名（GitHub 组织命名）：与代码包 namespace 分离是 AGP 的标准做法，
        //   全部源码的 package 声明无需跟着动。安装器身份以它为准。
        applicationId = "io.github.bi2qfa.alphasync"
        minSdk = 24          // Android 7.0（AlphaSync 需求：向下兼容到 7.0）
        targetSdk = 36
        // AlphaSync 1.0（vc 1，双端同步）：功能集 = 改名前的 2.12 代（倒序分页、
        //   小图 EXIF 随传捆绑包、PING JSON 实时包等）。**零兼容**：不与任何老版本
        //   互通（协议已无回退分支），两端必须一起装。
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("alphasync") {
            val signProps = Properties()
            val signFile = rootProject.file("../keys/signing.properties")
            if (signFile.exists()) signFile.inputStream().use { signProps.load(it) }
            storeFile = rootProject.file(
                "../keys/" + (signProps.getProperty("storeFile") ?: "AlphaSync.jks"),
            )
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
            // 手机端 v1+v2 显式开启（v3/v4 走 AGP 默认）
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // ★ 双端统一：debug/release 都用 AlphaSync 签名（keys/AlphaSync.jks，不入 git）
            signingConfig = signingConfigs.getByName("alphasync")
        }
        debug {
            signingConfig = signingConfigs.getByName("alphasync")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// ★ 强制核心 Compose 走**稳定线 1.12.1**（2026.09.00 稳定 BOM 的 pin 值）：
//   材料3 1.5.0-alpha28 的传递依赖会把 ui/foundation/runtime 顶到 1.13.0-alpha01，
//   只换 BOM 不动 force 的话，Gradle"取最高版本"仍会解析成 alpha —— 这里显式压回稳定。
//   材料3 本体保持 alpha28（MD3E API 需要）。运行期不兼容则回退（Q6/Q7 口径）。
configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.compose.animation:animation:1.12.1",
            "androidx.compose.animation:animation-core:1.12.1",
            "androidx.compose.foundation:foundation:1.12.1",
            "androidx.compose.foundation:foundation-layout:1.12.1",
            "androidx.compose.runtime:runtime:1.12.1",
            "androidx.compose.runtime:runtime-saveable:1.12.1",
            "androidx.compose.ui:ui:1.12.1",
            "androidx.compose.ui:ui-geometry:1.12.1",
            "androidx.compose.ui:ui-graphics:1.12.1",
            "androidx.compose.ui:ui-text:1.12.1",
            "androidx.compose.ui:ui-unit:1.12.1",
            "androidx.compose.ui:ui-util:1.12.1",
            "androidx.compose.ui:ui-tooling:1.12.1",
            "androidx.compose.ui:ui-tooling-preview:1.12.1",
            "androidx.compose.ui:ui-tooling-data:1.12.1",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // EXIF 方向与拍摄参数读取（竖屏照片的方向还原、预览器"照片信息"弹窗）
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
