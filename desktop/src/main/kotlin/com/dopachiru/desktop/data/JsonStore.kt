package com.dopachiru.desktop.data

import com.dopachiru.core.DopaCore
import kotlinx.serialization.KSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 設定と記録の置き場。`%APPDATA%\dopachiru\` */
object DopaPaths {
    val root: Path = Path.of(
        System.getenv("APPDATA") ?: System.getProperty("user.home"),
        "dopachiru",
    )

    fun file(name: String): Path {
        Files.createDirectories(root)
        return root.resolve(name)
    }
}

/**
 * JSON 1ファイル = 1コレクション。
 *
 * Android は Room を使っているが、こちらは規模が小さいのでファイルで足りる。
 * 同じ [DopaCore.json] を通すので、ルールの JSON は端末をまたいでそのまま読める
 * ── 同期を載せるときに変換が要らない。
 *
 * 書き込みは一時ファイル経由。書いている途中で電源が落ちても、
 * 前の内容が壊れずに残る。
 */
class JsonStore<T>(
    fileName: String,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val path: Path = DopaPaths.file(fileName)
    private val lock = Any()

    fun load(): T = synchronized(lock) {
        if (!Files.exists(path)) return default()
        runCatching {
            DopaCore.json.decodeFromString(serializer, Files.readString(path))
        }.getOrElse {
            // 壊れていたら退避して作り直す。起動できないほうが困る
            runCatching {
                Files.move(
                    path,
                    path.resolveSibling(path.fileName.toString() + ".broken"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            default()
        }
    }

    fun save(value: T): Unit = synchronized(lock) {
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        runCatching {
            Files.writeString(temp, DopaCore.json.encodeToString(serializer, value))
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
