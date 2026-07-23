package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class SeriesItem(
    @SerialName("x")
    val id: String,
    @SerialName("t")
    val name: String,
    @SerialName("p")
    private val poster: String = "",
    @SerialName("au")
    val author: String = "",
    @SerialName("g")
    val updatedAt: Long = 0,
    @SerialName("tag")
    private val tagIds: String = "",
    @SerialName("c")
    private val platformId: String = "-1",
    @SerialName("pd")
    private val publishDayId: String = "-1",
    @SerialName("h")
    val hot: Int = 0,
) {
    val tag get() = tagIds.split(",")
        .filter(String::isNotBlank)
        .map(String::toInt)

    val platform get() = platformId.toInt()

    val publishDay get() = publishDayId.toInt()

    var listIndex = -1

    fun toSManga(cdnUrl: String) = SManga.create().apply {
        url = id
        title = name
        thumbnail_url = poster.takeIf { it.isNotBlank() }?.let {
            cdnUrl + it
        }
        genre = buildList {
            add(platformsMap[platform])
            add(publishDayMap[publishDay])
            tag.forEach {
                add(tagsMap[it])
            }
        }.filterNotNull().joinToString()
        author = this@SeriesItem.author
        status = when (listIndex) {
            0 -> SManga.COMPLETED
            1 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class Chapter(
    @SerialName("id")
    val id: String,
    @SerialName("t")
    val title: String,
    @SerialName("d")
    val date: String = "",
    @SerialName("u")
    private val url: String = "",
) {
    fun toSChapter(mangaId: String) = SChapter.create().apply {
        url = this@Chapter.url
            .removePrefix("/webtoons/")
            .removeSuffix(".html")
            .ifBlank { "$mangaId/$id" }
        name = title
        date_upload = try {
            dateFormat.parse(date)!!.time
        } catch (_: ParseException) {
            0L
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
