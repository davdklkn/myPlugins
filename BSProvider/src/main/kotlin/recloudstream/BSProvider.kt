package com.example

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse

class BSProvider(val plugin: TestPlugin) : MainAPI() { // all providers must be an intstance of MainAPI
    override var mainUrl = "https://burning-series.io/" 
    override var name = "BS provider"
    override val supportedTypes = setOf(TvType.TvSeries)

    override var lang = "de"

    // enable this when your provider has a main page
    override val hasMainPage = true

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf<SearchResponse>()
    }
}