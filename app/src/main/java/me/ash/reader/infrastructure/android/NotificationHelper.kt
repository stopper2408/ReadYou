package me.ash.reader.infrastructure.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.page.common.ExtraName
import me.ash.reader.ui.page.common.NotificationGroupName
import timber.log.Timber

class NotificationHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val coroutineScope: CoroutineScope,
) {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context).apply {
            createNotificationChannel(
                NotificationChannel(
                    NotificationGroupName.ARTICLE_UPDATE,
                    NotificationGroupName.ARTICLE_UPDATE,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

    private fun feedGroupKey(feedId: String) = "feed:$feedId"

    private fun feedSummaryNotificationId(feedId: String) = "feed:$feedId".hashCode()

    private fun articleNotificationId(articleId: String) = "article:$articleId".hashCode()

    fun notify(feed: Feed, articles: List<Article>) {
        if (!notificationManager.areNotificationsEnabled()) return
        if (articles.isEmpty()) return
        if (!feed.isNotification) return
        coroutineScope.launch {
            Timber.d("notify ${feed.name} for ${articles.size} articles")

            val favIcon =
                withContext(ioDispatcher) {
                    feed.icon?.let { icon ->
                        context.imageLoader
                            .execute(ImageRequest.Builder(context).data(icon).build())
                            .drawable
                            ?.toBitmapOrNull()
                    }
                }

            val groupKey = feedGroupKey(feed.id)
            notificationManager.notify(
                feedSummaryNotificationId(feed.id),
                NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
                    .setContentTitle(feed.name)
                    .setContentText(
                        context.resources.getQuantityText(R.plurals.unread_desc, articles.size)
                    )
                    .setSmallIcon(R.drawable.ic_notification)
                    .setStyle(NotificationCompat.InboxStyle().setSummaryText(feed.name))
                    .setGroup(groupKey)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            feedSummaryNotificationId(feed.id),
                            Intent(context, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra(ExtraName.FEED_ID, feed.id)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                    .build(),
            )

            articles.asReversed().forEachIndexed { _, article ->
                val notificationId = articleNotificationId(article.id)
                val builder =
                    NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setSubText(feed.name)
                        .setAutoCancel(true)
                        .setContentTitle(article.title)
                        .setContentText(article.shortDescription)
                        .setLargeIcon(favIcon)
                        .setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                notificationId,
                                Intent(context, MainActivity::class.java).apply {
                                    flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    putExtra(ExtraName.ARTICLE_ID, article.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        )
                        .setGroup(groupKey)
                notificationManager.notify(
                    notificationId,
                    builder.build(),
                )
            }
        }
    }

    fun notify(feedWithArticle: FeedWithArticle) {
        notify(feedWithArticle.feed, feedWithArticle.articles)
    }

    fun cancelArticleNotification(articleId: String) {
        notificationManager.cancel(articleNotificationId(articleId))
    }

    fun cancelArticleNotifications(articleIds: Collection<String>) {
        articleIds.forEach { notificationManager.cancel(articleNotificationId(it)) }
    }

    fun cancelFeedSummaryNotification(feedId: String) {
        notificationManager.cancel(feedSummaryNotificationId(feedId))
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
}
