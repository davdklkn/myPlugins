// use an integer for version numbers
version = 2


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Watch Content from Burning Series"
    authors = listOf("davd")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("TvSeries")

    language = "de"
    iconUrl = "https://www.google.com/s2/favicons?domain=www.burning-series.io&sz=%size%"
}
