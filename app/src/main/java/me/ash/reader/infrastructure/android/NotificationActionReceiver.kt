package me.ash.reader.infrastructure.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.WidgetUpdateWorker
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.page.common.ExtraName
import timber.log.Timber

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notificationHelper: NotificationHelper

    @Inject lateinit var rssService: RssService

    @Inject lateinit var accountService: AccountService

    @Inject lateinit var diffMapHolder: DiffMapHolder

    @Inject lateinit var workManager: WorkManager

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    @Inject @IODispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_MARK_AS_READ) return

        val articleId = intent.getStringExtra(ExtraName.ARTICLE_ID) ?: return
        val feedId = intent.getStringExtra(ExtraName.FEED_ID)
        val accountId = intent.getIntExtra(ExtraName.ACCOUNT_ID, -1)

        val pendingResult = goAsync()
        applicationScope.launch(ioDispatcher + NonCancellable) {
            try {
                Timber.d("NotificationActionReceiver: marking article $articleId as read")
                diffMapHolder.removeDiff(articleId)
                notificationHelper.cancelNotificationForArticle(
                    articleId = articleId,
                    feedId = feedId,
                    markReadInMemory = true,
                )

                val service =
                    if (accountId != -1) {
                        val account = accountService.getAccountById(accountId)
                        if (account != null) rssService.get(account.type.id) else rssService.get()
                    } else {
                        rssService.get()
                    }

                service.markAsRead(
                    groupId = null,
                    feedId = null,
                    articleId = articleId,
                    before = null,
                    isUnread = false,
                )
                WidgetUpdateWorker.enqueueOneTimeWork(workManager)
            } catch (e: Exception) {
                Timber.e(e, "Failed to mark article $articleId as read from notification")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_AS_READ = "me.ash.reader.action.MARK_AS_READ"
    }
}
