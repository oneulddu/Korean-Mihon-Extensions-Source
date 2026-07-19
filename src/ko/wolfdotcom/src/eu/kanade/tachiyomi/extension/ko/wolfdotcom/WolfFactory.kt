package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import eu.kanade.tachiyomi.source.SourceFactory

class WolfFactory : SourceFactory {
    override fun createSources() = listOf(
        Wolf("웹툰 연재", "ing", "list", "view", ::webtoonOngoingFilters),
        Wolf("웹툰 완결", "end", "list", "view", ::webtoonCompletedFilters),
        Wolf("만화책", "cm", "cl", "cv", ::comicFilters),
    )
}
