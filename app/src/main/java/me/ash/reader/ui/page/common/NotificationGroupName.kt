package me.ash.reader.ui.page.common

object NotificationGroupName {

    const val ARTICLE_UPDATE: String = "article.update"
    const val FEED_GROUP_PREFIX: String = "FEED_GROUP_"

    fun feedGroupKey(feedId: String): String = "$FEED_GROUP_PREFIX$feedId"
}
