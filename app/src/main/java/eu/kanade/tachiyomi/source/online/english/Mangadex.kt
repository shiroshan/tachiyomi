package eu.kanade.tachiyomi.source.online.english

import com.github.salomonbrys.kotson.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.model.SManga.FollowStatus
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.set

open class Mangadex(override val lang: String, private val internalLang: String, private val langCode: Int) : HttpSource() {

    override val name = "MangaDex"

    override val baseUrl = "https://mangadex.org"

    private val cdnUrl = "https://cdndex.com"

    private val preferences: PreferencesHelper by injectLazy()

    private fun clientBuilder(): OkHttpClient = clientBuilder(preferences.r18()!!.toInt())

    private fun clientBuilder(r18Toggle: Int): OkHttpClient = network.cloudflareClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val originalCookies = chain.request().header("Cookie") ?: ""
                val newReq = chain
                        .request()
                        .newBuilder()
                        .header("Cookie", "$originalCookies; ${cookiesHeader(r18Toggle, langCode)}")
                        .build()
                chain.proceed(newReq)
            }.build()!!


    private fun cookiesHeader(r18Toggle: Int, langCode: Int): String {
        val cookies = mutableMapOf<String, String>()
        cookies["mangadex_h_toggle"] = r18Toggle.toString()
        cookies["mangadex_filter_langs"] = langCode.toString()
        return buildCookies(cookies)
    }

    private fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ", postfix = ";") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    private fun popularMangaSelector() = "div.manga-entry"

    private fun latestUpdatesSelector() = "tr a.manga_title"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/titles/0/$page/", headersBuilder().build())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/updates/$page", headersBuilder().build())
    }

    private fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a.manga_title").first().let {
            val url = modifyMangaUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.title = it.text().trim()
        }
        manga.thumbnail_url = formThumbUrl(manga.url)

        return manga
    }

    private fun modifyMangaUrl(url: String): String = url.replace("/title/", "/manga/").substringBeforeLast("/") + "/"

    private fun formThumbUrl(mangaUrl: String): String {
        return cdnUrl + "/images/manga/" + getMangaId(mangaUrl) + ".jpg"
    }

     private fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.let {
            manga.setUrlWithoutDomain(modifyMangaUrl(it.attr("href")))
            manga.title = it.text().trim()

        }
        manga.thumbnail_url = formThumbUrl(manga.url)

        return manga
    }

     private fun popularMangaNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

     private fun latestUpdatesNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

     private fun searchMangaNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

     private fun followsNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return clientBuilder().newCall(popularMangaRequest(page))
                .asObservableSuccess()
                .map { response ->
                    popularMangaParse(response)
                }
    }
    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }.distinct()

        val hasNextPage = popularMangaNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }


    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }

        val hasNextPage = latestUpdatesNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_ID_SEARCH)
            clientBuilder().newCall(searchMangaByIdRequest(realQuery))
                    .asObservableSuccess()
                    .map { response ->
                        val details = mangaDetailsParse(response)
                        details.url = "/manga/$realQuery/"
                        MangasPage(listOf(details), false)
                    }
        } else {
            buildR18Client(filters).newCall(searchMangaRequest(page, query, filters))
                    .asObservableSuccess()
                    .map { response ->
                        searchMangaParse(response)
                    }
        }
    }
    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }

        val hasNextPage = searchMangaNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun buildR18Client(filters: FilterList): OkHttpClient {
        filters.forEach { filter ->
            when (filter) {
                is R18 -> {
                    return when (filter.state) {
                        1 -> clientBuilder(ALL)
                        2 -> clientBuilder(ONLY_R18)
                        3 -> clientBuilder(NO_R18)
                        else -> clientBuilder()
                    }
                }
            }
        }
        return clientBuilder()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {

        val genresToInclude = mutableListOf<String>()
        val genresToExclude = mutableListOf<String>()

        // Do traditional search
        val url = "$baseUrl/?page=search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("p", page.toString())
                .addQueryParameter("title", query.replace(WHITESPACE_REGEX, " "))

        filters.forEach { filter ->
            when (filter) {
                is TextField -> url.addQueryParameter(filter.key, filter.state)
                is Demographic -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("demo_id", filter.state.toString())
                    }
                }
                is PublicationStatus -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status_id", filter.state.toString())
                    }
                }
                is OriginalLanguage -> {
                    if (filter.state != 0) {
                        val number: String = SOURCE_LANG_LIST.first { it -> it.first == filter.values[filter.state] }.second
                        url.addQueryParameter("lang_id", number)
                    }
                }
                is TagInclusionMode -> {
                    url.addQueryParameter("tag_mode_inc", arrayOf("all", "any")[filter.state])
                }
                is TagExclusionMode -> {
                    url.addQueryParameter("tag_mode_exc", arrayOf("all", "any")[filter.state])
                }
                is ContentList -> {
                    filter.state.forEach { content ->
                        if (content.isExcluded()) {
                            genresToExclude.add(content.id)
                        } else if (content.isIncluded()) {
                            genresToInclude.add(content.id)
                        }
                    }
                }
                is FormatList -> {
                    filter.state.forEach { format ->
                        if (format.isExcluded()) {
                            genresToExclude.add(format.id)
                        } else if (format.isIncluded()) {
                            genresToInclude.add(format.id)
                        }
                    }
                }
                is GenreList -> {
                    filter.state.forEach { genre ->
                        if (genre.isExcluded()) {
                            genresToExclude.add(genre.id)
                        } else if (genre.isIncluded()) {
                            genresToInclude.add(genre.id)
                        }
                    }
                }
                is ThemeList -> {
                    filter.state.forEach { theme ->
                        if (theme.isExcluded()) {
                            genresToExclude.add(theme.id)
                        } else if (theme.isIncluded()) {
                            genresToInclude.add(theme.id)
                        }
                    }
                }
                is SortFilter -> {
                    if (filter.state != null) {
                        if (filter.state!!.ascending) {
                            url.addQueryParameter("s", sortables[filter.state!!.index].second.toString())
                        } else {
                            url.addQueryParameter("s", sortables[filter.state!!.index].third.toString())
                        }
                    }
                }
            }
        }

        // Manually append genres list to avoid commas being encoded
        var urlToUse = url.toString()
        if (genresToInclude.isNotEmpty()) {
            urlToUse += "&tags_inc=" + genresToInclude.joinToString(",")
        }
        if (genresToExclude.isNotEmpty()) {
            urlToUse += "&tags_exc=" + genresToExclude.joinToString(",")
        }

        return GET(urlToUse, headersBuilder().build())
    }

    private fun searchMangaSelector() = "div.manga-entry"

    private fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        element.select("a.manga_title").first().let {
            val url = modifyMangaUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.title = it.text().trim()
        }

        manga.thumbnail_url = formThumbUrl(manga.url)


        return manga
    }

    override fun fetchFollows(page: Int): Observable<MangasPage> {
        return clientBuilder().newCall(followsListRequest(page))
                .asObservable()
                .map { response ->
                    followsParse(response)
                }
    }


    private fun followsParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val follows = document
                .select(followSelector())
                .map { followFromElement(it) }

        val hasNextPage = document
                .select(followsNextPageSelector())
                .isNotEmpty()

        val estimatedTotalFollows = document
                .select(estimatedTotalFollowsSelector())
                .text()
                .split(' ')
                .last { it.toIntOrNull() is Int } // TODO: Try to deduplicate toInt()
                .toInt()

        return MangasPage(follows, hasNextPage, estimatedTotalFollows)
    }

    protected fun followsListRequest(page: Int): Request {

        // Format `/follows/manga/$status[/$sort[/$page]]`
        val url = "$baseUrl/follows/manga/0".toHttpUrlOrNull()!!.newBuilder() // Gets regardless of follow status.
                .addQueryParameter("p", page.toString())

        /*  filters.forEach { filter ->
              when (filter) {
                  is TextField -> url.addQueryParameter(filter.key, filter.state)
                  is SortFilter -> {
                      if (filter.state != null) {
                          if (filter.state!!.ascending) {
                              url.addQueryParameter("s", sortables[filter.state!!.index].second.toString())
                          } else {
                              url.addQueryParameter("s", sortables[filter.state!!.index].third.toString())
                          }
                      }
                  }
              }*/
        //}


        return GET(url.toString(), headersBuilder().build())
    }

    private fun followSelector() = "div.manga-entry"
    private fun estimatedTotalFollowsSelector() = "div.manga-entry:last-of-type + *" // The element immediately following the last follow entry

    private fun followFromElement(element: Element): SManga {
        val manga = SManga.create()

        element.select("a.manga_title").first().let {
            val url = modifyMangaUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.title = it.text().trim()
        }

        manga.thumbnail_url = formThumbUrl(manga.url)

        manga.follow_status = getFollowStatusFromElement(element).let { FollowStatus.fromMangadex(it!!) }

        return manga
    }

    private fun getFollowStatusFromElement(element: Element): String? {
        var dropdownElement = element.select("button.btn.btn-success.dropdown-toggle").first()
                ?: element.select("button.btn.btn-warning.dropdown-toggle").first()
                ?: element.select("button.btn.btn-danger.dropdown-toggle").first()
                ?: element.select("button.btn.btn-info.dropdown-toggle").first()
                ?: element.select("button.btn.btn-secondary.dropdown-toggle").first()
                ?: element.select("button.btn.btn-primary.dropdown-toggle").first() //This should be last because it can match the Rating dropdown. The Follow Status dropdown should be what will be returned by `first()` since it should appear earlier in the HTML

        if (dropdownElement.select("span.fa-star").first() != null) { // Well, FUCK
            //Contingency. May not work if the earlier code didn't
            dropdownElement = element.select("button.btn.btn-success.dropdown-toggle:has(span.fas.fa-fw:not(.fa-star))").first()
        }

        return dropdownElement?.text()?.trim()
    }

    fun changeFollowStatus(manga: SManga): Observable<Boolean> {
        manga.follow_status ?: throw IllegalArgumentException("Cannot tell MD server to set an null follow status")

        val mangaID = getMangaId(manga.url)
        val status = manga.follow_status!!.toMangadexInt()

        return clientBuilder().newCall(GET("$baseUrl/ajax/actions.ajax.php?function=manga_follow&id=$mangaID&type=$status", headers))
                .asObservable()
                .map { isAuthenticationSuccessful(it) }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return clientBuilder().newCall(apiRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    mangaDetailsParse(response).apply { initialized = true }
                }
    }

    private fun apiRequest(manga: SManga): Request {
        return GET(baseUrl + API_MANGA + getMangaId(manga.url), headers)
    }

    private fun searchMangaByIdRequest(id: String): Request {
        return GET(baseUrl + API_MANGA + id, headers)
    }

    private fun getMangaId(url: String): String {
        val lastSection = url.trimEnd('/').substringAfterLast("/")
        return if (lastSection.toIntOrNull() != null) {
            lastSection
        } else {
            //this occurs if person has manga from before that had the id/name/
            url.trimEnd('/').substringBeforeLast("/").substringAfterLast("/")
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = SManga.create()
        val jsonData = response.body!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject
        val mangaJson = json.getAsJsonObject("manga")
        val chapterJson = json.getAsJsonObject("chapter")
        manga.title = cleanString(mangaJson.get("title").string)
        manga.thumbnail_url = cdnUrl + mangaJson.get("cover_url").string
        manga.description = cleanString(mangaJson.get("description").string)
        manga.author = mangaJson.get("author").string
        manga.artist = mangaJson.get("artist").string
        manga.lang_flag = mangaJson.get("lang_flag").string
        val status = mangaJson.get("status").int
        val tempStatus = parseStatus(status)
        val finalChapterNumber = getFinalChapter(mangaJson)
        if ((tempStatus == SManga.PUBLICATION_COMPLETE || tempStatus == SManga.CANCELLED) && chapterJson != null && isMangaCompleted(chapterJson, finalChapterNumber)) {
            manga.status = SManga.COMPLETED
        } else if (tempStatus == SManga.PUBLICATION_COMPLETE && chapterJson != null && isOneshot(chapterJson, finalChapterNumber)) {
            manga.status = SManga.COMPLETED
        } else {
            manga.status = tempStatus
        }

        val genres = (if (mangaJson.get("hentai").int == 1) listOf("Hentai") else listOf()) +
                mangaJson.get("genres").asJsonArray.mapNotNull { GENRES.get(it.toString()) }
        manga.genre = genres.joinToString(", ")

        return manga
    }

    // Remove bbcode tags as well as parses any html characters in description or chapter name to actual characters for example &hearts; will show ♥
    private fun cleanString(string: String): String {
        val bbRegex = """\[(\w+)[^]]*](.*?)\[/\1]""".toRegex()
        var intermediate = string
                .replace("[list]", "")
                .replace("[/list]", "")
                .replace("[*]", "")
        // Recursively remove nested bbcode
        while (bbRegex.containsMatchIn(intermediate)) {
            intermediate = intermediate.replace(bbRegex, "$2")
        }
        return Parser.unescapeEntities(intermediate, false)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return clientBuilder().newCall(apiRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    chapterListParse(response)
                }
    }

    private fun getFinalChapter(jsonObj: JsonObject): String = jsonObj.get("last_chapter").string.trim()

    private fun isOneshot(chapterJson: JsonObject, lastChapter: String): Boolean {
        val chapter = chapterJson.takeIf { it.size() > 0 }?.get(chapterJson.keys().elementAt(0))?.obj?.get("title")?.string
        return if (chapter != null) {
            chapter == "Oneshot" || chapter.isEmpty() && lastChapter == "0"
        } else {
            false
        }
    }

    private fun isMangaCompleted(chapterJson: JsonObject, finalChapterNumber: String): Boolean {
        val count = chapterJson.entrySet()
                .filter { it.value.asJsonObject.get("lang_code").string == internalLang }
                .filter { doesFinalChapterExist(finalChapterNumber, it.value) }.count()
        return count != 0
    }

    private fun doesFinalChapterExist(finalChapterNumber: String, chapterJson: JsonElement) = finalChapterNumber.isNotEmpty() && finalChapterNumber == chapterJson.get("chapter").string.trim()

    override fun chapterListParse(response: Response): List<SChapter> {
        val now = Date().time
        val jsonData = response.body!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject
        val mangaJson = json.getAsJsonObject("manga")
        val status = mangaJson.get("status").int

        val finalChapterNumber = getFinalChapter(mangaJson)
        val chapterJson = json.getAsJsonObject("chapter")
        val chapters = mutableListOf<SChapter>()

        // Skip chapters that don't match the desired language, or are future releases
        chapterJson?.forEach { key, jsonElement ->
            val chapterElement = jsonElement.asJsonObject
            if (chapterElement.get("lang_code").string == internalLang && (chapterElement.get("timestamp").asLong * 1000) <= now) {
                chapters.add(chapterFromJson(key, chapterElement, finalChapterNumber, status))
            }
        }
        return chapters
    }

    private fun chapterFromJson(chapterId: String, chapterJson: JsonObject, finalChapterNumber: String, status: Int): SChapter {
        val chapter = SChapter.create()
        chapter.url = API_CHAPTER + chapterId
        val chapterName = mutableListOf<String>()
        // Build chapter name
        if (chapterJson.get("volume").string.isNotBlank()) {
            chapterName.add("Vol." + chapterJson.get("volume").string)
        }
        if (chapterJson.get("chapter").string.isNotBlank()) {
            chapterName.add("Ch." + chapterJson.get("chapter").string)
        }
        if (chapterJson.get("title").string.isNotBlank()) {
            if (!chapterName.isEmpty()) {
                chapterName.add("-")
            }
            chapterName.add(chapterJson.get("title").string)
        }
        //if volume, chapter and title is empty its a oneshot
        if (chapterName.isEmpty()) {
            chapterName.add("Oneshot")
        }
        if ((status == 2 || status == 3) && doesFinalChapterExist(finalChapterNumber, chapterJson)) {
            chapterName.add("[END]")
        }

        chapter.name = cleanString(chapterName.joinToString(" "))
        // Convert from unix time
        chapter.date_upload = chapterJson.get("timestamp").long * 1000
        val scanlatorName = mutableListOf<String>()
        if (!chapterJson.get("group_name").nullString.isNullOrBlank()) {
            scanlatorName.add(chapterJson.get("group_name").string)
        }
        if (!chapterJson.get("group_name_2").nullString.isNullOrBlank()) {
            scanlatorName.add(chapterJson.get("group_name_2").string)
        }
        if (!chapterJson.get("group_name_3").nullString.isNullOrBlank()) {
            scanlatorName.add(chapterJson.get("group_name_3").string)
        }
        chapter.scanlator = cleanString(scanlatorName.joinToString(" & "))

        return chapter
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val server = preferences.imageServer().toString()
        return GET("$baseUrl${chapter.url}?server=$server")
    }


    override fun pageListParse(response: Response): List<Page> {
        val jsonData = response.body!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject

        val pages = mutableListOf<Page>()

        val hash = json.get("hash").string
        val pageArray = json.getAsJsonArray("page_array")
        val server = json.get("server").string

        pageArray.forEach {
            val url = "$server$hash/${it.asString}"
            pages.add(Page(pages.size, "", getImageUrl(url)))
        }

        return pages
    }

    private fun parseStatus(status: Int) = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.PUBLICATION_COMPLETE
        3 -> SManga.CANCELLED
        4 -> SManga.HIATUS
        else -> SManga.UNKNOWN

    }

    private fun getImageUrl(attr: String): String {
        // Some images are hosted elsewhere
        if (attr.startsWith("http")) {
            return attr
        }
        return baseUrl + attr
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun isLogged(): Boolean {
        val httpUrl = baseUrl.toHttpUrlOrNull()!!
        return network.cookieManager.get(httpUrl).any { it.name == "mangadex_rememberme_token" }
    }

    override fun login(username: String, password: String, twoFactorCode: String): Observable<Boolean> {
        val formBody = FormBody.Builder()
                .add("login_username", username)
                .add("login_password", password)
                .add("no_js", "1")
                .add("remember_me", "1")

        twoFactorCode.let {
            formBody.add("two_factor", it)
        }

        return clientBuilder().newCall(POST("$baseUrl/ajax/actions.ajax.php?function=login", headers, formBody.build()))
                .asObservable()
                .map { isAuthenticationSuccessful(it) }
    }


    override fun isAuthenticationSuccessful(response: Response): Boolean {
        if (response.body!!.string()!!.isEmpty()) {
            return true
        }
        return false
    }


    private class TextField(name: String, val key: String) : Filter.Text(name)
    private class Tag(val id: String, name: String) : Filter.TriState(name)
    private class ContentList(contents: List<Tag>) : Filter.Group<Tag>("Content", contents)
    private class FormatList(formats: List<Tag>) : Filter.Group<Tag>("Format", formats)
    private class GenreList(genres: List<Tag>) : Filter.Group<Tag>("Genres", genres)
    private class R18 : Filter.Select<String>("R18+", arrayOf("Default", "Show all", "Show only", "Show none"))
    private class Demographic : Filter.Select<String>("Demographic", arrayOf("All", "Shounen", "Shoujo", "Seinen", "Josei"))
    private class PublicationStatus : Filter.Select<String>("Publication status", arrayOf("All", "Ongoing", "Completed", "Cancelled", "Hiatus"))
    private class ThemeList(themes: List<Tag>) : Filter.Group<Tag>("Themes", themes)
    private class TagInclusionMode : Filter.Select<String>("Tag inclusion mode", arrayOf("All (and)", "Any (or)"), 0)
    private class TagExclusionMode : Filter.Select<String>("Tag exclusion mode", arrayOf("All (and)", "Any (or)"), 1)

    class SortFilter : Filter.Sort("Sort",
            sortables.map { it.first }.toTypedArray(),
            Filter.Sort.Selection(0, true))

    private class OriginalLanguage : Filter.Select<String>("Original Language", SOURCE_LANG_LIST.map { it.first }.toTypedArray())

    override fun getFilterList() = FilterList(
            TextField("Author", "author"),
            TextField("Artist", "artist"),
            R18(),
            SortFilter(),
            Demographic(),
            PublicationStatus(),
            OriginalLanguage(),
            ContentList(getContentList()),
            FormatList(getFormatList()),
            GenreList(getGenreList()),
            ThemeList(getThemeList()),
            TagInclusionMode(),
            TagExclusionMode()
    )

    private fun getContentList() = listOf(
            Tag("9", "Ecchi"),
            Tag("32", "Smut"),
            Tag("49", "Gore"),
            Tag("50", "Sexual Violence")
    ).sortedWith(compareBy { it.name })

    private fun getFormatList() = listOf(
            Tag("1", "4-koma"),
            Tag("4", "Award Winning"),
            Tag("7", "Doujinshi"),
            Tag("21", "Oneshot"),
            Tag("36", "Long Strip"),
            Tag("42", "Adaptation"),
            Tag("43", "Anthology"),
            Tag("44", "Web Comic"),
            Tag("45", "Full Color"),
            Tag("46", "User Created"),
            Tag("47", "Official Colored"),
            Tag("48", "Fan Colored")
    ).sortedWith(compareBy { it.name })

    private fun getGenreList() = listOf(
            Tag("2", "Action"),
            Tag("3", "Adventure"),
            Tag("5", "Comedy"),
            Tag("8", "Drama"),
            Tag("10", "Fantasy"),
            Tag("13", "Historical"),
            Tag("14", "Horror"),
            Tag("17", "Mecha"),
            Tag("18", "Medical"),
            Tag("20", "Mystery"),
            Tag("22", "Psychological"),
            Tag("23", "Romance"),
            Tag("25", "Sci-Fi"),
            Tag("28", "Shoujo Ai"),
            Tag("30", "Shounen Ai"),
            Tag("31", "Slice of Life"),
            Tag("33", "Sports"),
            Tag("35", "Tragedy"),
            Tag("37", "Yaoi"),
            Tag("38", "Yuri"),
            Tag("41", "Isekai"),
            Tag("51", "Crime"),
            Tag("52", "Magical Girls"),
            Tag("53", "Philosophical"),
            Tag("54", "Superhero"),
            Tag("55", "Thriller"),
            Tag("56", "Wuxia")
    ).sortedWith(compareBy { it.name })

    private fun getThemeList() = listOf(
            Tag("6", "Cooking"),
            Tag("11", "Gyaru"),
            Tag("12", "Harem"),
            Tag("16", "Martial Arts"),
            Tag("19", "Music"),
            Tag("24", "School Life"),
            Tag("34", "Supernatural"),
            Tag("40", "Video Games"),
            Tag("57", "Aliens"),
            Tag("58", "Animals"),
            Tag("59", "Crossdressing"),
            Tag("60", "Demons"),
            Tag("61", "Delinquents"),
            Tag("62", "Genderswap"),
            Tag("63", "Ghosts"),
            Tag("64", "Monster Girls"),
            Tag("65", "Loli"),
            Tag("66", "Magic"),
            Tag("67", "Military"),
            Tag("68", "Monsters"),
            Tag("69", "Ninja"),
            Tag("70", "Office Workers"),
            Tag("71", "Police"),
            Tag("72", "Post-Apocalyptic"),
            Tag("73", "Reincarnation"),
            Tag("74", "Reverse Harem"),
            Tag("75", "Samurai"),
            Tag("76", "Shota"),
            Tag("77", "Survival"),
            Tag("78", "Time Travel"),
            Tag("79", "Vampires"),
            Tag("80", "Traditional Games"),
            Tag("81", "Virtual Reality"),
            Tag("82", "Zombies"),
            Tag("83", "Incest")
    ).sortedWith(compareBy { it.name })

    private val GENRES = (getContentList() + getFormatList() + getGenreList() + getThemeList()).map { it.id to it.name }.toMap()

    companion object {
        private val WHITESPACE_REGEX = "\\s".toRegex()

        // This number matches to the cookie
        private const val NO_R18 = 0
        private const val ALL = 1
        private const val ONLY_R18 = 2


        private const val API_MANGA = "/api/manga/"
        private const val API_CHAPTER = "/api/chapter/"

        private const val PREFIX_ID_SEARCH = "id:"

        private val sortables = listOf(
                Triple("Update date", 0, 1),
                Triple("Alphabetically", 2, 3),
                Triple("Number of comments", 4, 5),
                Triple("Rating", 6, 7),
                Triple("Views", 8, 9),
                Triple("Follows", 10, 11))

        private val SOURCE_LANG_LIST = listOf(
                Pair("All", "0"),
                Pair("Japanese", "2"),
                Pair("English", "1"),
                Pair("Polish", "3"),
                Pair("German", "8"),
                Pair("French", "10"),
                Pair("Vietnamese", "12"),
                Pair("Chinese", "21"),
                Pair("Indonesian", "27"),
                Pair("Korean", "28"),
                Pair("Spanish (LATAM)", "29"),
                Pair("Thai", "32"),
                Pair("Filipino", "34"))

        private val FOLLOW_STATUS_LIST = listOf(
                Triple(0, FollowStatus.UNFOLLOWED, "Unfollowed"),
                Triple(1, FollowStatus.READING, "Reading"),
                Triple(2, FollowStatus.COMPLETED, "Completed"),
                Triple(3, FollowStatus.ON_HOLD, "On hold"),
                Triple(4, FollowStatus.PLAN_TO_READ, "Plan to read"),
                Triple(5, FollowStatus.DROPPED, "Dropped"),
                Triple(6, FollowStatus.RE_READING, "Re-reading"))

        fun FollowStatus.Companion.fromMangadex(x: Int) = FOLLOW_STATUS_LIST.first { it.first == x }.second
        fun FollowStatus.Companion.fromMangadex(MangadexFollowString: String) = FOLLOW_STATUS_LIST.first { it.third == MangadexFollowString }.second
        fun FollowStatus.toMangadexInt() = FOLLOW_STATUS_LIST.first { it.second == this }.first
        fun FollowStatus.toMangadexString() = FOLLOW_STATUS_LIST.first { it.second == this }.third
    }
}
