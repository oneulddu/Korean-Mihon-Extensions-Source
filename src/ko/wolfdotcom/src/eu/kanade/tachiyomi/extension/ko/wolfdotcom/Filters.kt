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
    default: Int = 0,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
    default,
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

class DayFilter :
    QueryFilter(
        "연재 요일",
        "t1",
        arrayOf(
            "전체" to "",
            "월" to "1",
            "화" to "2",
            "수" to "3",
            "목" to "4",
            "금" to "5",
            "토" to "6",
            "일" to "7",
            "10일" to "10",
        ),
    )

class WebtoonGenreFilter :
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

class ComicGenreFilter :
    QueryFilter(
        "장르",
        "t3",
        arrayOf(
            "전체" to "",
            "액션" to "액션",
            "판타지" to "판타지",
            "로맨스" to "로맨스",
            "드라마" to "드라마",
            "이세계" to "이세계",
            "전생" to "전생",
            "무협" to "무협",
            "일상" to "일상",
            "일상+치유" to "일상+치유",
            "순정" to "순정",
            "러브코미디" to "러브코미디",
            "개그" to "개그",
            "학원" to "학원",
            "스포츠" to "스포츠",
            "미스터리" to "미스터리",
            "추리" to "추리",
            "스릴러" to "스릴러",
            "공포" to "공포",
            "호러" to "호러",
            "도박" to "도박",
            "역사" to "역사",
            "시대" to "시대",
            "게임" to "게임",
            "SF" to "sf",
            "요리" to "요리",
            "먹방" to "먹방",
            "음악" to "음악",
            "라노벨" to "라노벨",
            "애니화" to "애니화",
            "BL" to "bl",
            "백합" to "백합",
            "성인" to "성인",
            "붕탁" to "붕탁",
            "TS" to "ts",
            "여장" to "여장",
            "17" to "17",
        ),
    )

class SortFilter(
    default: Int = 0,
    options: Array<Pair<String, String>> = LATEST_POPULAR_SORT_OPTIONS,
) : QueryFilter("정렬 기준", "o", options, default)

fun webtoonOngoingFilters(): FilterList = FilterList(
    SortFilter(options = LATEST_NEW_POPULAR_SORT_OPTIONS),
    TypeFilter(),
    DayFilter(),
    WebtoonGenreFilter(),
)

fun webtoonCompletedFilters(): FilterList = FilterList(
    SortFilter(),
    TypeFilter(),
    WebtoonGenreFilter(),
)

fun comicFilters(): FilterList = FilterList(
    SortFilter(),
    ComicGenreFilter(),
)

private val LATEST_POPULAR_SORT_OPTIONS = arrayOf(
    "최신순" to "n",
    "인기순" to "f",
)

private val LATEST_NEW_POPULAR_SORT_OPTIONS = arrayOf(
    "최신순" to "n",
    "신작순" to "r",
    "인기순" to "f",
)
