package eu.kanade.tachiyomi.animeextension.ar.egydead

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Egydead : ParsedAnimeHttpSource() {

    override val name = "Egydead"

    override val baseUrl = "https://tv10.egydead.live"

    override val lang = "ar"

    override val supportsLatest = true

    // Popular
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/", headers)
    }

    override fun popularAnimeSelector(): String = ".posts-list .post-item, .BlockItem, article.post"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.select("a").first() ?: element
        setUrlWithoutDomain(link.attr("href"))
        title = element.select(".title, h3, h2").text().ifEmpty { link.attr("title") }
        thumbnail_url = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
    }

    override fun popularAnimeNextPageSelector(): String = ".pagination .next, .next.page-numbers"

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // Anime details
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select(".singleTitle em, h1").text()
        description = document.select(".story, .description").text()
        thumbnail_url = document.select(".single-thumbnail img").attr("src")
    }

    // Episodes
    override fun episodeListSelector(): String = ".EpsList li a, .episodes-list a, ul.episodes li a"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        name = element.text()
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun episodeListParse(response: okhttp3.Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector())
            .map { episodeFromElement(it) }
            .distinctBy { it.url }
    }

    // Videos
    override fun videoListParse(response: okhttp3.Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        // Look for iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("twitter")) {
                videos.add(Video(src, "Server", src))
            }
        }
        // Look for data-link attributes
        document.select("[data-link]").forEach { element ->
            val link = element.attr("data-link")
            if (link.isNotEmpty()) {
                videos.add(Video(link, element.text().ifEmpty { "Server" }, link))
            }
        }
        return videos
    }

    override fun videoListSelector() = throw Exception("unused")
    override fun videoFromElement(element: Element) = throw Exception("unused")
    override fun videoUrlParse(document: Document) = throw Exception("unused")
}
