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
        versionCode = 1
        versionName = "4.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    // 权限请求框架：https://github.com/getActivity/XXPermissions（未使用）
    implementation("com.github.getActivity:XXPermissions:18.5")
    // 原项目依赖
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}