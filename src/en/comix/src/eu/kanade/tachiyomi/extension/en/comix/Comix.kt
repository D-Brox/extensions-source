package eu.kanade.tachiyomi.extension.en.comix

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.booleanOrNull
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.int
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Comix :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl get() = "$baseUrl/api/v1"
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val tagIdCache = object : LinkedHashMap<String, List<String>>(
        TAG_ID_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > TAG_ID_CACHE_SIZE
    }

    override fun OkHttpClient.Builder.configureClient() = cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(60.seconds)
        .readTimeout(60.seconds)
        .callTimeout(60.seconds)
        .addInterceptor(Descrambler.interceptor)
        .addInterceptor(::altHostFallback)
        .addInterceptor { chain ->
            val request = chain.request()
            // Image requests carry their alt hosts in a fragment and have no scramble paths
            // to rewrite, and API traffic must not be rewritten at all.
            if (
                request.url.fragment?.startsWith(ALT_HOSTS_FRAGMENT) == true ||
                request.url.host == baseUrlHost
            ) {
                return@addInterceptor chain.proceed(request)
            }

            val response = chain.proceed(request)
            if (response.code != 404) return@addInterceptor response

            val url = request.url.toString()
            val fallbacks = listOf("/hi/", "/fcf/", "/i5/", "/si/", "/i/", "/sii/", "/ii/")
                .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                .filter { it != url }

            if (fallbacks.isEmpty()) return@addInterceptor response

            var lastResponse = response
            for (fallbackUrl in fallbacks) {
                lastResponse.close()
                lastResponse = chain.proceed(request.newBuilder().url(fallbackUrl).build())
                if (lastResponse.code != 404) break
            }
            lastResponse
        }
        .rateLimit(2) { it.host == baseUrlHost }
        .rateLimit(5)

    private val baseUrlHost get() = baseUrl.toHttpUrl().host

    // Image mirrors: the reader walks them in order, so carry them in the fragment and
    // retry across them here.
    private fun altHostFallback(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val altHosts = request.url.altHosts()
        if (altHosts.isEmpty()) return chain.proceed(request)

        val urls = listOf(request.url) + altHosts.mapIndexed { index, host ->
            request.url.newBuilder()
                .host(host)
                .setQueryParameter("r", (index + 1).toString())
                .build()
        }

        var failed: Response? = null
        var error: IOException? = null
        for (url in urls) {
            if (failed != null) {
                failed.close()
                failed = null
                runCatching { Thread.sleep(2000) }
            }
            try {
                val response = chain.proceed(request.newBuilder().url(url).build())
                if (response.isSuccessful) return response
                failed = response
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                error = e
            }
        }
        return failed ?: throw error!!
    }

    private fun HttpUrl.altHosts(): List<String> = fragment
        ?.takeIf { it.startsWith(ALT_HOSTS_FRAGMENT) }
        ?.removePrefix(ALT_HOSTS_FRAGMENT)
        ?.split(',')
        .orEmpty()

    override fun Headers.Builder.configureHeaders() = add("Accept", "*/*")
        .apply {
            // The proxy's cleared jar also carries the site cookie, and cf_clearance is
            // bound to the user agent it was solved with, so both have to be echoed.
            val proxy = proxyServer() ?: return@apply
            storedProxyCookie()?.let { add("Cookie", it) }
            storedProxyUserAgent()?.let { set("User-Agent", it) }
        }

    // ============================== Proxy ==============================
    // When a comix-proxy server is configured, API calls go through its `/request`
    // endpoint: the proxy makes the call through the site's own bundle, so it mints the
    // `_` token, carries the WAF/Cloudflare cookies and unwraps the `{"e": ...}`
    // envelope, and hands back plain JSON. The extension then holds neither a token nor
    // a cipher key.
    //
    // Manga details and related titles are still read from the site's own HTML rather than
    // the API, so the proxy's cookie jar and the user agent it was solved with are cached
    // here and echoed on those requests; cf_clearance is bound to that user agent.
    private fun proxyServer(): String? = preferences.getString(PREF_PROXY_SERVER, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun storedProxyCookie(): String? = preferences.getString(PREF_PROXY_COOKIE, null)

    private fun storedProxyUserAgent(): String? = preferences.getString(PREF_PROXY_USER_AGENT, null)

    private fun saveProxySession(cookie: String?, cfClearance: String, userAgent: String) {
        val jar = cookie?.takeIf { it.isNotBlank() } ?: "cf_clearance=$cfClearance"
        preferences.edit()
            .putString(PREF_PROXY_COOKIE, jar)
            .putString(PREF_PROXY_USER_AGENT, userAgent)
            .apply()
    }

    /**
     * Asks the proxy for an API path with canonicalized [params] and returns the body's
     * plain JSON. `qs` is the canonical (raw-bracket) form the site's serializer produces;
     * the proxy signs over it, so ordering has to match what the server validates.
     */
    private fun proxyRequest(proxy: String, path: String, params: Map<String, List<String>>): String? {
        val body = ProxyRequestBody(path, canonicalizes(params)).toJsonRequestBody()
        return runCatching {
            client.newCall(POST(proxy.trimEnd('/') + "/request", body = body)).execute().use { resp ->
                if (resp.isSuccessful) resp.parseAs<ProxyRequestResponse>().json else null
            }
        }.getOrNull()
    }

    /**
     * Builds the canonical, sorted, indexed query string exactly like the site's
     * serializer: keys sorted, `key[]` arrays expanded to `key[i]=value`, single
     * values emitted as `key=value`, values emitted raw. The site mints tokens
     * over the decoded params (colons, spaces, commas, etc. are not
     * percent-escaped in the canonical), so values are used verbatim here.
     */
    private fun canonicalizes(params: Map<String, List<String>>): String = canonicalEntries(params).joinToString("&") { (name, value) -> "$name=$value" }

    /**
     * Asks the proxy for the jar it is currently using (`/cookies`). Clearing HTML pages
     * needs it, and the proxy is the only party that can solve Cloudflare.
     */
    private fun refreshCookiesOrWaf() {
        val proxy = proxyServer() ?: return
        val cookies = runCatching {
            client.newCall(GET(proxy.trimEnd('/') + "/cookies")).execute().use { resp ->
                if (resp.isSuccessful) resp.parseAs<ProxyCookiesResponse>() else null
            }
        }.getOrNull()
        if (cookies != null) {
            saveProxySession(cookies.cookie, cookies.cfClearance, cookies.userAgent)
        }
    }

    /**
     * Fetches a page HTML, re-solving the proxy's cookies and retrying once when the
     * server answers with the security check page instead.
     */
    private suspend fun fetchPage(url: HttpUrl): Document = fetchPage(url.toString())

    private suspend fun fetchPage(url: String): Document {
        val document = client.get(url).asJsoup()
        if (!document.isWafChallenge()) return document
        refreshCookiesOrWaf()
        val retry = client.get(url).asJsoup()
        if (retry.isWafChallenge()) {
            throw Exception("Security check could not be cleared")
        }
        return retry
    }

    private fun Document.isWafChallenge(): Boolean = selectFirst("title")?.text().orEmpty() == "Security check" ||
        selectFirst("#stage") != null

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[score]", "desc")
            addQueryParameter("page", page.toString())
            applyPreferenceFilters()
        }.build()
        return getMangaListFromBrowse(url)
    }

    private suspend fun getMangaListFromBrowse(url: HttpUrl): MangasPage {
        getSigned<SearchResponse>("/api/v1/manga", nativeMangaParams(url))?.let { response ->
            return MangasPage(
                response.items.map { it.toBasicSManga(preferences.posterQuality()) },
                response.hasNextPage(),
            )
        }
        throw Exception("Browse list unavailable; is the proxy server configured?")
    }

    private fun Document.extractInitialQueries(): JsonObject {
        val initialData = selectFirst("script#initial-data")?.data()
            ?: throw Exception("Could not find initial data in page")
        return initialData.parseAs<JsonObject>()["queries"] as? JsonObject
            ?: throw Exception("Could not find queries in initial data")
    }

    private fun nativeMangaParams(url: HttpUrl): Map<String, List<String>> = buildMap {
        url.queryParameterNames.forEach { name ->
            val values = url.queryParameterValues(name).filterNotNull()
            if (values.isNotEmpty()) {
                put(
                    name.removeSuffix("[]"),
                    if (name == "content_rating") values.flatMap { it.split(',') } else values,
                )
            }
        }
        putIfAbsent("limit", listOf("28"))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[chapter_updated_at]", "desc")
            addQueryParameter("page", page.toString())
            applyPreferenceFilters()
        }.build()
        return getMangaListFromBrowse(url)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val authorIds = filters.firstInstanceOrNull<Filters.AuthorFilter>()?.state
            ?.let { resolveTagIdsForNames("author", it) }
            .orEmpty()
        val artistIds = filters.firstInstanceOrNull<Filters.ArtistFilter>()?.state
            ?.let { resolveTagIdsForNames("artist", it) }
            .orEmpty()
        val tagIds = filters.firstInstanceOrNull<Filters.TagsFilter>()?.state
            ?.let { resolveTagIdsForNames("tag", it) }
            .orEmpty()
        val hasTermSelection = filters.filterIsInstance<Filters.TermFilter>()
            .any { it.hasSelection } || tagIds.isNotEmpty()
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("browse")
            .apply {
                filters.filterIsInstance<Filters.UriFilter>()
                    .filterNot { it is Filters.RequiresTermSelection && !hasTermSelection }
                    .forEach {
                        if (it is Filters.QueryAwareFilter) {
                            it.addToUri(this, query)
                        } else {
                            it.addToUri(this)
                        }
                    }

                authorIds.forEach { addQueryParameter("authors[]", it) }
                artistIds.forEach { addQueryParameter("artists[]", it) }
                tagIds.forEach { addQueryParameter("genres_in[]", it) }

                if (query.isNotBlank()) {
                    addQueryParameter("keyword", query)
                }

                addQueryParameter("page", page.toString())
            }.build()

        return getMangaListFromBrowse(url)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        if (url.pathSegments.size < 2 || url.pathSegments[0] != "title") return null

        val mangaSlug = url.pathSegments[1]
        val mangaId = mangaSlug.substringBefore("-").takeIf { it.isNotEmpty() } ?: return null
        val manga = SManga.create().apply {
            this.url = "/$mangaSlug"
            memo = buildJsonObject { put(MANGA_ID_MEMO, mangaId) }
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            this.url = manga.url
        }
    }

    private fun sourceFilters() = Filters(
        contentRating = preferences.contentRating(),
        selectedTypes = preferences.defaultTypes(),
        selectedDemographics = preferences.defaultDemographics(),
        blockedGenres = preferences.blockedGenres(),
    )

    private fun HttpUrl.Builder.applyPreferenceFilters() {
        sourceFilters().getFilterList()
            .filterIsInstance<Filters.PreferenceFilter>()
            .forEach { it.addToUri(this) }
    }

    private suspend fun resolveTagIdsForNames(type: String, raw: String): List<String> {
        val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return buildList {
            names.forEach { addAll(resolveTagIds(type, it)) }
        }
    }

    private suspend fun resolveTagIds(type: String, name: String): List<String> {
        val cacheKey = "$type\u0000${name.lowercase()}"
        synchronized(tagIdCache) { tagIdCache[cacheKey] }?.let { return it }

        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("keyword", name)
            .build()

        val ids = runCatching {
            client.get(url).parseAs<TagSearchResponse>().result.map { it.id.toString() }
        }.getOrNull() ?: return emptyList()
        synchronized(tagIdCache) { tagIdCache[cacheKey] = ids }
        return ids
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        var cachedDocument: Document? = null
        suspend fun getDocument(): Document {
            cachedDocument?.let { return it }
            return fetchPage(getMangaUrl(manga)).also { cachedDocument = it }
        }

        val deduplicateChapters = preferences.deduplicateChapters()
        val storedDeduplicateChapters = manga.memo[CHAPTER_LIST_DEDUPLICATED_MEMO]?.booleanOrNull
        val fetchUntilKnown = fetchChapters &&
            preferences.fetchChaptersUntilKnown() &&
            storedDeduplicateChapters == deduplicateChapters
        val latestChapterId = chapters.firstOrNull()
            ?.takeIf { fetchUntilKnown }
            ?.chapterId()

        fun mergeChapters(fetched: List<SChapter>) = if (fetchUntilKnown) {
            (fetched + chapters).distinctBy(SChapter::url)
        } else {
            fetched
        }

        val nativeChapters = if (fetchChapters && proxyServer() != null) {
            async { getNativeChapterList(manga, latestChapterId) }
        } else {
            null
        }

        val updatedManga = if (fetchDetails) parseMangaDetails(getDocument()) else manga
        val updatedChapters = if (fetchChapters) {
            val fetched = nativeChapters?.await()
                ?: throw Exception("Chapter list unavailable; is the proxy server configured?")
            mergeChapters(fetched)
        } else {
            chapters
        }
        val chapterListMode = if (fetchChapters) deduplicateChapters else storedDeduplicateChapters
        if (chapterListMode != null) {
            updatedManga.memo = buildJsonObject {
                updatedManga.memo.forEach { (key, value) -> put(key, value) }
                put(CHAPTER_LIST_DEDUPLICATED_MEMO, chapterListMode)
            }
        }
        SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga {
        val detail = document.extractInitialQueries()
            .entries.firstOrNull { (key, _) -> key.contains("\"detail\"") }
            ?.value
            ?: throw Exception("Could not find manga detail in queries")

        return detail.parseAs<Manga>().toSManga(
            preferences.posterQuality(),
            preferences.alternativeNamesInDescription(),
            preferences.scorePosition(),
            preferences.showExtraInfo(),
            preferences.showTagsInGenres(),
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title${manga.url}"

    private fun SManga.mangaId(): String? = memo[MANGA_ID_MEMO]?.string
        ?: getMangaUrl(this).toHttpUrlOrNull()
            ?.pathSegments
            ?.getOrNull(1)
            ?.substringBefore('-')
            ?.takeIf { it.isNotEmpty() }

    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val related = document.extractInitialQueries()
            .entries.firstOrNull { (key, _) -> key.contains("\"recommended\"") }
            ?.value
            ?: return emptyList()

        return related.parseAs<SearchResponse>().items.map {
            it.toBasicSManga(preferences.posterQuality())
        }
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/${chapter.url}"

    private fun SChapter.chapterId(): Int? = memo[CHAPTER_ID_MEMO]?.int
        ?: getChapterUrl(this).toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            ?.substringBefore("-chapter-")
            ?.toIntOrNull()

    private fun buildChapters(allChapters: List<Chapter>, mangaSlug: String): List<SChapter> {
        val blacklist = preferences.scanlatorBlacklist()
        val filteredChapters = if (blacklist.isNotEmpty()) {
            allChapters.filter { ch ->
                val scanlatorName = when {
                    ch.group != null -> ch.group.name
                    ch.isOfficial -> "Official"
                    else -> "Unknown"
                }
                val nameNormalized = scanlatorName.trim().lowercase()
                val idStr = ch.group?.id?.toString()

                nameNormalized !in blacklist && idStr !in blacklist
            }
        } else {
            allChapters
        }

        val finalChapters: List<Chapter> = if (preferences.deduplicateChapters()) {
            val chapterMap = LinkedHashMap<Number, Chapter>()
            deduplicateChapters(chapterMap, filteredChapters)
            chapterMap.values.toList()
        } else {
            filteredChapters
        }

        return finalChapters.map { it.toSChapter(mangaSlug) }
    }

    private fun deduplicateChapters(
        chapterMap: LinkedHashMap<Number, Chapter>,
        items: List<Chapter>,
    ) {
        for (ch in items) {
            val key = ch.number
            val current = chapterMap[key]
            if (current == null) {
                chapterMap[key] = ch
            } else {
                val newIsOfficial = ch.isOfficial
                val currentIsOfficial = current.isOfficial
                val newIsGroup10702 = ch.group?.id == 10702
                val currentIsGroup10702 = current.group?.id == 10702

                val better = when {
                    newIsOfficial && !currentIsOfficial -> true
                    !newIsOfficial && currentIsOfficial -> false
                    newIsGroup10702 && !currentIsGroup10702 -> true
                    !newIsGroup10702 && currentIsGroup10702 -> false
                    else -> when {
                        ch.votes > current.votes -> true
                        ch.votes < current.votes -> false
                        else -> ch.id > current.id
                    }
                }
                if (better) chapterMap[key] = ch
            }
        }
    }

    private suspend fun getNativeChapterList(manga: SManga, latestChapterId: Int?): List<SChapter>? {
        if (proxyServer() == null) return null
        val mangaSlug = getMangaUrl(manga).toHttpUrl().pathSegments.getOrNull(1) ?: return null
        val mangaId = manga.mangaId() ?: return null
        val chapters = mutableListOf<Chapter>()
        var page = 1
        while (page <= MAX_CHAPTER_PAGES) {
            val response = getSigned<ChapterDetailsResponse>(
                "/api/v1/manga/$mangaId/chapters",
                mapOf(
                    // The site's own reader pages by 20, and so does this. A bigger page is
                    // not a shortcut: measured against the proxy, limit=100 serves exactly
                    // one request before the cookie stops answering, while limit=20 serves
                    // 45+ on the same cookie. So a 500-chapter title costs 25 cheap requests
                    // on one jar instead of 5 requests each exhausting a jar of its own.
                    "limit" to listOf("20"),
                    "order[number]" to listOf("desc"),
                    "page" to listOf(page.toString()),
                ),
            ) ?: return null
            chapters += response.items
            val reachedKnown = response.items.any { it.id == latestChapterId }
            if (reachedKnown || !response.hasNextPage() || response.items.isEmpty()) break
            page++
        }
        return buildChapters(chapters, mangaSlug)
    }

    // V3 grid-scramble pages must NOT send Origin — the server withholds X-Scramble-Seed when
    // Origin is present. Legacy byte-XOR pages need Origin to receive X-Enc-Seed.
    // Comix image domains block any image request that carries Referer/Origin
    //
    // The fragment carries the image mirrors and must survive into the request: it is never
    // sent to the server, but altHostFallback reads it to retry across them. Dropping it
    // here silences the retry entirely.
    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        val requestHeaders = headersBuilder()
            .removeAll("Origin")
            .removeAll("Referer")
            .build()
        return GET(imageUrl, requestHeaders)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = getNativePageList(chapter)
        ?: throw Exception("Page list unavailable; is the proxy server configured?")

    private fun buildPages(response: ChapterResponse): List<Page> {
        val pages = response.pages
        val base = pages.baseUrl.trimEnd('/')

        return pages.items.mapIndexed { index, img ->
            val full = (if (img.url.startsWith("http")) img.url else "$base/${img.url.trimStart('/')}").toHttpUrl()
            // V3 pages need the query flag so the server returns grid-scramble headers.
            val isV3 = img.s == 1 || "v3" in full.queryParameterNames
            val url = full.newBuilder().apply {
                if (isV3 && "v3" !in full.queryParameterNames) {
                    addQueryParameter("v3", null)
                }
                val altHosts = pages.altHosts(full.host, index)
                if (altHosts.isNotEmpty()) fragment(ALT_HOSTS_FRAGMENT + altHosts.joinToString(","))
            }.build().toString()
            Page(index, imageUrl = url)
        }
    }

    private suspend fun getNativePageList(chapter: SChapter): List<Page>? {
        if (proxyServer() == null) return null
        val chapterId = chapter.chapterId() ?: return null
        return getSigned<ChapterResponse>("/api/v1/chapters/$chapterId", emptyMap())?.let(::buildPages)
    }

// ====================== API through the proxy =========================
    // The /api/v1/* endpoints need a `_` token the site's bundle mints, and answer with an
    // `{"e": ...}` envelope the same bundle unwraps. The client also sits behind Turnstile,
    // whose clearance cannot be obtained off-device. So the call is made through the proxy,
    // which owns the bundle: it signs, carries the cleared cookies and returns plain JSON.
    //
    // A null result means "unavailable", and every caller turns that into a clear error
    // rather than a silent empty list.

    override fun getFilterList(data: JsonElement?) = sourceFilters().getFilterList()

    private suspend inline fun <reified T> getSigned(
        path: String,
        params: Map<String, List<String>>,
    ): T? {
        val proxy = proxyServer() ?: return null
        val body = proxyRequest(proxy, path, params) ?: return null
        return runCatching { body.parseAs<T>() }.getOrNull()
    }

    private fun canonicalEntries(params: Map<String, List<String>>): List<Pair<String, String>> = buildList {
        params.toSortedMap().forEach { (rawName, values) ->
            val name = rawName.removeSuffix("[]")
            if (values.size == 1 && !rawName.endsWith("[]")) {
                add(name to values.single())
            } else {
                values.forEachIndexed { index, value -> add("$name[$index]" to value) }
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_PROXY_SERVER
            title = "Proxy server (comix-proxy)"
            summary = "Base URL of a comix-proxy instance. It makes the API calls for you: " +
                "the site answers API requests with a signed token and encrypted bodies and " +
                "gates clients with Turnstile, neither of which can be reproduced off-device " +
                "(e.g. http://192.168.1.10:9191). Required - without it no chapter, page or " +
                "search list can be read."
            dialogTitle = "Proxy server URL"
            setDefaultValue("http://127.0.0.1:9191")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FETCH_CHAPTERS_UNTIL_KNOWN
            title = "Faster chapter list fetching"
            summary = "Enabled: Uses fewer requests, but may miss newly added older chapters " +
                "(e.g. chapter 5.5 when the latest known chapter is 150).\n\n" +
                "Disabled: Finds older chapter additions, but fetching large chapter lists is slower."
            setDefaultValue(true)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_POSTER_QUALITY
            title = "Thumbnail Quality"
            summary = "Change the quality of the thumbnail. Current: %s."
            entryValues = arrayOf("small", "medium", "large")
            entries = arrayOf("Small", "Medium", "Large")
            setDefaultValue("large")
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Content rating"
            summary = "Maximum content rating shown in popular, latest, and search " +
                "results. The Content rating filter in search overrides this. " +
                "Current: %s."
            entries = arrayOf("Show all", "Safe only", "Up to Suggestive", "Up to Erotica", "Up to Pornographic")
            entryValues = arrayOf("", "safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(DEFAULT_CONTENT_RATING)
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_TYPES
            title = "Default types"
            summary = "Types to include in popular, latest, and search results. " +
                "The Type filter in search overrides this."
            entries = Filters.getTypes().map { it.first }.toTypedArray()
            entryValues = Filters.getTypes().map { it.second }.toTypedArray()
            setDefaultValue(Filters.getTypes().map { it.second }.toSet())
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_DEMOGRAPHICS
            title = "Default demographics"
            summary = "Demographics to include in popular, latest, and search " +
                "results. The Demographic filter in search overrides this."
            entries = Filters.getDemographics().map { it.first }.toTypedArray()
            entryValues = Filters.getDemographics().map { it.second }.toTypedArray()
            setDefaultValue(Filters.getDemographics().map { it.second }.toSet())
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Genres always excluded from results. The search filter " +
                "can still include a blocked genre as a one-off override."
            entries = Filters.getGenres().map { it.first }.toTypedArray()
            entryValues = Filters.getGenres().map { it.second }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DEDUPLICATE_CHAPTERS
            title = "Deduplicate Chapters"
            summary = "Remove duplicate chapters from the chapter list.\n" +
                "Official chapters (Comix-marked) are preferred, followed by the highest-voted or most recent.\n" +
                "Warning: It can be slow on large lists."
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_BLACKLIST
            title = "Scanlator Blacklist"
            summary =
                "Filter out chapters from specific groups. Comma-separated list of group names or group IDs (e.g., 'Violet Scans, 307')."
            dialogTitle = "Exclude groups"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_NAMES_IN_DESCRIPTION
            title = "Show Alternative Names in Description"
            setDefaultValue(false)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Append publication year, language, content rating, rank, " +
                "ratings count, and follower count to the manga description."
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRES
            title = "Show tags in genre chips"
            summary = "Include the site's narrative tag list (e.g. Demons, " +
                "Vampires, Time Travel) alongside the curated genres in the " +
                "manga details. Off by default — the curated set matches what " +
                "the site itself shows on the page."
            setDefaultValue(false)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "%s"
            entries = arrayOf("Top of description", "Bottom of description", "Don't show")
            entryValues = arrayOf("top", "bottom", "none")
            setDefaultValue("top")
        }.let(screen::addPreference)
    }

    private fun SharedPreferences.posterQuality() = getString(PREF_POSTER_QUALITY, "large")

    private fun SharedPreferences.deduplicateChapters() = getBoolean(DEDUPLICATE_CHAPTERS, false)

    private fun SharedPreferences.fetchChaptersUntilKnown() = getBoolean(PREF_FETCH_CHAPTERS_UNTIL_KNOWN, true)

    private fun SharedPreferences.scanlatorBlacklist(): Set<String> = getString(PREF_SCANLATOR_BLACKLIST, "")
        ?.split(",")
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet() ?: emptySet()

    private fun SharedPreferences.alternativeNamesInDescription() = getBoolean(ALTERNATIVE_NAMES_IN_DESCRIPTION, false)

    private fun SharedPreferences.scorePosition() = getString(PREF_SCORE_POSITION, "top") ?: "top"

    private fun SharedPreferences.showExtraInfo() = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun SharedPreferences.showTagsInGenres() = getBoolean(PREF_SHOW_TAGS_IN_GENRES, false)

    private fun SharedPreferences.defaultTypes(): Set<String> {
        val all = Filters.getTypes().map { it.second }.toSet()
        return getStringSet(PREF_DEFAULT_TYPES, all) ?: all
    }

    private fun SharedPreferences.defaultDemographics(): Set<String> {
        val all = Filters.getDemographics().map { it.second }.toSet()
        return getStringSet(PREF_DEFAULT_DEMOGRAPHICS, all) ?: all
    }

    private fun SharedPreferences.blockedGenres(): Set<String> = getStringSet(PREF_BLOCKED_GENRES, emptySet()) ?: emptySet()

    // The legacy "Hide NSFW" boolean still exists in some users' preferences;
    // map it to a sensible default until they pick a value explicitly.
    private fun SharedPreferences.contentRating(): String {
        if (contains(PREF_CONTENT_RATING)) {
            return getString(PREF_CONTENT_RATING, DEFAULT_CONTENT_RATING) ?: DEFAULT_CONTENT_RATING
        }
        if (contains(LEGACY_HIDE_NSFW_PREF) && !getBoolean(LEGACY_HIDE_NSFW_PREF, true)) {
            return ""
        }
        return DEFAULT_CONTENT_RATING
    }

    companion object {
        private const val PREF_POSTER_QUALITY = "pref_poster_quality"
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_DEFAULT_TYPES = "pref_default_types"
        private const val PREF_DEFAULT_DEMOGRAPHICS = "pref_default_demographics"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val LEGACY_HIDE_NSFW_PREF = "nsfw_pref"
        private const val DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_FETCH_CHAPTERS_UNTIL_KNOWN = "pref_fetch_chapters_until_known"
        private const val PREF_SCANLATOR_BLACKLIST = "pref_scanlator_blacklist"
        private const val ALTERNATIVE_NAMES_IN_DESCRIPTION = "pref_alt_names_in_description"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRES = "pref_show_tags_in_genres"
        private const val PREF_SCORE_POSITION = "pref_score_position"
        private const val PREF_PROXY_SERVER = "pref_proxy_server"
        private const val PREF_PROXY_COOKIE = "pref_proxy_cookie"
        private const val PREF_PROXY_USER_AGENT = "pref_proxy_user_agent"

        private const val DEFAULT_CONTENT_RATING = "suggestive"

        // Matches upstream. The site's own reader walks every page of a long title, and an
        // 11k-chapter series is 569 pages at limit=20, so a lower cap silently truncates it -
        // at 200 this stopped after 4,000 of 11,364 chapters.
        private const val MAX_CHAPTER_PAGES = 1000
        private const val TAG_ID_CACHE_SIZE = 50
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("/(?:i5|s?i+)/")
        private const val ALT_HOSTS_FRAGMENT = "alt="
    }
}
