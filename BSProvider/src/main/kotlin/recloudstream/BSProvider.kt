package recloudstream

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    override val hasMainPage = false

    private suspend fun customGet(path: String, referer: String? = null): String {
        val hostname = "bs.to"
        val ip = dns.lookup(hostname).firstOrNull()?.hostAddress ?: "104.21.63.123"
        val url = "https://$ip$path"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Host", hostname)
            .header("User-Agent", "curl/7.68.0")
        if (referer != null) {
            requestBuilder.header("Referer", referer)
        }
        val request = requestBuilder.build()
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
                    override val apiName: String = "BS"
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

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val seriesPage = customGet(url.removePrefix(mainUrl))
        val doc = Jsoup.parse(seriesPage)

        val title = doc.selectFirst("h2")?.text()?.substringBefore("Staffel")?.trim() ?: "Unknown Title"
        val posterUrl = doc.selectFirst("img[src^=\"/public/images/cover/\"]")?.attr("src")?.let { "$mainUrl$it" }
        val description = doc.selectFirst("#sp_left p")?.text()

        val seasonLinks = doc.select(".seasons ul li a").map { it.attr("href") }

        val episodes = seasonLinks.map { seasonUrl ->
            async {
                val seasonPage = customGet("/$seasonUrl")
                val seasonDoc = Jsoup.parse(seasonPage)
                val seasonNumber = seasonUrl.split("/")[2].toIntOrNull() ?: 1

                seasonDoc.select("table.episodes tr").mapIndexed { index, ep ->
                    val epNum = ep.selectFirst("td:first-child a")?.text()?.toIntOrNull() ?: (index + 1)
                    val epTitle = ep.selectFirst("td:nth-child(2) strong")?.text() ?: "Episode $epNum"
                    val epUrl = ep.selectFirst("td:first-child a")?.attr("href")?.let { "$mainUrl$it" } ?: ""

                    Episode(
                        name = epTitle,
                        season = seasonNumber,
                        episode = epNum,
                        data = epUrl
                    )
                }
            }
        }.awaitAll().flatten()

        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = coroutineScope {
    try {
        println("Fetching episode page: $data")
        val episodePage = customGet(data.removePrefix(mainUrl))
        val episodeDoc = Jsoup.parse(episodePage)
        val hosterLinks = episodeDoc.select("td:nth-child(3) a[title=\"VOE\"]")

        hosterLinks.forEach { link ->
            val hosterUrl = link.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            println("Found VOE hoster URL: $hosterUrl")

            try {
                val hosterPage = customGet(hosterUrl.removePrefix(mainUrl), referer = data)
                val hosterDoc = Jsoup.parse(hosterPage)
                val hosterPlayer = hosterDoc.selectFirst(".hoster-player")
                val linkId = hosterPlayer?.attr("data-lid")

                if (linkId != null) {
                    println("Extracted data-lid: $linkId")
                    val ajaxUrl = "$mainUrl/ajax/embed.php"
                    val request = Request.Builder()
                        .url(ajaxUrl)
                        .header("Host", "bs.to")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", hosterUrl)
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post("LID=$linkId&ticket=".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        .build()
                    val response = customClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: throw Exception("Empty AJAX response")

                    val json = JSONObject(responseBody)
                    if (json.optBoolean("success")) {
                        val voeUrl = json.getString("link")
                        println("Found VOE URL: $voeUrl")
                        Voe().getUrl(voeUrl, referer = hosterUrl, subtitleCallback = subtitleCallback, callback = callback)
                    } else {
                        println("AJAX failed, falling back to script parsing: $responseBody")
                        val scriptContent = hosterDoc.select("script").map { it.html() }.find { it.contains("sources = {") }
                        val voeUrl = scriptContent?.let { Regex("hls': '(https://.*?)'").find(it)?.groupValues?.get(1) }
                        if (voeUrl != null) {
                            println("Extracted VOE URL from script: $voeUrl")
                            Voe().getUrl(voeUrl, referer = hosterUrl, subtitleCallback = subtitleCallback, callback = callback)
                        } else {
                            println("No VOE URL found in scripts")
                        }
                    }
                } else {
                    println("No data-lid found, skipping $hosterUrl")
                }
            } catch (e: Exception) {
                println("Error processing $hosterUrl: ${e.message}")
            }
        }
        true
    } catch (e: Exception) {
        println("Failed to process episode page $data: ${e.message}")
        false
    }
}


}