package me.ash.reader.infrastructure.android

import android.Manifest
import android.content.Intent
import android.database.CursorWindow
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Consumer
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.profileinstaller.ProfileInstallerInitializer
import androidx.work.WorkManager
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Field
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.WidgetUpdateWorker
import me.ash.reader.infrastructure.compose.ProvideCompositionLocals
import me.ash.reader.infrastructure.preference.AccountSettingsProvider
import me.ash.reader.infrastructure.preference.InitialPagePreference
import me.ash.reader.infrastructure.preference.LanguagesPreference
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.initialPage
import me.ash.reader.ui.ext.isFirstLaunch
import me.ash.reader.ui.ext.languages
import me.ash.reader.ui.page.common.ExtraName
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel
import me.ash.reader.ui.page.nav3.AppEntry
import me.ash.reader.ui.page.nav3.key.Route
import me.ash.reader.ui.page.nav3.key.Route.*
import me.ash.reader.ui.theme.AppTheme

/** The Single-Activity Architecture. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var imageLoader: ImageLoader

    @Inject lateinit var settingsProvider: SettingsProvider

    @Inject lateinit var accountService: AccountService

    @Inject lateinit var workManager: WorkManager

    @Inject lateinit var filterUseCase: FilterStateUseCase

    @Inject lateinit var diffMapHolder: me.ash.reader.domain.data.DiffMapHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("RLog", "onCreate: ${ProfileInstallerInitializer().create(this)}")

        enableEdgeToEdge()

        // Set the language
        if (Build.VERSION.SDK_INT < 33) {
            LanguagesPreference.fromValue(languages).let { LanguagesPreference.setLocale(it) }
        }

        // Workaround for https://github.com/ReadYouApp/ReadYou/issues/312: increase cursor window
        // size
        try {
            val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB is the new cursor window size
        } catch (e: Exception) {
            Log.e("RLog", "Unable to increase cursor window size: ${e.printStackTrace()}")
        }

        val requestPermissionLauncher =
            this.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted
                ->
                if (isGranted) {} else { // Permission denied }
                }
            }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AccountSettingsProvider(accountService = accountService) {
                settingsProvider.ProvidesSettings {
                    val subscribeViewModel: SubscribeViewModel = hiltViewModel()

                    ProvideCompositionLocals {
                        AppTheme(useDarkTheme = LocalDarkTheme.current.isDarkTheme()) {
                            val isFirstLaunch = remember { isFirstLaunch }
                            val initialPage = remember { initialPage }
                            val launchAction = remember { intent.getLaunchAction() }

                            val startDestination = remember {
                                if (isFirstLaunch) listOf(Route.Startup)
                                else
                                    when (launchAction) {
                                        is LaunchAction.OpenArticle -> {
                                            filterUseCase.init(
                                                launchAction.feedId,
                                                launchAction.groupId,
                                            )
                                            listOf(
                                                Route.Feeds,
                                                Route.Reading(
                                                    articleId = launchAction.articleId,
                                                    launchId = System.currentTimeMillis(),
                                                ),
                                            )
                                        }
                                        is LaunchAction.OpenArticleList -> {
                                            filterUseCase.init(
                                                launchAction.feedId,
                                                launchAction.groupId,
                                            )
                                            listOf(
                                                Route.Feeds,
                                                Route.Reading(
                                                    articleId = null,
                                                    launchId = System.currentTimeMillis(),
                                                ),
                                            )
                                        }
                                        is LaunchAction.Subscribe -> {
                                            subscribeViewModel.handleSharedUrlFromIntent(
                                                launchAction.url
                                            )
                                            listOf(Route.Feeds)
                                        }
                                        else -> {
                                            if (
                                                initialPage == InitialPagePreference.FlowPage.value
                                            ) {
                                                listOf(Route.Feeds, Route.Reading(null))
                                            } else listOf(Route.Feeds)
                                        }
                                    }
                            }

                            val backStack = rememberNavBackStack(*startDestination.toTypedArray())

                            NewIntentHandlerEffect(backStack, subscribeViewModel)
                            AppEntry(backStack)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NewIntentHandlerEffect(
        backStack: NavBackStack<NavKey>,
        subscribeViewModel: SubscribeViewModel,
    ) {
        DisposableEffect(backStack) {
            val listener =
                Consumer<Intent> { newIntent ->
                    newIntent.getLaunchAction()?.let { action ->
                        when (action) {
                            is LaunchAction.OpenArticle -> {
                                val (articleId, feedId, groupId, accountId) = action
                                if (
                                    accountId != null &&
                                        accountId != accountService.getCurrentAccountId()
                                )
                                    return@Consumer
                                filterUseCase.init(feedId, groupId)
                                resetBackStackToFeeds(backStack)
                                backStack.add(
                                    Reading(
                                        articleId = articleId,
                                        launchId = System.currentTimeMillis(),
                                    )
                                )
                            }

                            is LaunchAction.OpenArticleList -> {
                                val (accountId, feedId, groupId) = action
                                if (
                                    accountId != null &&
                                        accountId != accountService.getCurrentAccountId()
                                )
                                    return@Consumer
                                filterUseCase.init(feedId, groupId)
                                resetBackStackToFeeds(backStack)
                                backStack.add(
                                    Reading(
                                        articleId = null,
                                        launchId = System.currentTimeMillis(),
                                    )
                                )
                            }

                            is LaunchAction.Subscribe -> {
                                subscribeViewModel.handleSharedUrlFromIntent(action.url)
                                resetBackStackToFeeds(backStack)
                            }
                        }
                    }
                }
            addOnNewIntentListener(listener)
            onDispose { removeOnNewIntentListener(listener) }
        }
    }

    private fun resetBackStackToFeeds(backStack: NavBackStack<NavKey>) {
        val feedsIndex = backStack.indexOf(Route.Feeds)
        if (feedsIndex != -1) {
            repeat(backStack.size - (feedsIndex + 1)) {
                backStack.removeLastOrNull()
            }
        } else {
            backStack.add(0, Route.Feeds)
            repeat(backStack.size - 1) { backStack.removeLastOrNull() }
        }
    }

    override fun onResume() {
        WidgetUpdateWorker.enqueueOneTimeWork(workManager)
        super.onResume()
    }

    override fun onPause() {
        diffMapHolder.writeDiffsToCacheSync()
        super.onPause()
    }

    override fun onStop() {
        diffMapHolder.writeDiffsToCacheSync()
        super.onStop()
    }
}

sealed interface LaunchAction {
    data class Subscribe(val url: String) : LaunchAction

    data class OpenArticle(
        val articleId: String,
        val feedId: String?,
        val groupId: String?,
        val accountId: Int? = null,
    ) : LaunchAction

    data class OpenArticleList(val accountId: Int?, val feedId: String?, val groupId: String?) :
        LaunchAction
}

private fun Intent.getLaunchAction(): LaunchAction? {
    return when (action) {
        Intent.ACTION_VIEW -> {
            dataString?.let { LaunchAction.Subscribe(it) }
        }

        Intent.ACTION_SEND -> {
            getStringExtra(Intent.EXTRA_TEXT)
                ?.also { removeExtra(Intent.EXTRA_TEXT) }
                ?.let { LaunchAction.Subscribe(it) }
        }

        else -> {
            val articleId =
                getStringExtra(ExtraName.ARTICLE_ID)?.also { removeExtra(ExtraName.ARTICLE_ID) }
            val feedId = getStringExtra(ExtraName.FEED_ID)?.also { removeExtra(ExtraName.FEED_ID) }
            val groupId =
                getStringExtra(ExtraName.GROUP_ID)?.also { removeExtra(ExtraName.GROUP_ID) }
            val openUnreadFlow =
                getBooleanExtra(ExtraName.OPEN_UNREAD_FLOW, false).also {
                    if (it) removeExtra(ExtraName.OPEN_UNREAD_FLOW)
                }
            val accountId = getIntExtra(ExtraName.ACCOUNT_ID, -1)

            if (accountId != -1) {
                removeExtra(ExtraName.ACCOUNT_ID)
            }

            val resolvedAccountId = if (accountId != -1) accountId else null

            if (articleId != null) {
                LaunchAction.OpenArticle(
                    articleId = articleId,
                    feedId = feedId,
                    groupId = groupId,
                    accountId = resolvedAccountId,
                )
            } else if (openUnreadFlow) {
                LaunchAction.OpenArticleList(
                    accountId = resolvedAccountId,
                    feedId = null,
                    groupId = null,
                )
            } else if (feedId != null || groupId != null || accountId != -1) {
                LaunchAction.OpenArticleList(
                    accountId = resolvedAccountId,
                    feedId = feedId,
                    groupId = groupId,
                )
            } else {
                null
            }
        }
    }
}
