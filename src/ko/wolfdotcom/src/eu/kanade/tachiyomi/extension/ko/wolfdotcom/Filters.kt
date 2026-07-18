package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl

interface UrlPartFilter {
    fun addToUrl(url: HttpUrl.Builder)
}

abstract class QueryFilter(
    name: String,
    private val parameter: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
),
    UrlPartFilter {
    override fun addToUrl(url: HttpUrl.Builder) {
        options[state].second.takeIf { it.isNotEmpty() }?.let {
            url.addQueryParameter(parameter, it)
        }
    }
}

class TypeFilter :
    QueryFilter(
        "분류",
        "t2",
        arrayOf(
            "전체" to "",
            "일반" to "1",
            "BL" to "2",
            "성인" to "3",
        ),
    )

class GenreFilter :
    QueryFilter(
        "장르",
        "t3",
        arrayOf(
            "전체" to "",
            "드라마" to "드라마",
            "판타지" to "판타지",
            "액션" to "액션",
            "로맨스" to "로맨스",
            "일상" to "일상",
            "개그" to "개그",
            "미스터리" to "미스터리",
            "순정" to "순정",
            "스포츠" to "스포츠",
            "스릴러" to "스릴러",
            "무협" to "무협",
            "학원" to "학원",
            "공포" to "공포",
            "스토리" to "스토리",
        ),
    )

class SortFilter(default: Int = 0) :
    Filter.Select<String>(
        "정렬 기준",
        options.map { it.first }.toTypedArray(),
        default,
    ),
    UrlPartFilter {

    override fun addToUrl(url: HttpUrl.Builder) {
        url.addQueryParameter("o", options[state].second)
    }

    companion object {
        private val options = listOf(
            "최신순" to "n",
            "인기순" to "f",
        )
    }
}

val POPULAR = FilterList(SortFilter(1))
val LATEST = FilterList(SortFilter(0))
