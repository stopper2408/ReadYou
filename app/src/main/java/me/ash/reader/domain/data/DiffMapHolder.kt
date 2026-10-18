package me.ash.reader.domain.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.currentAccountId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiffMapHolder"

@Singleton
@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val notificationHelper: NotificationHelper,
) {
    val diffMap = mutableStateMapOf<String, Diff>()

    private val pendingSyncDiffs = mutableStateMapOf<String, Diff>()
    private val syncedDiffs = mutableMapOf<String, Diff>()
    private val mutex = Mutex()
    private val cacheLock = Any()

    val diffMapSnapshotFlow = snapshotFlow { diffMap.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    private val pendingSyncDiffsSnapshotFlow = snapshotFlow { pendingSyncDiffs.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    val shouldSyncWithRemote get() = currentAccount?.type != AccountType.Local

    private val gson = Gson()

    private val cacheDir = context.cacheDir.resolve("diff")
    @Volatile
    private var userCacheDir = cacheDir.resolve(context.currentAccountId.toString())

    private var currentAccount: Account? = null

    private val cacheFile: File get() = userCacheDir.resolve("diff_map.json")

    var dbJob: Job? = null
    var remoteJob: Job? = null

    init {
        commitDiffsToDb()
        applicationScope.launch {
            accountService.currentAccountFlow.mapNotNull { it }.collect { account ->
                val previousAccount = currentAccount
                if (previousAccount != null && previousAccount != account) {
                    cleanup(previousAccount)
                }
                currentAccount = account
                init(account)
            }
        }
    }

    private fun init(account: Account) {
        userCacheDir = cacheDir.resolve(account.id.toString())
        commitDiffsFromCache()
        commitOnChange()
        if (account.type != AccountType.Local) {
            syncOnChange()
        }
    }

    private fun cleanup(account: Account) {
        dbJob?.cancel()
        remoteJob?.cancel()
        commitDiffsToDb()
        Snapshot.withMutableSnapshot {
            pendingSyncDiffs.clear()
        }
        syncedDiffs.clear()
    }

    private fun commitOnChange() {
        dbJob?.cancel()
        dbJob = applicationScope.launch(ioDispatcher) {
            diffMapSnapshotFlow.debounce(500).collect {
                if (it.isNotEmpty()) {
                    writeDiffsToCache()
                }
            }
        }
    }

    private fun syncOnChange() {
        remoteJob?.cancel()
        remoteJob = applicationScope.launch(ioDispatcher) {
            pendingSyncDiffsSnapshotFlow.debounce(2_000).collect {
                withContext(ioDispatcher) {
                    syncDiffsWithRemote(it)
                }
            }
        }
    }

    fun checkIfUnread(articleWithFeed: ArticleWithFeed): Boolean {
        return diffMap[articleWithFeed.article.id]?.isUnread ?: articleWithFeed.article.isUnread
    }

    fun removeDiff(articleId: String) {
        Snapshot.withMutableSnapshot {
            diffMap.remove(articleId)
            pendingSyncDiffs.remove(articleId)
        }
        writeDiffsToCache()
    }

    /**
     * Updates the diff map with changes to an article's read/unread status.
     *
     * This function manages a map (`diffMap`) that tracks pending changes (diffs) to the
     * read/unread status of articles. These changes are not immediately applied to the
     * underlying data store but are held in `diffMap` until a later commit operation.
     *
     * The function supports three modes of updating:
     *
     * 1. **Toggle:** If `isUnread` is `null`, the function toggles the current read/unread
     *    status of the article.  If the article is currently unread, it will be marked as read,
     *    and vice-versa.
     * 2. **Mark as Unread:** If `isUnread` is `true`, the article will be marked as unread,
     *    regardless of its current status.
     * 3. **Mark as Read:** If `isUnread` is `false`, the article will be marked as read,
     *    regardless of its current status.
     *
     * The function determines if a change needs to be tracked based on the current status and desired status:
     *  - If the requested change matches the article's current status, the diff is removed from the map, if it exists. (No change is needed.)
     *  - Otherwise, the diff is added to or updated in the map.
     *
     * @param articleWithFeed The article and its associated feed data. This is used to identify the article
     *                        and access its current read/unread state.
     * @param isUnread An optional boolean indicating the desired read/unread status of the article.
     *                 - `null`: Toggles the current read/unread status.
     *                 - `true`: Marks the article as unread.
     *                 - `false`: Marks the article as read.
     *
     * @return A [Diff] object representing the changes made to the article.
     *
     * @see Diff
     */
    private fun updateDiffInternal(
        articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id

        val diff = diffMap[articleId]

        if (diff == null) {
            val targetUnread = isUnread ?: !articleWithFeed.article.isUnread
            if (targetUnread != articleWithFeed.article.isUnread) {
                val newDiff = Diff(
                    isUnread = targetUnread, articleWithFeed = articleWithFeed
                )
                diffMap[articleId] = newDiff
                return newDiff
            }
        } else {
            if (isUnread == null || diff.isUnread != isUnread) {
                val removedDiff = diffMap.remove(articleId)
                return removedDiff?.copy(isUnread = !diff.isUnread)
            }
        }
        return null
    }

    fun updateDiff(
        vararg articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ) {
        val appliedDiffs = Snapshot.withMutableSnapshot {
            articleWithFeed.mapNotNull {
                updateDiffInternal(it, isUnread)
            }
        }
        val nowUnreadArticles = articleWithFeed.filter { checkIfUnread(it) }
        if (nowUnreadArticles.isNotEmpty()) {
            nowUnreadArticles.forEach { notificationHelper.markArticleUnreadInMemory(it.article.id) }
        }
        val nowReadArticles = articleWithFeed.filter { !checkIfUnread(it) }
        if (nowReadArticles.isNotEmpty()) {
            notificationHelper.cancelNotificationsForArticles(
                articleIds = nowReadArticles.map { it.article.id },
                affectedFeedIds = nowReadArticles.map { it.feed.id }.distinct(),
                markReadInMemory = true,
            )
        }
        if (appliedDiffs.isNotEmpty()) {
            writeDiffsToCache()
            if (shouldSyncWithRemote) {
                Snapshot.withMutableSnapshot {
                    appliedDiffs.forEach {
                        appendDiffToSync(it)
                    }
                }
            }
        }
    }

    fun updateDiff(
        diffs: Collection<Diff>, isUnread: Boolean = false
    ) {
        if (diffs.isEmpty()) return
        if (!isUnread) {
            notificationHelper.cancelNotificationsForArticles(
                articleIds = diffs.map { it.articleId },
                affectedFeedIds = diffs.map { it.feedId }.distinct(),
                markReadInMemory = true,
            )
        } else {
            diffs.forEach { notificationHelper.markArticleUnreadInMemory(it.articleId) }
        }
        val appliedDiffs = mutableListOf<Diff>()
        Snapshot.withMutableSnapshot {
            for (incoming in diffs) {
                val targetDiff = incoming.copy(isUnread = isUnread)
                val existing = diffMap[targetDiff.articleId]
                if (existing == null || existing.isUnread != isUnread) {
                    diffMap[targetDiff.articleId] = targetDiff
                    appliedDiffs.add(targetDiff)
                }
            }
        }
        if (appliedDiffs.isNotEmpty()) {
            writeDiffsToCache()
            if (shouldSyncWithRemote) {
                Snapshot.withMutableSnapshot {
                    appliedDiffs.forEach {
                        appendDiffToSync(it)
                    }
                }
            }
        }
    }

    fun cancelNotificationForArticle(articleId: String, feedId: String? = null) {
        notificationHelper.cancelNotificationForArticle(
            articleId = articleId,
            feedId = feedId,
            markReadInMemory = true,
        )
    }

    fun cancelAllNotifications() {
        notificationHelper.cancelAllNotifications()
    }

    fun cancelNotificationsForFeeds(feedIds: Collection<String>) {
        notificationHelper.cancelNotificationsForFeeds(feedIds)
    }

    fun cancelNotificationsForGroup(groupId: String) {
        val accountId = currentAccount?.id ?: context.currentAccountId
        notificationHelper.cancelNotificationsForGroup(accountId, groupId)
    }

    private fun appendDiffToSync(diff: Diff) {
        val syncedDiff = syncedDiffs[diff.articleId]
        if (syncedDiff == null || syncedDiff.isUnread != diff.isUnread) {
            pendingSyncDiffs[diff.articleId] = diff
        }
    }

    fun commitDiffsToDb(): Job {
        return applicationScope.launch(ioDispatcher + NonCancellable) {
            commitDiffsToDbSuspend()
        }
    }

    suspend fun commitDiffsToDbSuspend() {
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                val cachedDiffs = synchronized(cacheLock) { readCacheFileLocked() }
                val memorySnapshot = diffMap.toMap()
                val snapshot = cachedDiffs + memorySnapshot
                if (snapshot.isEmpty()) {
                    synchronized(cacheLock) { deleteCacheFileLocked() }
                    return@withLock
                }
                if (shouldSyncWithRemote && cachedDiffs.isNotEmpty()) {
                    Snapshot.withMutableSnapshot {
                        cachedDiffs.values.forEach { appendDiffToSync(it) }
                    }
                }
                val markAsReadArticles =
                    snapshot.filter { !it.value.isUnread }.map { it.key }.toSet()
                val markAsUnreadArticles =
                    snapshot.filter { it.value.isUnread }.map { it.key }.toSet()

                rssService.get().batchMarkAsRead(articleIds = markAsReadArticles, isUnread = false)
                rssService.get().batchMarkAsRead(articleIds = markAsUnreadArticles, isUnread = true)

                Snapshot.withMutableSnapshot {
                    snapshot.forEach { (key, committedDiff) ->
                        if (diffMap[key] == committedDiff) {
                            diffMap.remove(key)
                        }
                    }
                }
                synchronized(cacheLock) {
                    writeOrDeleteCacheFileLocked(diffMap.toMap())
                }
            }
        }
    }

    fun writeDiffsToCacheSync() {
        val snapshot = diffMap.toMap()
        synchronized(cacheLock) {
            if (snapshot.isNotEmpty()) {
                writeOrDeleteCacheFileLocked(snapshot)
            }
        }
    }

    private fun writeDiffsToCache() {
        val snapshot = diffMap.toMap()
        applicationScope.launch(ioDispatcher + NonCancellable) {
            synchronized(cacheLock) {
                writeOrDeleteCacheFileLocked(snapshot)
            }
        }
    }

    private fun readCacheFileLocked(): Map<String, Diff> {
        try {
            if (cacheFile.exists() && cacheFile.canRead()) {
                val tmpJson = cacheFile.readText()
                if (tmpJson.isNotBlank()) {
                    val mapType = object : TypeToken<Map<String, Diff>>() {}.type
                    return gson.fromJson<Map<String, Diff>>(tmpJson, mapType) ?: emptyMap()
                }
            }
        } catch (_: Exception) {
        }
        return emptyMap()
    }

    private fun writeOrDeleteCacheFileLocked(snapshot: Map<String, Diff>) {
        try {
            if (snapshot.isEmpty()) {
                deleteCacheFileLocked()
            } else {
                val tmpJson = gson.toJson(snapshot)
                userCacheDir.mkdirs()
                if (!cacheFile.exists()) {
                    cacheFile.createNewFile()
                }
                if (cacheFile.exists() && cacheFile.canWrite()) {
                    cacheFile.writeText(tmpJson)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteCacheFileLocked() {
        try {
            if (cacheFile.exists() && cacheFile.canWrite()) {
                cacheFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun syncDiffsWithRemote(diffs: Map<String, Diff>) {
        if (!shouldSyncWithRemote) return
        if (diffs.isEmpty()) return
        val toBeSync = diffs
        val markAsReadArticles =
            toBeSync.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles =
            toBeSync.filter { it.value.isUnread }.map { it.key }.toSet()

        val rssService = rssService.get()

        val synced = supervisorScope {
            val read = async {
                rssService.syncReadStatus(
                    articleIds = markAsReadArticles,
                    isUnread = false
                )
            }
            val unread = async {
                rssService.syncReadStatus(
                    articleIds = markAsUnreadArticles,
                    isUnread = true
                )
            }
            runCatching { read.await() }.getOrElse { emptySet() } +
                    runCatching { unread.await() }.getOrElse { emptySet() }
        }

        Snapshot.withMutableSnapshot {
            pendingSyncDiffs -= synced
        }
        syncedDiffs += diffs.filter { synced.contains(it.key) }
    }

    private fun commitDiffsFromCache() {
        commitDiffsToDb()
    }
}

data class Diff(
    val isUnread: Boolean, val articleId: String, val feedId: String
) {
    constructor(isUnread: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isUnread = isUnread,
        articleId = articleWithFeed.article.id,
        feedId = articleWithFeed.feed.id,
    )
}
