package org.koitharu.kotatsu.parsers.site.wpcomics.vi

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.wpcomics.WpComicsParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DOCTRUYEN3Q", "DocTruyen3Q", "vi")
internal class DocTruyen3Q(context: MangaLoaderContext) :
	WpComicsParser(context, MangaParserSource.DOCTRUYEN3Q, "doctruyen3qui12.pro", 36) {

	override val datePattern = "dd/MM/yyyy"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isTagsExclusionSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = availableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-truyen")

			if (filter.tags.isNotEmpty()) {
				append("/")
				append(filter.tags.first().key)
			}

			val params = mutableListOf<String>()

			when (order) {
				SortOrder.UPDATED -> params.add("sort=1")
				SortOrder.POPULARITY -> params.add("sort=2")
				else -> {}
			}

			filter.states.oneOrThrowIfMany()?.let { state ->
				when (state) {
					MangaState.ONGOING -> params.add("status=0")
					MangaState.FINISHED -> params.add("status=1")
					else -> params.add("status=2")
				}
			}

			if (filter.query?.isNotEmpty() == true) {
				params.add("keyword=${filter.query.urlEncoded()}")
			}

			if (page > 1) {
				params.add("page=$page")
			}

			if (params.isNotEmpty()) {
				append("?")
				append(params.joinToString("&"))
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val tags = availableTags()
		return doc.select("div.item-manga").mapNotNull { div ->
			val href = div.selectFirst("h3 a")?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val tagElements = div.select("p.info a[href*=tim-truyen]")
			val mangaTags = tagElements.mapNotNullToSet { a ->
				val key = a.attr("href").substringAfterLast('/')
				tags.find { it.key == key }
			}

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("div.image-item img")?.findImageUrl().orEmpty(),
				title = div.selectFirst("h3 a")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = mangaTags,
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val availableTags = availableTags()
		val tagElements = doc.select("li.category.row p.detail-info a[href*=tim-truyen]")
		val tags = tagElements.mapNotNullToSet { a ->
			val key = a.attr("href").substringAfterLast('/')
			availableTags.find { it.key == key }
		}

		val description = doc.selectFirst("div.summary-content p.detail-summary")?.html()
		val authorText = doc.selectFirst("li.author.row p.detail-info")?.text().orEmpty()

		val author = if (authorText.isNotEmpty() && authorText != "Đang cập nhật") {
			authorText
		} else {
			null
		}

		val altTitleText = doc.selectFirst("li.name-other.row p.detail-info")?.text()
		val altTitle = if (!altTitleText.isNullOrEmpty() && altTitleText != "Đang cập nhật") {
			altTitleText
		} else {
			null
		}

		val stateText = doc.selectFirst("li.status.row p.detail-info span.label")?.text()
		val state = when (stateText) {
			"Đang cập nhật" -> MangaState.ONGOING
			"Đã hoàn thành" -> MangaState.FINISHED
			else -> null
		}

		return manga.copy(
			authors = setOfNotNull(author),
			description = description,
			state = state,
			tags = tags,
			chapters = getChapters(doc),
			altTitles = setOfNotNull(altTitle),
		)
	}

	override suspend fun getChapters(doc: Document, reversed: Boolean): List<MangaChapter> {
		return doc.select("li.row:not([style*='display: none'])").mapChapters(reversed) { _, element ->
			val chapterLink = element.selectFirst("a.chapter") ?: return@mapChapters null
			val href = chapterLink.attrAsAbsoluteUrlOrNull("href") ?: return@mapChapters null
			val name = chapterLink.text()
			val number = chapterLink.attr("data-chapter").toFloatOrNull() ?: 0f
			val timeElement = element.select("div.style-chap").firstOrNull()
			val timeText = timeElement?.text()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = number,
				url = href,
				uploadDate = parseChapterDate(timeText),
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}
	}

	private fun parseChapterDate(dateText: String?): Long {
		if (dateText == null) return 0

		val relativeTimePattern = Regex("(\\d+)\\s*(phút|giờ|ngày|tuần) trước")
		val absoluteTimePattern = Regex("(\\d{2}-\\d{2}-\\d{4})")

		return when {
			dateText.contains("phút trước") -> {
				val match = relativeTimePattern.find(dateText)
				val minutes = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - minutes * 60 * 1000
			}

			dateText.contains("giờ trước") -> {
				val match = relativeTimePattern.find(dateText)
				val hours = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - hours * 3600 * 1000
			}

			dateText.contains("ngày trước") -> {
				val match = relativeTimePattern.find(dateText)
				val days = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - days * 86400 * 1000
			}

			dateText.contains("tuần trước") -> {
				val match = relativeTimePattern.find(dateText)
				val weeks = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - weeks * 7 * 86400 * 1000
			}

			absoluteTimePattern.matches(dateText) -> {
				val formatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
				formatter.parseSafe(dateText)
			}

			else -> 0L
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
	    val fullUrl = chapter.url.toAbsoluteUrl(domain)
	    val doc = webClient.httpGet(fullUrl).parseHtml()
	
	    var urls = doc.select("div.page-chapter img")
	        .mapNotNull { it.attrAsRelativeUrlOrNull("data-original") }
	        .filterNot { filterAdsUrls(it) }
	
	    if (urls.isEmpty()) {
	        urls = doc.select("div.page-chapter img")
	            .mapNotNull { it.attrAsRelativeUrlOrNull("src") }
	            .filterNot { filterAdsUrls(it) }
	    }
	
	    return urls.map { url ->
	        MangaPage(
	            id = generateUid(url),
	            url = url,
	            preview = null,
	            source = source,
	        )
	    }
	}

	private fun filterAdsUrls(url: String): Boolean {
	    return url.contains("sp1.jpg") ||
			url.contains("3q_fake") ||
			url.contains("sp2.jpg") ||
			url.contains("3qui5.jpg") ||
			url.contains("3qui6.jpg") ||
			url.contains("3qui8.jpg") ||
			url.contains("3qui9.jpg") ||
			url.contains("3q_top") ||
			url.contains("3q282.jpg") ||
			url.contains("3qui5_banner.jpg") ||
			url.contains("dt3qui8.jpg") ||
			url.contains("3qui10.jpg") ||
			url.contains("3qui12.jpg")
	}

	private fun availableTags(): Set<MangaTag> = setOf(
		MangaTag("Action", "action", source),
		MangaTag("Adult", "truong-thanh", source),
		MangaTag("Adventure", "phieu-luu", source),
		MangaTag("Anime", "anime", source),
		MangaTag("Chuyển Sinh", "chuyen-sinh", source),
		MangaTag("Comedy", "comedy", source),
		MangaTag("Comic", "comic", source),
		MangaTag("Cooking", "nau-an", source),
		MangaTag("Cổ Đại", "co-dai", source),
		MangaTag("Doujinshi", "doujinshi", source),
		MangaTag("Drama", "drama", source),
		MangaTag("Đam Mỹ", "dam-my", source),
		MangaTag("Ecchi", "ecchi", source),
		MangaTag("Fantasy", "fantasy", source),
		MangaTag("Gender Bender", "gender-bender", source),
		MangaTag("Harem", "harem", source),
		MangaTag("Historical", "historical", source),
		MangaTag("Horror", "horror", source),
		MangaTag("Hệ Thống", "he-thong", source),
		MangaTag("Josei", "josei", source),
		MangaTag("Live action", "live-action", source),
		MangaTag("Manga", "manga", source),
		MangaTag("Manhua", "manhua", source),
		MangaTag("Manhwa", "manhwa", source),
		MangaTag("Martial Arts", "martial-arts", source),
		MangaTag("Mature", "mature", source),
		MangaTag("Mecha", "mecha", source),
		MangaTag("Mystery", "mystery", source),
		MangaTag("Ngôn Tình", "ngon-tinh", source),
		MangaTag("NTR", "ntr", source),
		MangaTag("One shot", "one-shot", source),
		MangaTag("Psychological", "psychological", source),
		MangaTag("Romance", "romance", source),
		MangaTag("School Life", "school-life", source),
		MangaTag("Sci-fi", "sci-fi", source),
		MangaTag("Seinen", "seinen", source),
		MangaTag("Shoujo", "shoujo", source),
		MangaTag("Shoujo Ai", "shoujo-ai", source),
		MangaTag("Shounen", "shounen", source),
		MangaTag("Slice of Life", "slice-of-life", source),
		MangaTag("Smut", "smut", source),
		MangaTag("Soft Yaoi", "soft-yaoi", source),
		MangaTag("Soft Yuri", "soft-yuri", source),
		MangaTag("Sports", "sports", source),
		MangaTag("Supernatural", "supernatural", source),
		MangaTag("Thiếu Nhi", "thieu-nhi", source),
		MangaTag("Tragedy", "tragedy", source),
		MangaTag("Trinh Thám", "trinh-tham", source),
		MangaTag("Truyện Màu", "truyen-mau", source),
		MangaTag("Webtoon", "webtoon", source),
		MangaTag("Xuyên Không", "xuyen-khong", source),
		MangaTag("Yaoi", "yaoi", source),
		MangaTag("Yuri", "yuri", source),
		MangaTag("16+", "16", source),
		MangaTag("18+", "18", source),
		MangaTag("ABO", "abo", source),
		MangaTag("BoyLove", "boylove", source),
		MangaTag("Girl Love", "girl-love", source),
		MangaTag("Người Thú", "nguoi-thu", source),
	)
}
