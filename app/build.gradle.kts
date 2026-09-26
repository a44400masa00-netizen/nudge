plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.studynudge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.studynudge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // 音声アシスタント機能（呼びかけ検出）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 音声アシスタント機能（端末内AI）。※このライブラリはビルドが失敗しやすい最大のリスク箇所。
    // 失敗する場合はこの1行を削除し、LocalLlm.kt / LocalModel.kt の中身を空実装に差し替えれば、
    // クラウド(Gemini)のみの音声アシスタントとして動作する。
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
}
