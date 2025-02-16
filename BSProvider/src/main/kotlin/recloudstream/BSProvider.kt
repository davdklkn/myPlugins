package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor

//mine
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BSProvider : MainAPI() {

    data class VideoSearchResponse(
        val list: List<VideoItem>
    )

    data class VideoItem(
        val id: String,
        val title: String,
        @Suppress("PropertyName")
        val thumbnail_360_url: String
    )

    data class VideoDetailResponse(
        val id: String,
        val title: String,
        val description: String,
        @Suppress("PropertyName")
        val thumbnail_720_url: String
    )

    override var mainUrl = "https://bs.to"
    override var name = "BS"
    override val supportedTypes = setOf(TvType.Others)

    override var lang = "de"

    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=26").text
        val popular = tryParseJson<VideoSearchResponse>(response)?.list ?: emptyList()

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Popular",
                    popular.map { it.toSearchResponse(this) },
                    true
                ),
            ),
            false
        )
    }

    // override suspend fun search(query: String): List<SearchResponse> {
    //     val response = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=10&search=${query.encodeUri()}").text
    //     val searchResults = tryParseJson<VideoSearchResponse>(response)?.list ?: return emptyList()
    //     return searchResults.map { it.toSearchResponse(this) }
    // }
    // New search function for BS.to
override suspend fun search(query: String): List<SearchResponse> {
        // Step 1: Encode the search query and construct the search URL
        val searchUrl = "$mainUrl/suche?q=${query.encodeUri()}"

        // Step 2: Fetch the HTML content of the search results page
        val response = app.get(searchUrl).text

        // Step 3: Parse the HTML content using Jsoup
        val document: Document = Jsoup.parse(response)

        // Step 4: Extract the series from the search results
        val searchResults = mutableListOf<SearchResponse>()
        
        // Look for all <li> elements with a <a> tag (these contain the series links)
        for (element: Element in document.select("li > a")) {
            val title = element.attr("title")
            val seriesUrl = element.attr("href")

            // Map each series to the SearchResponse format
            searchResults.add(
                SearchResponse(
                    title = title,
                    url = "$mainUrl$seriesUrl",
                    type = TvType.Movie // Assuming all results are movies for now, can be changed based on actual type.
                ).apply {
                    this.posterUrl = "https://bs.to/favicon.ico" // Placeholder poster URL. You may want to scrape the actual poster if available
                }
            )
        }

        // Step 5: Return the list of search results
        return searchResults
    }
    

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("dailymotion.com/video/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value
        val response = app.get("$mainUrl/video/$videoId?fields=id,title,description,thumbnail_720_url").text
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        return videoDetail.toLoadResponse(this)
    }

    private fun VideoItem.toSearchResponse(provider: BSProvider): SearchResponse {
        return provider.newMovieSearchResponse(
            this.title,
            "https://www.dailymotion.com/video/${this.id}",
            TvType.Movie
        ) {
            this.posterUrl = thumbnail_360_url
        }
    }

    private suspend fun VideoDetailResponse.toLoadResponse(provider: BSProvider): LoadResponse {
        return provider.newMovieLoadResponse(
            this.title,
            "https://www.dailymotion.com/video/${this.id}",
            TvType.Movie,
            this.id
        ) {
            plot = description
            posterUrl = thumbnail_720_url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(
            "https://www.dailymotion.com/embed/video/$data",
            subtitleCallback,
            callback
        )
        return true
    }
}