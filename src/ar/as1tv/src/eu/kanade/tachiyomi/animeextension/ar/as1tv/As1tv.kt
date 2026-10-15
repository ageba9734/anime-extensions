package eu.kanade.tachiyomi.animeextension.ar.as1tv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class As1tv : ParsedAnimeHttpSource() {

    override val name = "Asia Drama"
    override val baseUrl = "https://as1tv.com"
    override val lang = "ar"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    // مؤكد من HTML: article.post > .block-post2 > a ، العنوان .blockTitle ، الصورة data-img
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/drama/page/$page/", headers)

    override fun popularAnimeSelector() = "article.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst(".blockTitle")?.text()
            ?: element.selectFirst("a")!!.attr("title")
        thumbnail_url = element.selectFirst("img")?.let {
            it.attr("data-img").ifBlank { it.attr("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/drama/page/$page/", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.toValue().orEmpty()

        if (query.isBlank() && section.isNotBlank()) {
            return GET("$baseUrl/$section/page/$page/".toHttpUrl(), headers)
        }

        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلتر يعمل عند ترك البحث فارغاً"),
        SectionFilter(),
    )

    private class SectionFilter : AnimeFilter.Select<String>(
        "القسم",
        SECTIONS.map { it.first }.toTypedArray(),
    ) {
        fun toValue() = SECTIONS[state].second
    }

    // ============================== Details ===============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst(".poster-wrapper img")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        description = document.selectFirst(".description")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select(".single_tax a[href*=/genre/]").joinToString { it.text().trim() }
        artist = document.select("ul.team li > div > span").joinToString { it.text().trim() }
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    // مؤكد من HTML: ul.list-eps li a ، والعنوان في title مثل "الحلقة 5 إلى أختي الوحيدة"
    // ملاحظة: يعرض الموسم الحالي فقط (ul.list-seasons فيها روابط المواسم الأخرى)
    override fun episodeListSelector() = "ul.list-eps li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector())
            .distinctBy { it.attr("href") }
            .map { episodeFromElement(it) }
            .sortedByDescending { it.episode_number }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val title = element.attr("title").ifBlank { element.text() }.trim()
        val number = Regex("""الحلقة\s*(\d+)""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
            ?: Regex("""(\d+)""").find(element.text())?.groupValues?.get(1)?.toFloatOrNull()
            ?: 1F
        episode_number = number
        name = title.ifBlank { "الحلقة ${number.toInt()}" }
        scanlator = element.selectFirst("span")?.text()?.trim()
    }

    // ================================ Video ===============================
    // مؤكد: زر "مشاهدة الحلقة" هو نموذج POST إلى https://asiawiki.me مع الحقل epwatch=<رقم الحلقة>
    // غير مؤكد: شكل الصفحة التي يرجعها asiawiki.me، لذلك البحث هنا عام (iframe / m3u8 / mp4)
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeUrl = response.request.url.toString()
        val epId = document.selectFirst("input[name=epwatch]")?.attr("value")
            ?: throw Exception("لم يتم العثور على رقم الحلقة (epwatch)")

        // 1) نفس زر "مشاهدة الحلقة": POST إلى asiawiki.me (يضع كوكي episode_id)
        val postHeaders = headers.newBuilder()
            .set("Referer", episodeUrl)
            .set("Origin", baseUrl)
            .build()
        client.newCall(POST(WATCH_URL, postHeaders, FormBody.Builder().add("epwatch", epId).build()))
            .execute().close()

        // 2) نفس سكربت الصفحة: fetch_episode بالرقم من الكوكي (وإلا نفس الرقم)
        val cookieId = client.cookieJar.loadForRequest(WATCH_URL.toHttpUrl())
            .firstOrNull { it.name == "episode_id" }?.value
            ?: epId
        val apiUrl = "$WATCH_URL/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "fetch_episode")
            .addQueryParameter("id", cookieId)
            .build()
        val apiHeaders = headers.newBuilder()
            .set("Referer", "$WATCH_URL/")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val json = client.newCall(GET(apiUrl, apiHeaders)).execute().use { it.body.string() }

        val servers = JSONObject(json).optJSONObject("servers")
            ?: throw Exception(
                if (document.text().contains("العضوية الذهبية")) "هذه الحلقة للأعضاء الذهبيين"
                else "لا توجد سيرفرات لهذه الحلقة",
            )

        // 3) كل سيرفر قيمته كود iframe
        val videos = mutableListOf<Video>()
        servers.keys().forEach { serverName ->
            val html = servers.optString(serverName)
            val frameUrl = Jsoup.parse(html).selectFirst("iframe")?.attr("src")
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: html.takeIf { it.startsWith("http") }
                ?: return@forEach
            runCatching { videos += videosFromServer(serverName, frameUrl) }
        }

        if (videos.isEmpty()) throw Exception("تعذر استخراج الفيديو من السيرفرات")
        return videos.distinctBy { it.videoUrl }
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private fun videosFromServer(serverName: String, url: String): List<Video> {
        val n = serverName.lowercase()
        return when {
            "dood" in n -> doodExtractor.videosFromUrl(url, "DoodStream")
            "streamwish" in n -> streamWishExtractor.videosFromUrl(url) { "StreamWish - $it" }
            "ok.ru" in n || "okru" in n -> okruExtractor.videosFromUrl(url, "Ok.ru - ")
            // FileMoon / VidHide / VK / AsiaTvPlayer / LuluVdo / Vgembed / Vidmoly: بحث عام عن m3u8 و mp4 (يمكن إضافة extractors لاحقاً)
            else -> {
                val frameHeaders = headers.newBuilder().set("Referer", "$WATCH_URL/").build()
                val body = client.newCall(GET(url, frameHeaders)).execute().use { it.body.string() }
                directVideos(body, url, serverName)
            }
        }
    }

    private fun directVideos(text: String, referer: String, label: String): List<Video> {
        val vHeaders = headers.newBuilder().set("Referer", referer).build()
        return Regex("""https?:\\?/\\?/[^s"'<>\\]+?\.(?:m3u8|mp4)[^s"'<>\\]*""")
            .findAll(text)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .map { Video(it, label + if (it.contains(".m3u8")) " - HLS" else " - MP4", it, vHeaders) }
            .toList()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    companion object {
        private const val WATCH_URL = "https://asiawiki.me"

        private val SECTIONS = listOf(
            "الكل" to "",
            "الدراما الكورية" to "types/الدراما-الكورية",
            "الدراما الصينية" to "types/الدراما-الصينية",
            "الدراما التايلاندية" to "types/الدراما-التايلاندية",
            "الدراما التايونية" to "types/الدراما-التايونية",
            "الدراما اليابانية" to "types/الدراما-اليابانية",
            "الدراما التركية" to "types/الدراما-التركية",
            "الدراما الفلبينية" to "types/الدراما-الفلبنيه",
            "برامج ترفيهية" to "types/برامج-ترفيهية",
            "أفلام آسيوية" to "types/افلام-اسيوية",
            "دراما مكتملة" to "دراما-مكتملة",
            "دراما تبث حاليا" to "دراما-تبث-حاليا",
            "الأكثر تقييما" to "الدراما-الاكثر-تقييما",
        )
    }
}
