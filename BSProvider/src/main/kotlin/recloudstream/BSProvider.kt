package recloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dns.DnsOverHttps
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.InetAddress

// Bootstrap client with hardcoded Cloudflare IP to avoid initial DNS issues
val bootstrapClient = OkHttpClient.Builder()
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "cloudflare-dns.com") {
                return listOf(InetAddress.getByName("104.16.248.249"))
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    })
    .build()

// Configure DNS over HTTPS with Cloudflare’s 1.1.1.1
val dns = DnsOverHttps.Builder()
    .client(bootstrapClient)
    .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
    .build()

// Custom HTTP client with DoH and SSL bypass
val customClient: OkHttpClient by lazy {
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    OkHttpClient.Builder()
        .dns(dns)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
}

class BSProvider : MainAPI() {
    data class VideoSearchResponse(val list: List<VideoItem>)
    data class VideoItem(val id: String, val title: String, @com.fasterxml.jackson.annotation.JsonProperty("thumbnail_360_url") val thumbnail_360_url: String)
    data class VideoDetailResponse(val id: String, val title: String, val description: String, @com.fasterxml.jackson.annotation.JsonProperty("thumbnail_720_url") val thumbnail_720_url: String)

    override var mainUrl = "https://bs.to"
    override var name = "BS"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "de"
    override val hasMainPage = true

    private suspend fun customGet(path: String): String {
        val hostname = "bs.to"
        val ip = dns.lookup(hostname).firstOrNull()?.hostAddress ?: "104.21.63.123" // Fallback to known working IP
        val url = "https://$ip$path"
        val request = Request.Builder()
            .url(url)
            .header("Host", hostname)
            .header("User-Agent", "curl/7.68.0") // Mimic curl
            .build()
        val response = customClient.newCall(request).execute()
        return response.body?.string() ?: throw Exception("Failed to fetch $url")
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val pageContent = customGet("/andere-serien")
        val regex = """<a href="serie/([^"]+)" title="([^"]+)">([^<]+)</a>""".toRegex()
        val matches = regex.findAll(pageContent)

        val filteredMatches = matches.filter {
            it.groupValues[3].contains(query, ignoreCase = true)
        }.toList()

        val deferredResults = filteredMatches.map { match ->
            async {
                val seriesId = match.groupValues[1]
                val seriesUrl = "$mainUrl/serie/$seriesId"
                val seriesPage = customGet("/serie/$seriesId")
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
        deferredResults.awaitAll()
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("dailymotion.com/video/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value
            ?: return null
        val response = customGet("/video/$videoId?fields=id,title,description,thumbnail_720_url")
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        return videoDetail.toLoadResponse(this)
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