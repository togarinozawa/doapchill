package com.dopachiru.core.io

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.SiteCatalog
import com.dopachiru.core.model.SitePattern
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * ルールの持ち出しと取り込み。
 *
 * ## 何のためか
 *
 * 込み入ったルールを画面でこねるのは骨が折れる。文章で言えば作ってもらえるほうが早い
 * ── そのために、**この機械の外にある道具でも書ける形**にして出し入れする。
 *
 * ## なぜ目録(catalog)を同梱するのか
 *
 * 条件は typeId という文字列で保存されている。ルールだけを書き出すと、
 * 受け取った側からは**どんな条件が在って、どの値をどの名前で渡すのかが分からない**。
 * 当てずっぽうで書けば、取り込みで弾かれるか、もっと悪いことに
 * 「どこにも当たらないルール」になる。
 *
 * だから書き出しには、いま登録されている条件・アクション・サイトの束をまるごと添える。
 * **このファイル1つ渡せば、外の道具が正しいルールを書ける**ようにする。
 * 取り込むときは読み飛ばす(向こうが書き換えていても、こちらの登録が正)。
 *
 * ## 足りない値は既定で埋める
 *
 * 外で書いたルールは、パラメータを全部は埋めていないのが普通。
 * 抜けを弾くのではなく既定値で埋める ── 弾くと、惜しいところまで書けた
 * ルールが丸ごと捨てられる。
 */
