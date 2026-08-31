import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/** Windows 版の版番号。持ち運び版の名前と MSI の両方で使う。 */
val desktopVersion = "1.4.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // ルールエンジンはそのまま。条件・アクション・ゲートは Android と同じものが動く
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    // compose.material3 は非推奨と言われるが、直接座標を書くと版が別系列で追従が面倒になる。
    // 版を揃えてくれるこちらを使う。
    @Suppress("DEPRECATION")
    implementation(compose.material3)

    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

/**
 * インストール不要の持ち運び版。JRE ごと固めるので、展開してそのまま動く。
 * `gradlew :desktop:packagePortable`
 */
tasks.register<Zip>("packagePortable") {
    group = "compose desktop"
    description = "app イメージを zip に固める(管理者権限なしで動かせる)"
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    // 版を名前に入れる。入れないと、どれを渡したのか後から辿れない
    archiveFileName.set("dopachiru-windows-$desktopVersion.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
}

/** Win32 まわりが実機で動くかを確かめる。`gradlew :desktop:win32Smoke` */
tasks.register<JavaExec>("win32Smoke") {
    group = "verification"
    description = "前面ウィンドウの取得・アプリ列挙・プロセス一時停止を実際に叩く"
    mainClass.set("com.dopachiru.desktop.tools.Win32SmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "com.dopachiru.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // ブラウザ拡張の受け口(com.sun.net.httpserver)。
            // jlink で削られると、配布版でだけ拡張が繋がらなくなる
            modules("jdk.httpserver")
            packageName = "Dopachiru"
            packageVersion = desktopVersion
            // WiX の MSI 生成が非 ASCII で転ぶので、ここだけ英語にしてある
            description = "Dopachiru - self-imposed app usage limits"
            vendor = "dopachiru"

            windows {
                menu = true
                shortcut = true
                // 更新しても設定が引き継がれるように固定する
                upgradeUuid = "6f2e1b74-2a5d-4a0e-9d3a-1c7b5e0f8a21"
            }
        }
    }
}
