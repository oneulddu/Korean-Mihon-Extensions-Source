package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import eu.kanade.tachiyomi.source.SourceFactory

class WolfFactory : SourceFactory {
    override fun createSources() = listOf(
        Wolf("웹툰", "ing", "list", "view"), // webtoon
        Wolf("만화책", "cm", "cl", "cv"), // comic book
        Wolf("포토툰", "pt", "list", "view"),
    )
}
