package eu.kanade.tachiyomi.animeextension.ar.fivetv

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Fivetv :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Fivetv"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // The site rotates mirror domains, so the base URL is user-editable.
    // Everything derived from it must be rebuilt when it changes (see AGENTS.md).
    override var baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.toHttpUrlOrNull() != null }
            ?: DEFAULT_BASE_URL
        set(value) {
            val clean = value.trim().trimEnd('/')
            preferences.edit().putString(PREF_BASE_URL_KEY, clean).apply()
            docHeaders = headersBuilder().build()
            client = buildClient()
        }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private var docHeaders by LazyMutable { headersBuilder().build() }

    override var client: OkHttpClient by LazyMutable { buildClient() }

    private fun buildClient() = network.client.newBuilder()
        .rateLimit(3) { it.host == baseUrl.toHttpUrl().host }
        .build()

    // ============================== Popular ===============================
    // The site exposes no verified "popular" listing, so both tabs use the
    // empty-query search listing (approved by the user for Latest).

    override fun popularAnimeRequest(page: Int): Request = listingRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listingRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreId = filters.filterIsInstance<GenreFilter>().firstOrNull()?.id.orEmpty()
        return listingRequest(page, query.trim(), genreId)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    private fun listingRequest(page: Int, query: String = "", genreId: String = ""): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment("")
            .addQueryParameter("q", query)
            .apply { if (genreId.isNotEmpty()) addQueryParameter("genre[]", genreId) }
            .addQueryParameter("pg", page.toString())
            .build()
        return GET(url, docHeaders)
    }

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.ftv-home-poster-card").mapNotNull { element ->
            val link = element.selectFirst("a.ftv-home-poster-card__title") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text().trim()
                thumbnail_url = element.selectFirst("img")?.let { img ->
                    img.absUrl("data-src").ifEmpty { img.absUrl("src") }
                }
            }
        }.distinctBy { it.url }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val isMovie = response.request.url.encodedPath.startsWith("/movie/")
        return SAnime.create().apply {
            title = document.selectFirst("h1.ftv-content-hero__title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst(".ftv-content-hero__poster img")?.absUrl("src")
            genre = document.select(".ftv-content-hero__genres a").joinToString { it.text().trim() }
            description = buildString {
                document.selectFirst(".ftv-content-hero__description")?.text()?.trim()?.let { append(it) }
                val year = document.selectFirst(".ftv-content-hero__year a")?.text()?.trim()
                val extras = document.select(".ftv-content-chip--secondary").map { it.text().trim() }
                val info = listOfNotNull(year?.takeIf { it.isNotEmpty() }) + extras.filter { it.isNotEmpty() }
                if (info.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(info.joinToString(" • "))
                }
            }
            val statusText = document.selectFirst("a.ftv-content-chip--status")?.text()?.trim().orEmpty()
            status = when {
                isMovie -> SAnime.COMPLETED
                statusText.contains("منتهي") -> SAnime.COMPLETED
                statusText.contains("مستمر") -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val panels = document.select(".ftv-season-panel[data-season-panel]")
        val groups: List<Pair<String, List<org.jsoup.nodes.Element>>> = if (panels.isNotEmpty()) {
            panels.map { it.attr("data-season-panel") to it.select("a.ftv-series-episode-card").toList() }
        } else {
            listOf("" to document.select("a.ftv-series-episode-card").toList())
        }

        if (groups.all { it.second.isEmpty() }) {
            // Movie page: a single playable entry.
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "مشاهدة الفيلم"
                    episode_number = 1F
                },
            )
        }

        val multiSeason = groups.count { it.second.isNotEmpty() } > 1
        var counter = 0
        val episodes = groups.flatMap { (season, cards) ->
            cards.map { card ->
                counter++
                val label = card.selectFirst("strong")?.text()?.trim().orEmpty()
                val number = EPISODE_NUMBER_REGEX.find(label)?.value?.toFloatOrNull()
                SEpisode.create().apply {
                    setUrlWithoutDomain(card.absUrl("href"))
                    name = when {
                        multiSeason && season.isNotEmpty() ->
                            "الموسم $season - الحلقة ${number?.clean() ?: counter}"
                        label.isNotEmpty() -> label
                        else -> "الحلقة $counter"
                    }
                    // Across several seasons the real numbers collide, so use the running position.
                    episode_number = if (multiSeason) counter.toFloat() else number ?: counter.toFloat()
                }
            }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    private fun Float.clean(): String = if (this % 1F == 0F) toInt().toString() else toString()

    // ============================== Hosters ===============================
    // Servers are static in the HTML (button.ftv-watch-tab[data-server-url]); no AJAX needed.
    // Their order and set vary per page, so they are parsed by name/URL, never by position.

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val hosters = document.select("button.ftv-watch-tab[data-server-url]").mapNotNull { button ->
            val vip = button.attr("data-server-vip")
            if (vip.isNotEmpty() && vip != "0") return@mapNotNull null
            val url = button.attr("data-server-url").trim()
            if (url.isEmpty()) return@mapNotNull null
            val name = button.selectFirst("strong")?.text()?.trim().orEmpty()
                .ifEmpty { url.toHttpUrlOrNull()?.host.orEmpty() }
            val quality = button.selectFirst("small")?.text()?.trim().orEmpty()
            Hoster(hosterUrl = url, hosterName = name, internalData = quality)
        }.distinctBy { it.hosterUrl }

        if (hosters.isNotEmpty()) return hosters

        val iframeUrl = document.selectFirst("iframe.ftv-player-iframe")?.absUrl("src").orEmpty()
        val host = iframeUrl.toHttpUrlOrNull()?.host ?: return emptyList()
        return listOf(Hoster(hosterUrl = iframeUrl, hosterName = host, internalData = ""))
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedByDescending { hosterPriority(it.hosterUrl) }

    private fun hosterPriority(url: String): Int {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        return when {
            "vidmoly" in host -> 3
            "dsvplay" in host -> 2
            UNIVERSAL_HOSTS.any { it in host } -> 1
            else -> 0
        }
    }

    // ============================ Video List ==============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        return when {
            "vidmoly" in host -> VidMolyExtractor(client, docHeaders).videosFromUrl(url)

            // DSVPlay is a Doodstream mirror.
            "dsvplay" in host -> DoodExtractor(client).videosFromUrl(url, hoster.hosterName)

            // No dedicated extractor exists in lib/ for these hosts. Best-effort WebView sniffing;
            // needs verification against the live embeds.
            UNIVERSAL_HOSTS.any { it in host } ->
                UniversalExtractor(client).videosFromUrl(url, docHeaders, prefix = hoster.hosterName)

            else -> emptyList()
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("التصفية تُطبَّق مع البحث أو مع بحث فارغ"),
        GenreFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>("النوع", GENRES.map { it.first }.toTypedArray()) {
        val id get() = GENRES[state].second
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "رابط الموقع"
            summary = baseUrl
            dialogTitle = "رابط الموقع"
            dialogMessage = "الافتراضي: $DEFAULT_BASE_URL"
            setDefaultValue(DEFAULT_BASE_URL)
            setOnPreferenceChangeListener { pref, newValue ->
                val value = (newValue as String).trim().trimEnd('/')
                if (value.toHttpUrlOrNull() == null) {
                    Toast.makeText(screen.context, "رابط غير صالح", Toast.LENGTH_LONG).show()
                    false
                } else {
                    baseUrl = value
                    pref.summary = value
                    true
                }
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://ro39285yaa.5ivtv.in"
        private const val PREF_BASE_URL_KEY = "preferred_base_url"

        private val EPISODE_NUMBER_REGEX = Regex("""\d+(\.\d+)?""")

        // Hosts without a dedicated extractor in lib/ (unverified, see getVideoList).
        private val UNIVERSAL_HOSTS = listOf("71stream", "ult4vid", "morencius", "hgcloud")

        // Genre ids taken from the site's search filter form (genre[]).
        private val GENRES = listOf(
            "الكل" to "",
            "أطفال" to "177",
            "أكشن" to "25",
            "أكشن ومغامرة" to "109",
            "أنمي" to "38",
            "إثارة" to "27",
            "تاريخي" to "42",
            "تحقيق" to "84",
            "جريمة" to "16",
            "حرب" to "114",
            "حرب وسياسة" to "125",
            "حربي" to "44",
            "حواري" to "135",
            "خيال علمي" to "35",
            "خيال علمي وفانتازيا" to "108",
            "دراما" to "14",
            "رسوم متحركة" to "116",
            "رعب" to "6",
            "رومانسي" to "23",
            "عائلي" to "39",
            "غموض" to "17",
            "فانتازيا" to "34",
            "فيلم تلفزيوني" to "142",
            "كوميدي" to "32",
            "مسلسل يومي" to "173",
            "مغامرة" to "31",
            "موسيقي" to "56",
            "واقعي" to "115",
            "وثائقي" to "51",
        )
    }
}
