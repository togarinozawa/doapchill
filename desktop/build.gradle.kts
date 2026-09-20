import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/** Windows 版の版番号。持ち運び版の名前と MSI の両方で使う。 */
val desktopVersion = "1.20.0"

/**
 * サーバーに自動で繋ぐための入口の鍵。`local.properties` の `dopa.enrollKey`。
 *
 * **リポジトリには置きません**(公開しているので)。無ければ空のまま入り、
 * 自動で繋ぐ機能だけが黙って止まります ── 手で合言葉を入れる道は残るので、
 * 鍵を持っていない人がクローンしてもビルドは通ります。Android 側と同じ扱い。
 */
val enrollKey: String = rootProject.file("local.properties").let { file ->
    if (!file.exists()) "" else Properties()
        .apply { file.inputStream().use { load(it) } }
        .getProperty("dopa.enrollKey", "")
}

/**
 * 鍵を持ち物として同梱する。ソースに書かないのは、**書いた瞬間に公開される**ため。
 *
 * 生成物の中には入るので、配ったものからは読めます。そこは Android と同じ割り切り。
 */
val enrollKeyResource: Provider<RegularFile> =
    layout.buildDirectory.file("generated/enroll/dopachiru-enroll.txt")

tasks.register("writeEnrollKey") {
    val out = enrollKeyResource
    val key = enrollKey
    outputs.file(out)
    doLast {
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText(key)
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/enroll"))

tasks.named("processResources") { dependsOn("writeEnrollKey") }

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
 * 配る用のインストーラを `dist/` に版つきで置く。
 * `gradlew :desktop:distMsi`
 *
 * build/ の中の Dopachiru-1.6.0.msi は次のビルドで黙って上書きされるので、
 * どれを入れたのかが後から辿れない。:app:dist と対にしてある。
 */
tasks.register<Copy>("distMsi") {
    group = "distribution"
    description = "MSI を dist/ に版つきでコピーする"
    dependsOn("packageMsi")
    from(layout.buildDirectory.dir("compose/binaries/main/msi"))
    include("*.msi")
    into(rootProject.layout.projectDirectory.dir("dist"))
    // 版は設定時に読んでおく。rename の中から script の値を掴むと、
    // 構成キャッシュが「script object reference は直列化できない」で落ちる
    val name = "dopachiru-windows-$desktopVersion.msi"
    rename { name }
}

/**
 * WiX を使わないインストーラ。
 * `gradlew :desktop:packageSetup`
 *
 * jpackage の MSI は WiX 3 を要求する(`packageMsi` / `distMsi`)。入っていない
 * 環境でも「インストールされた状態」を作れるように、app イメージに
 * 導入スクリプトを添えて固める。入れ先が決まるので**自動起動も使える**
 * ── 持ち運び版との違いはそこ。
 *
 * 中身は desktop/packaging/ にある。UTF-8 の BOM 付きで置いてあるので、
 * Windows PowerShell 5.1 でも日本語が化けない。フィルタを通さずそのまま
 * 入れているのはこのため(Gradle の filter は BOM を落とす)。
 */
tasks.register<Zip>("packageSetup") {
    group = "distribution"
    description = "app イメージに導入スクリプトを添えて zip に固める(WiX 不要)"
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from(layout.projectDirectory.dir("packaging"))
    // 版はスクリプトに埋め込まず、添えたファイルから読ませる
    from(versionStamp)
    archiveFileName.set("dopachiru-windows-$desktopVersion-setup.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
}

/** `packageSetup` が添える version.txt。「アプリと機能」に出す版になる。 */
val versionStamp: Provider<RegularFile> = layout.buildDirectory.file("packaging/version.txt")

tasks.register("writeVersionStamp") {
    val out = versionStamp
    val version = desktopVersion
    outputs.file(out)
    doLast {
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText(version)
    }
}

tasks.named("packageSetup") { dependsOn("writeVersionStamp") }

/**
 * インストール不要の持ち運び版。JRE ごと固めるので、展開してそのまま動く。
 * `gradlew :desktop:packagePortable`
 *
 * 自動起動も入れられるが、フォルダを動かすと前のパスを指したままになる。
 * 動かした先で一度起動すれば書き直される(WindowsAutoStart.reconcile)。
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

/** 入っているアプリの一覧が実機で取れるかを見る。`gradlew :desktop:installedAppsSmoke` */
tasks.register<JavaExec>("installedAppsSmoke") {
    group = "verification"
    description = "スタートメニューから実行ファイル名を拾えているか目で見る"
    mainClass.set("com.dopachiru.desktop.tools.InstalledAppsSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** 使用状況の書き出しが1枚の紙として読めるかを見る。`gradlew :desktop:usageReportSmoke` */
tasks.register<JavaExec>("usageReportSmoke") {
    group = "verification"
    description = "作り物の記録を流し込んで、書き出しの見た目を目で見る"
    mainClass.set("com.dopachiru.desktop.tools.UsageReportSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
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
                menuGroup = "Dopachiru"
                shortcut = true
                /**
                 * 管理者権限を求めない。
                 *
                 * 入れるのは %LOCALAPPDATA% の下だけになる。自分用のアプリに
                 * UAC の壁を立てる理由が無いし、権限を求めるものほど
                 * 「怪しい」と見なされて入れるのをやめてしまう。
                 *
                 * **1.0.x の MSI(端末全体に入る版)を入れたままだと、
                 * 別物として並んで入る。** 先に「アプリと機能」から消すこと。
                 */
                perUserInstall = true
                // 入れ先を選べるようにする。既定のままで困らないが、聞かれないのも不安
                dirChooser = true
                // 更新しても設定が引き継がれるように固定する
                upgradeUuid = "6f2e1b74-2a5d-4a0e-9d3a-1c7b5e0f8a21"
            }
        }
    }
}
