package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://shooflive.watch"
    override var name = "ShoofLive"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("article, div.match-container, div.movie, div.post, div.item, a[href*='watch'], a[href*='match']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/") return null

        val title = this.selectFirst("h1, h2, h3, h4, .title, .team-title, .match-title")?.text()?.trim()
            ?: anchor.attr("title").trim().ifEmpty { null }
            ?: this.text().trim().ifEmpty { null }
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })

        return newMovieSearchResponse(title, href, TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, div.movie, div.post, div.item, a[href*='watch']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .entry-title, .match-details-title")?.text()?.trim() ?: "بث مباشر"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description], .story, .entry-content")?.text()?.trim()

        val episodes = document.select("div.episodes-list a, ul.episodes a, a[href*='episode']").mapNotNull { ep ->
            val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
            val epName = ep.text().trim().ifEmpty { "حلقة" }
            newEpisode(epHref) {
                this.name = epName
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe[src], iframe[data-src]").forEach { frame ->
            val rawSrc = frame.attr("data-src").ifEmpty { frame.attr("src") }
            val frameUrl = fixUrlNull(rawSrc) ?: return@forEach

            loadExtractor(frameUrl, data, subtitleCallback, callback)

            runCatching {
                val frameText = app.get(frameUrl, referer = mainUrl).text
                Regex("""(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)""").findAll(frameText).forEach { match ->
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - سيرفر مباشر",
                            url = match.value.replace("\\/", "/"),
                            referer = frameUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        }
        return true
    }
}
