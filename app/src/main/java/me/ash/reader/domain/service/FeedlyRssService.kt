package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import androidx.compose.ui.util.fastFilter
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.FeedlySecurityKey
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.exception.FeedlyAPIException
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.provider.feedly.FeedlyAPI
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar

private const val TAG = "FeedlyRssService"

class FeedlyRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val groupDao: GroupDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    workManager: WorkManager,
    private val accountService: AccountService,
) :
    AbstractRssRepository(
        articleDao,
        groupDao,
        feedDao,
        workManager,
        rssHelper,
        notificationHelper,
        ioDispatcher,
        defaultDispatcher,
        accountService,
    ) {

    override val importSubscription: Boolean = false
    override val addSubscription: Boolean = true
    override val moveSubscription: Boolean = true
    override val deleteSubscription: Boolean = true
    override val updateSubscription: Boolean = true

    private suspend fun getFeedlyAPI(): FeedlyAPI =
        FeedlySecurityKey(accountService.getCurrentAccount().securityKey).run {
            FeedlyAPI.getInstance(
                context = context,
                accessToken = accessToken ?: throw FeedlyAPIException("Access token is not set"),
            )
        }

    override suspend fun validCredentials(account: Account): Boolean {
        return try {
            val key = FeedlySecurityKey(account.securityKey)
            if (key.accessToken.isNullOrBlank()) return false
            val api = FeedlyAPI.getInstance(context, key.accessToken!!)
            val profile = api.getProfile()
            val userId = profile.id
            if (!userId.isNullOrBlank() && userId != key.userId) {
                accountService.update(account.copy(securityKey = FeedlySecurityKey(key.accessToken, userId).toString()))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "validCredentials failed: ${e.message}", e)
            false
        }
    }

    override suspend fun clearAuthorization() {
        FeedlyAPI.clearInstance()
    }

    override suspend fun subscribe(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) {
        val accountId = accountService.getCurrentAccountId()
        val key = FeedlySecurityKey(accountService.getCurrentAccount().securityKey)
        val userId = key.userId ?: throw FeedlyAPIException("User ID is not available")
        val api = getFeedlyAPI()

        val feedId = "feed/$feedLink"
        val categoryId = "user/$userId/category/${groupId.dollarLast()}"

        val subscription =
            api.addSubscription(
                feedId = feedId,
                title = searchedFeed.title,
                categoryId = categoryId,
            )

        feedDao.insert(
            Feed(
                id = accountId.spacerDollar(feedId),
                name = subscription.title ?: searchedFeed.title ?: context.getString(R.string.empty),
                url = feedLink,
                groupId = groupId,
                accountId = accountId,
                icon = subscription.iconUrl,
                isNotification = isNotification,
                isFullContent = isFullContent,
                isBrowser = isBrowser,
            )
        )
    }

    override suspend fun addGroup(destFeed: Feed?, newGroupName: String): String {
        val accountId = accountService.getCurrentAccountId()
        val key = FeedlySecurityKey(accountService.getCurrentAccount().securityKey)
        val userId = key.userId ?: throw FeedlyAPIException("User ID is not available")
        val categoryId = "user/$userId/category/$newGroupName"
        val id = accountId.spacerDollar(categoryId)
        groupDao.insert(Group(id = id, name = newGroupName, accountId = accountId))
        return id
    }

    override suspend fun renameFeed(feed: Feed) {
        super.renameFeed(feed)
    }

    override suspend fun moveFeed(originGroupId: String, feed: Feed) {
        val api = getFeedlyAPI()
        val key = FeedlySecurityKey(accountService.getCurrentAccount().securityKey)
        val userId = key.userId ?: throw FeedlyAPIException("User ID is not available")
        val feedId = feed.id.dollarLast()
        val newCategoryId = "user/$userId/category/${feed.groupId.dollarLast()}"
        api.addSubscription(feedId = feedId, title = feed.name, categoryId = newCategoryId)
        super.moveFeed(originGroupId, feed)
    }

    override suspend fun changeFeedUrl(feed: Feed) {
        throw FeedlyAPIException("Unsupported")
    }

    override suspend fun deleteGroup(group: Group, onlyDeleteNoStarred: Boolean?) {
        feedDao.queryByGroupId(accountService.getCurrentAccountId(), group.id).forEach {
            deleteFeed(it, onlyDeleteNoStarred)
        }
        super.deleteGroup(group, false)
    }

    override suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean?) {
        getFeedlyAPI().deleteSubscription(feed.id.dollarLast())
        super.deleteFeed(feed, false)
    }

    /**
     * Feedly sync strategy:
     * 1. Fetch collections + subscriptions; build the complete group set (including any
     *    built-in categories like global.uncategorized that are not in /v3/collections)
     * 2. Upsert groups, then upsert feeds — order matters for FK constraints
     * 3. Paginate stream contents for global.all since last sync
     * 4. Insert articles whose feed exists in the DB; read/starred from stream item fields
     * 5. Remove orphaned groups and feeds
     */
    override suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?,
    ): ListenableWorker.Result = coroutineScope {
        try {
            val preTime = System.currentTimeMillis()
            val preDate = Date(preTime)
            // Use var so we can keep the account object up-to-date if userId is fetched here
            var account = accountService.getAccountById(accountId)!!
            check(account.type.id == AccountType.Feedly.id) { "account type is invalid" }

            val key = FeedlySecurityKey(account.securityKey)
            val accessToken =
                key.accessToken ?: throw FeedlyAPIException("Access token is not set")
            val userId = key.userId ?: run {
                Log.i(TAG, "userId not cached, fetching from profile")
                val profile = FeedlyAPI.getInstance(context, accessToken).getProfile()
                val id = profile.id ?: throw FeedlyAPIException("Unable to retrieve user ID")
                account = account.copy(securityKey = FeedlySecurityKey(accessToken, id).toString())
                accountService.update(account)
                id
            }
            Log.i(TAG, "sync start — accountId=$accountId userId=$userId newerThan=${account.updateAt}")
            val feedlyAPI = FeedlyAPI.getInstance(context, accessToken)

            // 1. Fetch both collections and subscriptions up front
            val collections = feedlyAPI.getCollections()
            Log.i(TAG, "collections fetched: ${collections.size}")
            val subscriptions = feedlyAPI.getSubscriptions()
            Log.i(TAG, "subscriptions fetched: ${subscriptions.size}")

            // Build a complete set of category IDs: collections provide labels;
            // subscriptions may reference built-in categories (e.g. global.uncategorized)
            // that are not in /v3/collections — those would violate the Feed→Group FK if missed.
            val collectionIds = collections.mapNotNull { it.id }.toSet()
            val allCategoryIds = subscriptions
                .flatMap { it.categories.orEmpty() }
                .mapNotNull { it.id }
                .toSet()

            val remoteGroupIds = mutableSetOf<String>()
            val groups = mutableListOf<Group>()

            collections.forEach { collection ->
                val id = collection.id ?: return@forEach
                remoteGroupIds.add(accountId.spacerDollar(id))
                groups.add(
                    Group(
                        id = accountId.spacerDollar(id),
                        name = collection.label ?: context.getString(R.string.empty),
                        accountId = accountId,
                    )
                )
            }

            // Create a group for any subscription category not covered by collections
            allCategoryIds.filterNot { it in collectionIds }.forEach { categoryId ->
                val label = when {
                    categoryId.endsWith("global.uncategorized") ->
                        context.getString(R.string.all)
                    else -> categoryId.substringAfterLast("/")
                }
                val dbId = accountId.spacerDollar(categoryId)
                remoteGroupIds.add(dbId)
                groups.add(Group(id = dbId, name = label, accountId = accountId))
                Log.i(TAG, "synthetic group for built-in category: $categoryId label=$label")
            }

            Log.i(TAG, "upserting ${groups.size} groups")
            // Groups must exist before feeds are inserted (FK constraint)
            groupDao.insertOrUpdate(groups)

            // 2. Build feeds — every subscription category now has a matching group in DB
            val remoteFeedIds = mutableSetOf<String>()
            val feeds = subscriptions.mapNotNull { subscription ->
                val subId = subscription.id ?: return@mapNotNull null
                val firstCategoryId =
                    subscription.categories?.firstOrNull()?.id ?: run {
                        Log.w(TAG, "subscription $subId has no category — skipping")
                        return@mapNotNull null
                    }
                remoteFeedIds.add(accountId.spacerDollar(subId))
                Feed(
                    id = accountId.spacerDollar(subId),
                    name = subscription.title?.decodeHTML()
                        ?: context.getString(R.string.empty),
                    url = subId.removePrefix("feed/"),
                    groupId = accountId.spacerDollar(firstCategoryId),
                    accountId = accountId,
                    icon = subscription.iconUrl,
                )
            }
            Log.i(TAG, "upserting ${feeds.size} feeds (remoteFeedIds=${remoteFeedIds.size})")
            feedDao.insertOrUpdate(feeds)

            val noIconFeeds = feedDao.queryNoIcon(accountId)
            feedDao.update(
                *noIconFeeds
                    .map { it.copy(icon = rssHelper.queryRssIconLink(it.url)) }
                    .toTypedArray()
            )

            // 3. Paginate stream contents for all articles since last sync.
            // If the DB has no articles at all (e.g. previous syncs failed silently and left
            // updateAt set to a stale timestamp), ignore newerThan and fetch everything.
            val allStreamId = "user/$userId/category/global.all"
            val hasExistingArticles = articleDao.hasArticles(accountId)
            val newerThan = if (hasExistingArticles) account.updateAt?.time else null
            Log.i(TAG, "fetching stream $allStreamId newerThan=$newerThan hasExistingArticles=$hasExistingArticles")

            val allArticles = mutableListOf<Article>()
            var continuation: String? = null
            var batchCount = 0
            val maxBatches = 40

            while (batchCount < maxBatches) {
                val streamContents =
                    feedlyAPI.getStreamContents(
                        streamId = allStreamId,
                        count = 250,
                        continuation = continuation,
                        newerThan = newerThan,
                    )

                val items = streamContents.items
                Log.i(TAG, "batch $batchCount: ${items?.size ?: 0} items, continuation=${streamContents.continuation?.take(20)}")
                if (items.isNullOrEmpty()) break

                var skipped = 0
                items.forEach { item ->
                    val entryId = item.id ?: return@forEach
                    val feedIdRaw = item.origin?.streamId ?: return@forEach
                    // Skip articles whose feed wasn't in our subscription list
                    if (accountId.spacerDollar(feedIdRaw) !in remoteFeedIds) {
                        skipped++
                        return@forEach
                    }
                    val link =
                        item.canonicalUrl
                            ?: item.canonical?.firstOrNull()?.href
                            ?: item.alternate?.firstOrNull()?.href
                            ?: ""
                    val content = item.content?.content ?: item.summary?.content ?: ""
                    val isStarred =
                        item.tags?.any { it.id?.contains("global.saved") == true } == true

                    allArticles.add(
                        Article(
                            id = accountId.spacerDollar(entryId),
                            date =
                                item.published
                                    ?.let { Date(it) }
                                    ?.takeIf { !it.isFuture(preDate) } ?: preDate,
                            title =
                                item.title?.decodeHTML()
                                    ?: context.getString(R.string.empty),
                            author = item.author,
                            rawDescription = content,
                            shortDescription =
                                Readability.parseToText(content, link).take(280),
                            img =
                                item.visual?.url
                                    ?: item.thumbnail?.firstOrNull()?.url
                                    ?: rssHelper.findThumbnail(content),
                            link = link,
                            feedId = accountId.spacerDollar(feedIdRaw),
                            accountId = accountId,
                            isUnread = item.unread != false,
                            isStarred = isStarred,
                            updateAt = preDate,
                        )
                    )
                }
                if (skipped > 0) Log.w(TAG, "batch $batchCount: skipped $skipped items (feed not in subscription list)")

                continuation = streamContents.continuation ?: break
                batchCount++
            }

            Log.i(TAG, "stream fetch done: ${allArticles.size} articles across $batchCount batches")

            if (allArticles.isNotEmpty()) {
                articleDao.insert(*allArticles.toTypedArray())
                val notificationFeeds =
                    feedDao.queryNotificationEnabled(accountId).associateBy { it.id }
                val notificationFeedIds = notificationFeeds.keys
                allArticles
                    .fastFilter { it.isUnread && it.feedId in notificationFeedIds }
                    .groupBy { it.feedId }
                    .mapKeys { (feedId, _) -> notificationFeeds[feedId]!! }
                    .forEach { (feed, articles) -> notificationHelper.notify(feed, articles) }
            }

            // 5. Remove orphaned groups and feeds (only if remote lists are non-empty,
            //    to avoid accidentally wiping everything if an API call returned nothing)
            if (remoteGroupIds.isNotEmpty()) {
                groupDao.queryAll(accountId).forEach {
                    if (it.id !in remoteGroupIds) super.deleteGroup(it, true)
                }
            }
            if (remoteFeedIds.isNotEmpty()) {
                feedDao.queryAll(accountId).forEach {
                    if (it.id !in remoteFeedIds) super.deleteFeed(it, true)
                }
            }

            Log.i(TAG, "sync completed in ${System.currentTimeMillis() - preTime}ms, " +
                    "${allArticles.size} articles inserted")
            // Only advance updateAt when we actually inserted articles. If 0 articles came back
            // (e.g. a bug filtered everything), keeping the old updateAt means the next sync
            // retries from the same window rather than silently skipping everything forever.
            if (allArticles.isNotEmpty()) {
                accountService.update(account.copy(updateAt = preDate))
            }
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "sync failed: ${e.message}", e)
            ListenableWorker.Result.failure()
        }
    }

    override suspend fun markAsRead(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        before: Date?,
        isUnread: Boolean,
    ) {
        super.markAsRead(groupId, feedId, articleId, before, isUnread)
        val asOf = before?.time ?: System.currentTimeMillis()
        val api = getFeedlyAPI()
        when {
            groupId != null -> {
                if (isUnread) {
                    // Feedly does not support "keepUnread" at category level via markers,
                    // so we only handle the local update already done by super.
                } else {
                    api.markCategoryAsRead(groupId.dollarLast(), asOf)
                }
            }

            feedId != null -> {
                if (isUnread) {
                    // Feedly does not support keepUnread at feed level via markers.
                } else {
                    api.markFeedAsRead(feedId.dollarLast(), asOf)
                }
            }

            articleId != null -> {
                if (isUnread) {
                    api.markEntriesAsUnread(listOf(articleId.dollarLast()))
                } else {
                    api.markEntriesAsRead(listOf(articleId.dollarLast()))
                }
            }

            else -> {
                if (!isUnread) {
                    val key = FeedlySecurityKey(accountService.getCurrentAccount().securityKey)
                    val userId = key.userId ?: return
                    api.markCategoryAsRead("user/$userId/category/global.all", asOf)
                }
            }
        }
    }

    override suspend fun syncReadStatus(articleIds: Set<String>, isUnread: Boolean): Set<String> {
        val api = getFeedlyAPI()
        val remoteIds = articleIds.map { it.dollarLast() }
        return try {
            if (isUnread) {
                api.markEntriesAsUnread(remoteIds)
            } else {
                api.markEntriesAsRead(remoteIds)
            }
            articleIds
        } catch (e: Exception) {
            Log.e(TAG, "syncReadStatus failed: ${e.message}", e)
            emptySet()
        }
    }

    override suspend fun markAsStarred(articleId: String, isStarred: Boolean) {
        super.markAsStarred(articleId, isStarred)
        val api = getFeedlyAPI()
        val remoteId = articleId.dollarLast()
        if (isStarred) {
            api.markEntriesAsSaved(listOf(remoteId))
        } else {
            api.markEntriesAsUnsaved(listOf(remoteId))
        }
    }
}
