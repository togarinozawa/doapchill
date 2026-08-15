package com.dopachiru.core.condition

import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 制限条件の1種類。
 *
 * 新しい条件を足すときにやることは2つだけ:
 *   1. このインターフェースを実装した object をファイル1つに書く
 *   2. [BuiltInConditions] の一覧に足す
 *
 * 設定画面の入力UIは [params] から組み立てられるので、UI 側には手を入れない。
 */
interface ConditionType {
    /**
     * 永続化に使う不変のID。
     * 保存済みルールがこのIDで条件を引くので、一度決めたら変えない。
     */
    val id: String

    /** 設定画面に出す名前。 */
    val displayName: String

    /** 条件を選ぶときに出す説明。 */
    val description: String

    /** この条件が必要とするパラメータ。 */
    val params: List<ParamSpec>

    /**
     * いま新しく選べるか。false なら条件の一覧に出さない。
     *
     * 凍結した機能を**レジストリから外さない**ために要る。外してしまうと
     * 保存済みのルールがその条件を引けなくなり、一覧の説明が ID の生文字列に化ける。
     * 選ばせないことと、読めなくすることは別。
     */
    val available: Boolean get() = true

    /** 条件が成立していれば true。 */
    fun evaluate(p: Params, ctx: EvalContext): Boolean

    /** ルール一覧に出す1行サマリ。例: "22:00〜06:00" */
    fun summarize(p: Params): String

    /**
     * この条件の成否が変わりうる、最も早い時刻。
     *
     * 返した時刻まではポーリングしなくてよい、という宣言。実装しなければ null が返り、
     * 呼び出し側は安全側に倒して短い間隔で見に来る。
     * つまり**実装は任意**で、書かなくても正しく動く。書けばその条件のぶんだけ電池が減らなくなる。
     *
     * 「もう変わらない」場合も遠い未来を返してよい(呼び出し側で上限に丸められる)。
     */
    fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime? = null
}
