package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
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

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


// Custom DNS resolver using 1.1.1.1
object CustomDNS : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val dns = java.net.InetAddress.getAllByName(hostname).toList()
            return dns.ifEmpty { listOf(InetAddress.getByName("1.1.1.1")) }
        } catch (e: Exception) {
            return Dns.SYSTEM.lookup(hostname)
        }
    }
}

// Custom HTTP client with bypassed SSL verification and custom DNS
val customClient: OkHttpClient by lazy {
    // Create a trust manager that does not validate certificates (insecure)
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: java.security.cert.X509Certificate?, authType: String?) {}
        override fun checkServerTrusted(chain: java.security.cert.X509Certificate?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    // Install the all-trusting trust manager
    val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    OkHttpClient.Builder()
        .dns(CustomDNS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true } // Ignore hostname mismatch
        .build()
}


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
    

    
// Resolve IP manually and use it with Host header
    private suspend fun customGet(path: String): String {
        val hostname = "bs.to"
        val ip = CustomDNS.lookup(hostname).firstOrNull()?.hostAddress
            ?: throw Exception("Failed to resolve $hostname")
        val url = "https://$ip$path" // Use IP instead of domain
        val request = Request.Builder()
            .url(url)
            .header("Host", hostname) // Set Host header to bs.to
            .build()
        val response = customClient.newCall(request).execute()
        return response.body?.string() ?: throw Exception("Failed to fetch $url")
    }



    // override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    //     val response = customGet("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=26")
    //     val popular = tryParseJson<VideoSearchResponse>(response)?.list ?: emptyList()

    //     return newHomePageResponse(
    //         listOf(
    //             HomePageList(
    //                 "Popular",
    //                 popular.map { it.toSearchResponse(this) },
    //                 true
    //             ),
    //         ),
    //         false
    //     )
    // }

    // override suspend fun search(query: String): List<SearchResponse> {
    //     val response = customGet("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=10&search=${query.encodeUri()}")
    //     val searchResults = tryParseJson<VideoSearchResponse>(response)?.list ?: return emptyList()
    //     return searchResults.map { it.toSearchResponse(this) }
    // }
    // New search function for BS.to
override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
    // Request the page that lists available series
    val pageContent = customGet("$mainUrl/andere-serien")
    
    // Regex to match the series list entries
    val regex = """<a href="serie/([^"]+)" title="([^"]+)">([^<]+)</a>""".toRegex()
    val matches = regex.findAll(pageContent)

    // Filter matches first
    val filteredMatches = matches.filter {
        it.groupValues[3].contains(query, ignoreCase = true)
    }.toList()

    // Process requests in parallel
    val deferredResults = filteredMatches.map { match ->
        async {
            val seriesId = match.groupValues[1]
            val seriesUrl = "$mainUrl/serie/$seriesId"
            
            // Fetch the series page to get the poster URL
            val seriesPage = customGet(seriesUrl)
            val posterRegex = """<img src="(/public/images/cover/\d+\.jpg)" """.toRegex()
            val posterMatch = posterRegex.find(seriesPage)
            val posterUrl = posterMatch?.groupValues?.get(1)?.let { "$mainUrl$it" }

            object : SearchResponse {
                override val name: String = match.groupValues[3]
                override val url: String = seriesUrl
                override val apiName: String = "myBS"
                override var type: TvType? = TvType.TvSeries
                override var posterUrl: String? = posterUrl
                override var posterHeaders: Map<String, String>? = null
                override var id: Int? = null
                override var quality: SearchQuality? = null
            }
        }
    }

    // Wait for all results and return as list
    deferredResults.awaitAll()
}

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("dailymotion.com/video/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value
        val response = customGet("$mainUrl/video/$videoId?fields=id,title,description,thumbnail_720_url")
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        return videoDetail.toLoadResponse(this)
    }

    // private fun VideoItem.toSearchResponse(provider: BSProvider): SearchResponse {
    //     return provider.newMovieSearchResponse(
    //         this.title,
    //         "https://www.dailymotion.com/video/${this.id}",
    //         TvType.Movie
    //     ) {
    //         this.posterUrl = thumbnail_360_url
    //     }
    // }

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