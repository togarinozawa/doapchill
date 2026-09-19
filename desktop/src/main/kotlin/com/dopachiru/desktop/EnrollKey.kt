package com.dopachiru.desktop

/**
 * サーバーに自分で繋ぎにいくための入口の鍵。
 *
 * **ソースには書きません。** リポジトリは公開なので、書いた時点で配ったことになります。
 * `local.properties` の `dopa.enrollKey` をビルドが持ち物として同梱し、ここで読みます。
 * 鍵を持っていない人がクローンしてビルドすると空になり、
 * 自動で繋ぐところだけが黙って止まります(手で合言葉を入れる道は残ります)。
 *
 * 配っている MSI の中には入っているので、**中を開けた人には読めます。**
 * そこはサーバー側で受けています ── 配る合言葉は端末ごとに別で、
 * 名簿に見慣れない名前が出たら1台だけ止められます。
 */
object EnrollKey {
    val value: String by lazy {
        runCatching {
            EnrollKey::class.java.getResourceAsStream("/dopachiru-enroll.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                .orEmpty()
        }.getOrDefault("")
    }
}
