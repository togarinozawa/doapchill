# kotlinx.serialization
# 生成された Serializer は明示的に .serializer() で呼んでいるが、
# @Serializable クラスの companion / $$serializer は R8 から見えないため残す。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.KSerializer {
    *;
}

# core のモデルは JSON で永続化するので、フィールド名まで保つ
-keep class com.dopachiru.core.model.** { *; }
-keep class com.dopachiru.core.gate.** { *; }
-keep class com.dopachiru.core.time.** { *; }
-keep class com.dopachiru.core.param.** { *; }

# 条件・アクションの実装は id 文字列から引くだけだが、
# レジストリ登録時にオブジェクト初期化が要るので残す
-keep class com.dopachiru.core.condition.types.** { *; }
-keep class com.dopachiru.core.action.types.** { *; }

# Room が生成する実装
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# manifest から名前で引かれるコンポーネント
-keep class com.dopachiru.service.** { *; }
-keep class com.dopachiru.DopaApplication { *; }
-keep class com.dopachiru.MainActivity { *; }