@Serializable
data class RuleBundle(
    /** これが何のファイルか。取り違えを弾くために見る。 */
    val format: String = FORMAT,

    /** 読み手が形の違いを判断するための番号。 */
    val version: Int = VERSION,

    val exportedAt: String = "",

    /** 人にも道具にも読ませる説明。中身の意味は持たない。 */
    val note: String = NOTE,

    val rules: List<Rule> = emptyList(),

    /** パッケージ名 → タグ。ルールがタグで対象を指しているときに要る。 */
    val tags: Map<String, Set<String>> = emptyMap(),

    /** 書くときの参照。取り込みでは読まない。 */
    val catalog: Catalog? = null,
) {
    companion object {
        const val FORMAT = "dopachiru.rules"
        const val VERSION = 1

        const val NOTE =
            "ドパチルのルール。rules を足したり書き換えたりして取り込めます。" +
                "condition の typeId と actionId、params のキーは catalog を見てください。" +
                "params の書き漏らしは既定値で埋まります。uid が同じルールは差し替えになります。"

        internal val JSON = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** いま登録されている条件・アクション・サイトの一覧。書き出しにだけ入る。 */
@Serializable
data class Catalog(
    val conditions: List<TypeDoc> = emptyList(),
    val actions: List<TypeDoc> = emptyList(),
    val siteGroups: List<SiteGroupDoc> = emptyList(),
    val hints: List<String> = emptyList(),
)

@Serializable
data class TypeDoc(
    val id: String,
    val name: String,
    val description: String,
    val params: List<ParamDoc> = emptyList(),
    /** アクションだけ。強さが最大のものが採用される。 */
    val severity: Int? = null,
)

@Serializable
data class ParamDoc(
    val key: String,
    val label: String,
    /** int / minutes / timeOfDay / bool / text / enum / daysOfWeek / packages / resetPolicy */
    val type: String,
    val default: String,
    val help: String = "",
    val range: String = "",
    val options: List<String> = emptyList(),
)

@Serializable
data class SiteGroupDoc(val id: String, val label: String, val patterns: List<String>)

/** 取り込む前に「何が起きるか」を出したもの。押す前に見せるためのもの。 */
data class ImportPlan(
    /** 新しく増えるルール。 */
    val added: List<Rule> = emptyList(),
    /** uid が一致したので差し替わるもの(既存 → 取り込むもの)。 */
    val replaced: List<Pair<Rule, Rule>> = emptyList(),
    /** 取り込めなかったものと、その理由。 */
    val problems: List<Problem> = emptyList(),
    val tags: Map<String, Set<String>> = emptyMap(),
) {
    data class Problem(val ruleName: String, val reason: String)

    val isEmpty: Boolean get() = added.isEmpty() && replaced.isEmpty()

    /** 一行で。「3件追加・1件差し替え・1件は取り込めません」 */
    fun summary(): String = buildList {
        if (added.isNotEmpty()) add("${added.size}件追加")
        if (replaced.isNotEmpty()) add("${replaced.size}件差し替え")
        if (problems.isNotEmpty()) add("${problems.size}件は取り込めません")
        if (isEmpty && problems.isEmpty()) add("取り込めるルールがありません")
    }.joinToString("・")
}

object RuleBundleIo {

    // ---- 書き出し ---------------------------------------------------------

    fun export(
        rules: List<Rule>,
        tags: Map<String, Set<String>> = emptyMap(),
        exportedAt: String = "",
        includeCatalog: Boolean = true,
    ): String {
        val bundle = RuleBundle(
            exportedAt = exportedAt,
            // uid が空のままだと、取り込み側で毎回「新しいルール」に見える
            rules = rules.map { if (it.uid.isBlank()) it.copy(uid = newUid()) else it },
            tags = tags,
            catalog = if (includeCatalog) buildCatalog() else null,
        )
        return RuleBundle.JSON.encodeToString(RuleBundle.serializer(), bundle)
    }

    fun buildCatalog(): Catalog = Catalog(
        conditions = ConditionRegistry.all()
            .filter { it.available }
            .map { TypeDoc(it.id, it.displayName, it.description, it.params.map(::docOf)) },
        actions = ActionRegistry.all()
            .map { TypeDoc(it.id, it.displayName, it.description, it.params.map(::docOf), it.severity) },
        siteGroups = SiteCatalog.all.map { SiteGroupDoc(it.id, it.label, it.patterns) },
        hints = listOf(
            "target は packages(アプリ)・tags・sites(URL)の和。matchAll を立てると全アプリが対象になり、except で始まる欄が例外になる。",
            "sites の書き方はホストか、ホストとパスの先頭だけ。正規表現は使えない。下位ドメイン(www. や m.)は自動で含む。",
            "condition は木。leaf(1つの条件)・allOf(かつ)・anyOf(または)・not(でないとき)を入れ子にできる。",
            "condition を children が空の allOf にすると無条件、つまり常に成立する。",
            "timeOfDay と minutes は分で書く。23:00 は 1380、6:00 は 360。",
            "consequence は破ったときの報い。lockScope は NONE・APP・RULE_TARGET・EVERYTHING のどれか。",
            "uid を空にすると新しいルールとして増える。既存を書き換えたいときは uid を残す。",
        ),
    )

    private fun docOf(spec: ParamSpec): ParamDoc = when (spec) {
        is ParamSpec.IntParam -> ParamDoc(
            spec.key, spec.label, "int", spec.default.toString(), spec.help,
            range = "${spec.min}〜${spec.max}" + if (spec.unit.isNotBlank()) " ${spec.unit}" else "",
        )

        is ParamSpec.DurationParam -> ParamDoc(
            spec.key, spec.label, "minutes", spec.default.toString(), spec.help,
            range = "${spec.min}〜${spec.max} 分",
        )

        is ParamSpec.TimeOfDayParam -> ParamDoc(
            spec.key, spec.label, "timeOfDay", spec.default.toString(), spec.help,
            range = "0〜1439(その日の何分目か)",
        )

        is ParamSpec.BoolParam ->
            ParamDoc(spec.key, spec.label, "bool", spec.default.toString(), spec.help)

        is ParamSpec.TextParam -> ParamDoc(
            spec.key, spec.label, "text", spec.default, spec.help,
            range = if (spec.multiline) "改行で区切ると1つずつ選ばれます" else "",
        )

        is ParamSpec.EnumParam -> ParamDoc(
            spec.key, spec.label, "enum", spec.default, spec.help,
            options = spec.options.map { it.value + " (" + it.label + ")" },
        )

        is ParamSpec.DayOfWeekParam -> ParamDoc(
            spec.key, spec.label, "daysOfWeek", spec.default.sorted().joinToString(","), spec.help,
            range = "月=1 〜 日=7 の配列",
        )

        is ParamSpec.PackagesParam ->
            ParamDoc(spec.key, spec.label, "packages", "", spec.help, range = "パッケージ名の配列")

        is ParamSpec.ResetPolicyParam ->
            ParamDoc(spec.key, spec.label, "resetPolicy", "", spec.help)
    }

    // ---- 読み込み ---------------------------------------------------------

    sealed interface ParseResult {
        data class Ok(val bundle: RuleBundle) : ParseResult
        data class Failed(val message: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        val bundle = runCatching {
            RuleBundle.JSON.decodeFromString(RuleBundle.serializer(), text)
        }.getOrElse {
            return ParseResult.Failed("JSON として読めませんでした。" + shortReason(it))
        }

        if (bundle.format != RuleBundle.FORMAT) {
            return ParseResult.Failed(
                "ドパチルのルールファイルではないようです(format が " + bundle.format + ")。",
            )
        }
        if (bundle.version > RuleBundle.VERSION) {
            return ParseResult.Failed(
                "新しい形式です(version ${bundle.version})。ドパチルを更新してください。",
            )
        }
        return ParseResult.Ok(bundle)
    }

    /**
     * 取り込んだら何が起きるかを組み立てる。**ここでは何も変えない。**
     *
     * @param existing いま入っているルール。uid で突き合わせる。
     */
    fun plan(bundle: RuleBundle, existing: List<Rule>): ImportPlan {
        val byUid = existing.filter { it.uid.isNotBlank() }.associateBy { it.uid }
        val added = ArrayList<Rule>()
        val replaced = ArrayList<Pair<Rule, Rule>>()
        val problems = ArrayList<ImportPlan.Problem>()

        for (raw in bundle.rules) {
            val name = raw.name.ifBlank { "(名前なし)" }

            val unknownCondition = ConditionTree.leafTypeIds(raw.condition)
                .firstOrNull { ConditionRegistry[it] == null }
            if (unknownCondition != null) {
                problems += ImportPlan.Problem(name, "知らない条件です: " + unknownCondition)
                continue
            }
            if (ActionRegistry[raw.actionId] == null) {
                problems += ImportPlan.Problem(name, "知らない措置です: " + raw.actionId)
                continue
            }
            if (raw.target.isEmpty) {
                problems += ImportPlan.Problem(name, "対象がありません(アプリもタグもURLも空)")
                continue
            }
            val badSite = raw.target.sites.firstOrNull { !SitePattern.isValid(it) }
            if (badSite != null) {
                problems += ImportPlan.Problem(name, "URL の書き方が違います: " + badSite)
                continue
            }

            val filled = fillDefaults(raw)
            val hit = byUid[filled.uid]
            if (hit != null) {
                // 既存の番号は保つ。番号が変わると罰や記録の紐付けが切れる
                replaced += hit to filled.copy(id = hit.id)
            } else {
                added += filled.copy(id = 0L, uid = filled.uid.ifBlank { newUid() })
            }
        }

        return ImportPlan(added, replaced, problems, bundle.tags)
    }

    /**
     * 書かれていないパラメータを既定値で埋める。
     *
     * 外で書いたものは全部は埋まっていないのが普通なので、
     * 抜けを理由に丸ごと捨てない。
     */
    private fun fillDefaults(rule: Rule): Rule {
        val action = ActionRegistry[rule.actionId]
        val actionParams = if (action == null) {
            rule.actionParams
        } else {
            merge(Params.defaultsOf(action.params), rule.actionParams)
        }
        return rule.copy(
            condition = fillCondition(rule.condition),
            actionParams = actionParams,
        )
    }

    private fun fillCondition(node: ConditionNode): ConditionNode = when (node) {
        is ConditionNode.Leaf -> {
            val type = ConditionRegistry[node.typeId]
            if (type == null) {
                node
            } else {
                node.copy(params = merge(Params.defaultsOf(type.params), node.params))
            }
        }

        is ConditionNode.AllOf -> ConditionNode.AllOf(node.children.map(::fillCondition))
        is ConditionNode.AnyOf -> ConditionNode.AnyOf(node.children.map(::fillCondition))
        is ConditionNode.Not -> ConditionNode.Not(fillCondition(node.child))
    }

    /** 書かれているほうが勝つ。 */
    private fun merge(defaults: Params, given: Params): Params =
        Params(JsonObject(defaults.json + given.json))

    private fun newUid(): String = UUID.randomUUID().toString()

    private fun shortReason(t: Throwable): String =
        (t.message ?: t::class.simpleName.orEmpty()).take(160)
}
