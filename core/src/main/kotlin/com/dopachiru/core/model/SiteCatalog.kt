package com.dopachiru.core.model

/**
 * よく名前の挙がるサイトの束。
 *
 * 一覧から選べるようにしておくのは、**URL を1つずつ手で書かせると
 * 抜けが出る**ため。`youtube.com/shorts` は塞いだが `m.youtube.com` は
 * 通る、のような穴は自分では気づけない(ホストの下位ドメインは
 * [SitePattern] 側で吸収しているが、書き忘れはどうにもならない)。
 *
 * ここに無いものは自分で足せる。この束は出発点であって、上限ではない。
 */
data class SiteGroup(
    val id: String,
    val label: String,
    val help: String,
    val patterns: List<String>,
)

object SiteCatalog {

    /**
     * 短い動画の無限スクロール。
     *
     * 他と分けてあるのは、**同じサイトの中で害の濃さが違う**ため。
     * 「YouTube は調べ物に要るが、ショートだけは止めたい」がいちばん多い形なので、
     * サイト全体とは別の束にしてある。
     */
    val SHORT_VIDEO = SiteGroup(
        id = "short_video",
        label = "短い動画",
        help = "縦スクロールで次が勝手に来るところ。サイト全体は止めない",
        patterns = listOf(
            "youtube.com/shorts",
            "tiktok.com",
            "instagram.com/reels",
            "instagram.com/reel",
            "facebook.com/reel",
            "snapchat.com/spotlight",
        ),
    )

    val SOCIAL = SiteGroup(
        id = "social",
        label = "SNS",
        help = "終わりの無いタイムライン",
        patterns = listOf(
            "x.com",
            "twitter.com",
            "instagram.com",
            "facebook.com",
            "threads.com",
            "threads.net",
            "bsky.app",
            "mastodon.social",
        ),
    )

    val FORUM = SiteGroup(
        id = "forum",
        label = "掲示板・まとめ",
        help = "read 数の多い順に並ぶところ",
        patterns = listOf(
            "reddit.com",
            "5ch.net",
            "open2ch.net",
            "hatena.ne.jp",
            "girlschannel.net",
            "news.ycombinator.com",
        ),
    )

    val VIDEO = SiteGroup(
        id = "video",
        label = "動画サイト",
        help = "自動再生で次が来るところ。調べ物にも使うので扱いに注意",
        patterns = listOf(
            "youtube.com",
            "nicovideo.jp",
            "twitch.tv",
            "abema.tv",
            "netflix.com",
            "primevideo.com",
        ),
    )

    /** 画面に出す順。軽いものから。 */
    val all: List<SiteGroup> = listOf(SHORT_VIDEO, SOCIAL, FORUM, VIDEO)

    fun byId(id: String): SiteGroup? = all.firstOrNull { it.id == id }
}
