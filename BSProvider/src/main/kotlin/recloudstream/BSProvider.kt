package recloudstream

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import java.net.InetAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    override var mainUrl = "https://bs.to"
    override var name = "BS"
    override val supportedTypes = setOf(TvType.TvSeries)
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
        val doc = Jsoup.parse(pageContent)
        val seriesLinks = doc.select("a[href^=\"serie/\"]")
        val filteredLinks = seriesLinks.filter { it.text().contains(query, ignoreCase = true) }

        val deferredResults = filteredLinks.map { link ->
            async {
                val seriesId = link.attr("href").removePrefix("serie/")
                val seriesUrl = "$mainUrl/serie/$seriesId"
                val seriesPage = customGet("/serie/$seriesId")
                val seriesDoc = Jsoup.parse(seriesPage)
                val posterImg = seriesDoc.selectFirst("img[src^=\"/public/images/cover/\"]")
                val posterUrl = posterImg?.attr("src")?.let { "$mainUrl$it" }

                object : SearchResponse {
                    override val name: String = link.text()
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
        val seriesPage = customGet(url.replace(mainUrl, ""))
        val doc = Jsoup.parse(seriesPage)
        val title = doc.selectFirst("h1")?.text() ?: return null
        val posterImg = doc.selectFirst("img[src^=\"/public/images/cover/\"]")
        val posterUrl = posterImg?.attr("src")?.let { mainUrl + it }

        val episodes = mutableListOf<Episode>()
        val seasons = doc.select("div#seasons > ul")
        seasons.forEachIndexed { seasonIndex, seasonUl ->
            val seasonNumber = seasonIndex + 1
            val episodeLis = seasonUl.select("li")
            episodeLis.forEachIndexed { episodeIndex, episodeLi ->
                val episodeA = episodeLi.selectFirst("a")
                val episodeUrl = mainUrl + episodeA.attr("href")
                val episodeTitle = episodeA.text()
                episodes.add(
                    Episode(
                        data = episodeUrl,
                        name = episodeTitle,
                        season = seasonNumber,
                        episode = episodeIndex + 1
                    )
                )
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = customGet(data.replace(mainUrl, ""))
        val doc = Jsoup.parse(episodePage)
        val iframe = doc.selectFirst("iframe[src*=\"dailymotion.com\"]")
        val iframeSrc = iframe?.attr("src")
        if (iframeSrc != null) {
            loadExtractor(iframeSrc, subtitleCallback, callback)
            return true
        }
        return false
    }
}