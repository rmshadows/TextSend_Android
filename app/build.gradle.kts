plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.rmshadows.textsend"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.rmshadows.textsend"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "5.0.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI 通过环境变量传入 keystore。未设置时 release 保持未签名，本地构建行为不变。
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/androidx.annotation_annotation-experimental.version",
            )
        }
    }
}

// AGP 8.4 会把 annotation-experimental 的空 R 打进 project_dex_archive 两次，
// 合并 dex 报 R is defined multiple times。该库只有编译期注解，运行时不需要。
configurations.configureEach {
    exclude(group = "androidx.annotation", module = "annotation-experimental")
}

dependencies {
    // AndroidX 版本 CameraScan 要求 compileSdkVersion >= 33
    implementation("com.github.jenly1314:camera-scan:1.0.1")
    // AndroidX 版本 取景框
    implementation("com.github.jenly1314:viewfinderview:1.1.0")
    // AndroidX 版本ZXing Lite
    implementation("com.github.jenly1314:zxing-lite:3.0.1")
    // Google Gson
    implementation("com.google.code.gson:gson:2.10.1")
    // 原项目依赖
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}