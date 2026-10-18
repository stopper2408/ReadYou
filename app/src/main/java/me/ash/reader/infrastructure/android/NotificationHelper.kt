package me.ash.reader.infrastructure.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.page.common.ExtraName
import me.ash.reader.ui.page.common.NotificationGroupName
import timber.log.Timber

@Singleton
class NotificationHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val coroutineScope: CoroutineScope,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
) {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context).apply {
            createNotificationChannel(
                NotificationChannel(
                    NotificationGroupName.ARTICLE_UPDATE,
                    context.getString(R.string.notifications),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

    private val systemNotificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val mutex = Mutex()

    /**
     * Tracks notified unread articles per feed (feedId -> LinkedHashMap<articleId, Article>)
     * ordered newest-first so dynamic summary updates and child notification replacements
     * stay accurate and instantaneous.
     */
    private val notifiedArticlesByFeed = ConcurrentHashMap<String, LinkedHashMap<String, Article>>()

    /**
     * Tracks feed metadata for active notified feeds so summaries can be rebuilt without
     * unnecessary database round-trips.
     */
    private val cachedFeedsById = ConcurrentHashMap<String, Feed>()

    /**
     * Tracks articleIds that have been marked as read in memory (e.g. via DiffMapHolder or
     * NotificationActionReceiver) before the database commit completes.
     */
    private val readArticleIdsInMemory: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun hasNotificationPermission(): Boolean {
        if (!notificationManager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun notify(feedWithArticle: FeedWithArticle) {
        notify(feedWithArticle.feed, feedWithArticle.articles)
    }

    fun notify(feed: Feed, articles: List<Article>) {
        if (!hasNotificationPermission()) return
        if (!feed.isNotification) return
        val unreadIncoming = articles.filter { it.isUnread && !readArticleIdsInMemory.contains(it.id) }
        if (unreadIncoming.isEmpty()) return

        coroutineScope.launch(ioDispatcher) {
            mutex.withLock {
                if (!hasNotificationPermission()) return@withLock
                cachedFeedsById[feed.id] = feed

                val existingMap = notifiedArticlesByFeed.getOrPut(feed.id) { LinkedHashMap() }
                // Reconcile existing tracked articles with DB read state so stale read items are dropped
                if (existingMap.isNotEmpty()) {
                    val dbArticles =
                        articleDao
                            .queryArticlesByIds(existingMap.keys.toList())
                            .associateBy { it.id }
                    val iterator = existingMap.iterator()
                    while (iterator.hasNext()) {
                        val (id, _) = iterator.next()
                        val dbArticle = dbArticles[id]
                        if (dbArticle == null || !dbArticle.isUnread || readArticleIdsInMemory.contains(id)) {
                            iterator.remove()
                        }
                    }
                }

                // Merge incoming unread articles with existing unread notified articles, sorted newest first
                val mergedArticles =
                    (unreadIncoming + existingMap.values)
                        .distinctBy { it.id }
                        .filter { !readArticleIdsInMemory.contains(it.id) }
                        .sortedByDescending { it.date.time }

                if (mergedArticles.isEmpty()) return@withLock

                val updatedMap = LinkedHashMap<String, Article>(mergedArticles.size)
                mergedArticles.forEach { updatedMap[it.id] = it }
                notifiedArticlesByFeed[feed.id] = updatedMap

                Timber.d("notify ${feed.name} for ${mergedArticles.size} unread articles (${unreadIncoming.size} new)")

                postFeedNotificationsLocked(
                    feed = feed,
                    unreadArticles = mergedArticles,
                    isSilentUpdate = false,
                )
            }
        }
    }

    fun markArticleUnreadInMemory(articleId: String) {
        readArticleIdsInMemory.remove(articleId)
    }

    fun cancelNotificationForArticle(
        articleId: String,
        feedId: String? = null,
        markReadInMemory: Boolean = true,
    ) {
        cancelNotificationsForArticles(
            articleIds = listOf(articleId),
            affectedFeedIds = listOfNotNull(feedId),
            markReadInMemory = markReadInMemory,
        )
    }

    fun cancelNotificationsForArticles(
        articleIds: Collection<String>,
        affectedFeedIds: Collection<String>? = null,
        markReadInMemory: Boolean = true,
    ) {
        if (articleIds.isEmpty()) return
        if (markReadInMemory) {
            readArticleIdsInMemory.addAll(articleIds)
        }
        // Immediately cancel individual child notifications in the status bar for zero latency
        articleIds.forEach { articleId ->
            notificationManager.cancel(articleNotificationId(articleId))
        }

        coroutineScope.launch(ioDispatcher) {
            mutex.withLock {
                val feedsToReconcile = mutableSetOf<String>()
                affectedFeedIds?.let { feedsToReconcile.addAll(it) }

                // Locate any feed groups in memory that contained these articles
                notifiedArticlesByFeed.forEach { (feedId, articleMap) ->
                    var removedAny = false
                    articleIds.forEach { id ->
                        if (articleMap.remove(id) != null) {
                            removedAny = true
                        }
                    }
                    if (removedAny) {
                        feedsToReconcile.add(feedId)
                    }
                }

                // Also inspect OS active notifications in case the app process restarted
                val activeNotifications = getActiveStatusBarNotifications()
                val targetNotificationIds = articleIds.map { articleNotificationId(it) }.toSet()
                activeNotifications.forEach { sbn ->
                    if (sbn.id in targetNotificationIds) {
                        extractFeedIdFromGroupKey(sbn.notification.group)?.let {
                            feedsToReconcile.add(it)
                        }
                    }
                }

                // If we still don't know the feedId (e.g. cold process & child already cancelled by OS autoCancel),
                // check active summary groups or look up the articles in DB
                if (feedsToReconcile.isEmpty()) {
                    val activeSummaryFeedIds =
                        activeNotifications
                            .mapNotNull { extractFeedIdFromGroupKey(it.notification.group) }
                            .toSet()
                    if (activeSummaryFeedIds.isNotEmpty()) {
                        val articlesFromDb = articleDao.queryArticlesByIds(articleIds)
                        articlesFromDb.forEach { article ->
                            if (article.feedId in activeSummaryFeedIds) {
                                feedsToReconcile.add(article.feedId)
                            }
                        }
                    }
                }

                feedsToReconcile.forEach { feedId ->
                    reconcileFeedGroupLocked(feedId)
                }
            }
        }
    }

    fun cancelNotificationsForFeed(feedId: String) {
        cancelNotificationsForFeeds(listOf(feedId))
    }

    fun cancelNotificationsForFeeds(feedIds: Collection<String>) {
        if (feedIds.isEmpty()) return
        val feedIdSet = feedIds.toSet()

        // Immediately cancel summary notifications and any tracked child notifications
        feedIdSet.forEach { feedId ->
            notificationManager.cancel(summaryNotificationId(feedId))
            notifiedArticlesByFeed.remove(feedId)?.keys?.forEach { articleId ->
                readArticleIdsInMemory.add(articleId)
                notificationManager.cancel(articleNotificationId(articleId))
            }
        }

        // Also sweep OS activeNotifications for any children belonging to these feed groups
        getActiveStatusBarNotifications().forEach { sbn ->
            val sbnFeedId = extractFeedIdFromGroupKey(sbn.notification.group)
            if (sbnFeedId != null && sbnFeedId in feedIdSet) {
                notificationManager.cancel(sbn.id)
            }
        }
    }

    fun cancelNotificationsForGroup(accountId: Int, groupId: String) {
        coroutineScope.launch(ioDispatcher) {
            val feeds = feedDao.queryByGroupId(accountId, groupId)
            cancelNotificationsForFeeds(feeds.map { it.id })
        }
    }

    fun cancelAllNotifications() {
        notifiedArticlesByFeed.clear()
        readArticleIdsInMemory.clear()
        notificationManager.cancelAll()
    }

    fun reconcileFeedNotifications(feedId: String) {
        coroutineScope.launch(ioDispatcher) {
            mutex.withLock {
                reconcileFeedGroupLocked(feedId)
            }
        }
    }

    fun reconcileGroupNotifications(accountId: Int, groupId: String) {
        coroutineScope.launch(ioDispatcher) {
            val feeds = feedDao.queryByGroupId(accountId, groupId)
            mutex.withLock {
                feeds.forEach { feed ->
                    reconcileFeedGroupLocked(feed.id)
                }
            }
        }
    }

    fun reconcileAllActiveNotifications() {
        coroutineScope.launch(ioDispatcher) {
            mutex.withLock {
                val activeFeedIds = mutableSetOf<String>()
                activeFeedIds.addAll(notifiedArticlesByFeed.keys)
                getActiveStatusBarNotifications().forEach { sbn ->
                    extractFeedIdFromGroupKey(sbn.notification.group)?.let {
                        activeFeedIds.add(it)
                    }
                }
                activeFeedIds.forEach { feedId ->
                    reconcileFeedGroupLocked(feedId)
                }
            }
        }
    }

    private suspend fun reconcileFeedGroupLocked(feedId: String) {
        val groupKey = NotificationGroupName.feedGroupKey(feedId)
        val trackedMap = notifiedArticlesByFeed[feedId]

        // Collect active OS notifications belonging to this feed group
        val activeForFeed =
            getActiveStatusBarNotifications().filter { it.notification.group == groupKey }
        val summaryId = summaryNotificationId(feedId)
        val activeChildren = activeForFeed.filter { it.id != summaryId }

        val remainingUnreadArticles: List<Article> =
            if (trackedMap != null && trackedMap.isNotEmpty()) {
                val trackedIds = trackedMap.keys.toList()
                val dbArticlesById =
                    articleDao.queryArticlesByIds(trackedIds).associateBy { it.id }

                val validArticles = mutableListOf<Article>()
                val iterator = trackedMap.iterator()
                while (iterator.hasNext()) {
                    val (articleId, cachedArticle) = iterator.next()
                    val dbArticle = dbArticlesById[articleId]
                    val isStillUnread =
                        (dbArticle?.isUnread ?: cachedArticle.isUnread) &&
                            !readArticleIdsInMemory.contains(articleId)
                    if (!isStillUnread) {
                        iterator.remove()
                        notificationManager.cancel(articleNotificationId(articleId))
                    } else {
                        validArticles.add(dbArticle ?: cachedArticle)
                    }
                }
                validArticles.sortedByDescending { it.date.time }
            } else if (activeChildren.isNotEmpty()) {
                // Fallback after process death: reconstruct from active child notification extras or DB
                val activeArticleIds =
                    activeChildren.mapNotNull {
                        it.notification.extras.getString(ExtraName.ARTICLE_ID)
                    }
                if (activeArticleIds.isNotEmpty()) {
                    val dbArticles =
                        articleDao
                            .queryArticlesByIds(activeArticleIds)
                            .filter { it.isUnread && !readArticleIdsInMemory.contains(it.id) }
                            .sortedByDescending { it.date.time }
                    val activeUnreadIds = dbArticles.map { it.id }.toSet()
                    activeArticleIds.forEach { id ->
                        if (id !in activeUnreadIds) {
                            notificationManager.cancel(articleNotificationId(id))
                        }
                    }
                    val rebuiltMap = LinkedHashMap<String, Article>()
                    dbArticles.forEach { rebuiltMap[it.id] = it }
                    notifiedArticlesByFeed[feedId] = rebuiltMap
                    dbArticles
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

        if (remainingUnreadArticles.isEmpty()) {
            notifiedArticlesByFeed.remove(feedId)
            // Cancel any remaining children and the summary notification so no empty ghost summary lingers
            activeChildren.forEach { notificationManager.cancel(it.id) }
            notificationManager.cancel(summaryId)
            return
        }

        val feed = cachedFeedsById[feedId] ?: feedDao.queryById(feedId)?.also { cachedFeedsById[feedId] = it }
        if (feed == null || !feed.isNotification) {
            cancelNotificationsForFeed(feedId)
            return
        }

        postFeedNotificationsLocked(
            feed = feed,
            unreadArticles = remainingUnreadArticles,
            isSilentUpdate = true,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun postFeedNotificationsLocked(
        feed: Feed,
        unreadArticles: List<Article>,
        isSilentUpdate: Boolean,
    ) {
        if (!hasNotificationPermission()) return
        if (unreadArticles.isEmpty()) {
            notificationManager.cancel(summaryNotificationId(feed.id))
            return
        }

        val favIcon: Bitmap? =
            withContext(ioDispatcher) {
                feed.icon?.let { icon ->
                    runCatching {
                        context.imageLoader
                            .execute(ImageRequest.Builder(context).data(icon).build())
                            .drawable
                            ?.toBitmapOrNull()
                    }.getOrNull()
                }
            }

        val groupKey = NotificationGroupName.feedGroupKey(feed.id)
        val totalUnreadCount = unreadArticles.size
        val latestTimestamp = unreadArticles.maxOfOrNull { it.date.time } ?: System.currentTimeMillis()

        // Safeguard against Android's per-app active StatusBarNotification rate/count limit:
        // Cap child notifications per feed to MAX_CHILD_NOTIFICATIONS_PER_FEED and enforce global headroom.
        val childArticlesToPost = selectChildArticlesWithinOsLimits(feed.id, unreadArticles)

        // Cancel any previously active child notifications for this feed that are no longer in childArticlesToPost
        val desiredChildNotificationIds = childArticlesToPost.map { articleNotificationId(it.id) }.toSet()
        val summaryId = summaryNotificationId(feed.id)
        val currentlyActiveIdsForFeed =
            getActiveStatusBarNotifications()
                .filter { it.notification.group == groupKey && it.id != summaryId }
                .map { it.id }
                .toSet()

        (currentlyActiveIdsForFeed - desiredChildNotificationIds).forEach { staleNotificationId ->
            notificationManager.cancel(staleNotificationId)
        }

        // Post child notifications (oldest to newest, with explicit sortKey so newest is always first)
        childArticlesToPost.forEachIndexed { index, article ->
            // Explicit lexicographical sortKey ("00", "01", ...) guarantees newest-first ordering inside the group
            val sortKey = String.format("%04d", index)
            val shouldPostChild =
                !isSilentUpdate || articleNotificationId(article.id) !in currentlyActiveIdsForFeed

            if (shouldPostChild) {
                val childNotification =
                    buildChildNotification(
                        feed = feed,
                        article = article,
                        favIcon = favIcon,
                        groupKey = groupKey,
                        sortKey = sortKey,
                        isSilent = isSilentUpdate,
                    )
                notificationManager.notify(articleNotificationId(article.id), childNotification)
            }
        }

        // Build and post/update the Group Summary Notification (InboxStyle)
        val summaryNotification =
            buildSummaryNotification(
                feed = feed,
                unreadArticles = unreadArticles,
                totalUnreadCount = totalUnreadCount,
                latestTimestamp = latestTimestamp,
                favIcon = favIcon,
                groupKey = groupKey,
                isSilent = isSilentUpdate,
            )
        notificationManager.notify(summaryId, summaryNotification)
    }

    private fun buildChildNotification(
        feed: Feed,
        article: Article,
        favIcon: Bitmap?,
        groupKey: String,
        sortKey: String,
        isSilent: Boolean,
    ): Notification {
        val openArticleIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ExtraName.ARTICLE_ID, article.id)
                putExtra(ExtraName.FEED_ID, feed.id)
                putExtra(ExtraName.GROUP_ID, feed.groupId)
                putExtra(ExtraName.ACCOUNT_ID, feed.accountId)
            }

        val contentPendingIntent =
            PendingIntent.getActivity(
                context,
                articleClickRequestCode(article.id),
                openArticleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val markAsReadIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MARK_AS_READ
                putExtra(ExtraName.ARTICLE_ID, article.id)
                putExtra(ExtraName.FEED_ID, feed.id)
                putExtra(ExtraName.ACCOUNT_ID, feed.accountId)
            }

        val markAsReadPendingIntent =
            PendingIntent.getBroadcast(
                context,
                articleMarkReadRequestCode(article.id),
                markAsReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val snippetText = article.shortDescription.ifBlank { feed.name }
        val extras =
            android.os.Bundle().apply {
                putString(ExtraName.ARTICLE_ID, article.id)
                putString(ExtraName.FEED_ID, feed.id)
            }

        return NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setSubText(feed.name)
            .setContentTitle(article.title)
            .setContentText(snippetText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(snippetText)
                    .setBigContentTitle(article.title)
                    .setSummaryText(feed.name)
            )
            .setLargeIcon(favIcon)
            .setWhen(article.date.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup(groupKey)
            .setSortKey(sortKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(isSilent)
            .setSilent(isSilent)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.mark_as_read),
                markAsReadPendingIntent,
            )
            .addExtras(extras)
            .build()
    }

    private fun buildSummaryNotification(
        feed: Feed,
        unreadArticles: List<Article>,
        totalUnreadCount: Int,
        latestTimestamp: Long,
        favIcon: Bitmap?,
        groupKey: String,
        isSilent: Boolean,
    ): Notification {
        // Clicking the unexpanded Group Summary notification opens the global Unread FlowPage across all feeds
        val openGlobalUnreadIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ExtraName.OPEN_UNREAD_FLOW, true)
                putExtra(ExtraName.ACCOUNT_ID, feed.accountId)
            }

        val summaryPendingIntent =
            PendingIntent.getActivity(
                context,
                summaryClickRequestCode(feed.id),
                openGlobalUnreadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val previewArticles = unreadArticles.take(MAX_INBOX_LINES)
        val firstHeadline = previewArticles.firstOrNull()?.title ?: feed.name
        val secondHeadline =
            previewArticles.getOrNull(1)?.title
                ?: context.resources.getQuantityString(
                    R.plurals.unread_desc,
                    totalUnreadCount,
                    totalUnreadCount,
                )

        val inboxStyle =
            NotificationCompat.InboxStyle().also { style ->
                previewArticles.forEach { article ->
                    style.addLine(article.title)
                }
                style.setSummaryText(feed.name)
            }

        val extras =
            android.os.Bundle().apply {
                putString(ExtraName.FEED_ID, feed.id)
            }

        return NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setSubText(feed.name)
            .setContentTitle(firstHeadline)
            .setContentText(secondHeadline)
            .setNumber(totalUnreadCount)
            .setLargeIcon(favIcon)
            .setWhen(latestTimestamp)
            .setShowWhen(true)
            .setStyle(inboxStyle)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(isSilent)
            .setSilent(isSilent)
            .setAutoCancel(true)
            .setContentIntent(summaryPendingIntent)
            .addExtras(extras)
            .build()
    }

    private fun selectChildArticlesWithinOsLimits(
        feedId: String,
        unreadArticles: List<Article>,
    ): List<Article> {
        val groupKey = NotificationGroupName.feedGroupKey(feedId)
        val otherFeedsActiveCount =
            getActiveStatusBarNotifications().count { it.notification.group != groupKey }
        val availableSlots = (MAX_TOTAL_ACTIVE_NOTIFICATIONS - otherFeedsActiveCount - 1).coerceAtLeast(1)
        val childCap = minOf(MAX_CHILD_NOTIFICATIONS_PER_FEED, availableSlots)
        return unreadArticles.take(childCap)
    }

    private fun getActiveStatusBarNotifications() =
        runCatching {
            systemNotificationManager?.activeNotifications?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

    private fun extractFeedIdFromGroupKey(groupKey: String?): String? {
        if (groupKey == null) return null
        return if (groupKey.startsWith(NotificationGroupName.FEED_GROUP_PREFIX)) {
            groupKey.removePrefix(NotificationGroupName.FEED_GROUP_PREFIX)
        } else {
            null
        }
    }

    companion object {
        private const val MAX_CHILD_NOTIFICATIONS_PER_FEED = 8
        private const val MAX_TOTAL_ACTIVE_NOTIFICATIONS = 24
        private const val MAX_INBOX_LINES = 7

        fun summaryNotificationId(feedId: String): Int = "SUMMARY_$feedId".hashCode()

        fun articleNotificationId(articleId: String): Int = "ARTICLE_$articleId".hashCode()

        private fun articleClickRequestCode(articleId: String): Int =
            "CLICK_ARTICLE_$articleId".hashCode()

        private fun articleMarkReadRequestCode(articleId: String): Int =
            "MARK_READ_ARTICLE_$articleId".hashCode()

        private fun summaryClickRequestCode(feedId: String): Int =
            "CLICK_SUMMARY_$feedId".hashCode()
    }
}
