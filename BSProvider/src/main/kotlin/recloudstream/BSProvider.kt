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
        // Fetch the HTML content of the page with all series
        val url = "$mainUrl/andere-serien"
        val response = app.get(url).text
        
        // Parse the HTML using Jsoup
        val document = Jsoup.parse(response)
        val seriesList = document.select("li > a[href^=serie]")

        // Filter and match the series based on the search query
        val filteredSeries = seriesList.filter {
            it.text().contains(query, ignoreCase = true)
        }

        // Map the filtered series to the SearchResponse format
        return filteredSeries.map {
            val seriesTitle = it.text()
            val seriesUrl = "$mainUrl${it.attr("href")}"
            SearchResponse(
                title = seriesTitle,
                url = seriesUrl,
                type = TvType.Movie // We assume "Movie" as the default type
            ) {
                // Placeholder for posterUrl since bs.to doesn't provide thumbnails in the search
                this.posterUrl = "https://via.placeholder.com/150" 
            }
        }
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