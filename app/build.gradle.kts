import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dopachiru"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dopachiru"
        /**
         * Android 10。手持ちのうち一番古い AQUOS R2 SH-03K が
         * 2020年3月の更新で Android 10 に上がったきり打ち止めなので、そこに合わせている。
         *
         * ここを下げるときに効いてくるもの:
         *  - 29 未満: `AppOpsManager.unsafeCheckOpNoThrow`(AppUsageRanking)が無い
         *  - 26 未満: core が使う java.time に脱糖(desugaring)が要る
         */
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "0.7.3"
    }

    signingConfigs {
        // 個人用サイドロード。Play に出さないので debug キーストアで署名する。
        // 同じ鍵で署名し続けるかぎり、アンインストールせずに上書き更新できる。
        create("sideload") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        /**
         * 実機で動かす用。
         *
         * 既定では R8 で圧縮する(63MB → 3MB)。圧縮しても条件・アクションの実装と
         * シリアライザが残ることは mapping.txt で確認済み。
         *
         * 圧縮が原因かどうかを切り分けたいときは `-PnoMinify=true` を付けてビルドする。
         */
        release {
            isMinifyEnabled = project.findProperty("noMinify") != "true"
            isShrinkResources = isMinifyEnabled
            signingConfig = signingConfigs.getByName("sideload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // 学習予定の時刻計算のように、Android を触らない部分だけを JVM で見る
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
