package com.faithscroll.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** All videos of one YouTube channel, newest first, a page at a time (so you can keep scrolling). */
class ChannelVideos(val channelId: String) {
    var name: String = ""; private set
    var avatar: String? = null; private set
    var subscribers: Long = -1; private set
    private var tab: ListLinkHandler? = null
    private var next: Page? = null
    var done = false; private set

    private fun toVideos(items: List<Any>): List<Video> = items.filterIsInstance<StreamInfoItem>().mapNotNull { i ->
        val id = Regex("(?:v=|shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(i.url)?.groupValues?.get(1) ?: return@mapNotNull null
        Video(id, i.name.orEmpty(), name.ifBlank { i.uploaderName.orEmpty() }, if (i.duration in 1..90) Video.KIND_SHORT else Video.KIND_STORY)
    }

    /** First page (and the channel's name/picture). Falls back to the channel's RSS feed (latest 15). */
    suspend fun first(): List<Video> = withContext(Dispatchers.IO) {
        runCatching {
            StreamResolver.ensureInit()
            val info = ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/$channelId")
            name = info.name.orEmpty()
            avatar = info.avatars.maxByOrNull { it.height }?.url
            subscribers = info.subscriberCount
            val t = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) } ?: info.tabs.first()
            tab = t
            val ti = ChannelTabInfo.getInfo(ServiceList.YouTube, t)
            next = ti.nextPage; done = next == null
            toVideos(ti.relatedItems)
        }.onFailure { Diag.err("channel", "$channelId", it) }.getOrNull() ?: rss()
    }

    /** The next page, or empty when there are no more. */
    suspend fun more(): List<Video> = withContext(Dispatchers.IO) {
        val t = tab; val p = next
        if (t == null || p == null) { done = true; return@withContext emptyList() }
        runCatching {
            val page = ChannelTabInfo.getMoreItems(ServiceList.YouTube, t, p)
            next = page.nextPage; done = next == null
            toVideos(page.items)
        }.onFailure { Diag.err("channel", "$channelId more", it); done = true }.getOrDefault(emptyList())
    }

    private fun rss(): List<Video> {
        done = true
        val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") ?: return emptyList()
        if (name.isBlank()) name = Regex("<title>([^<]*)</title>").find(xml)?.groupValues?.get(1).orEmpty()
        return Regex("<yt:videoId>([A-Za-z0-9_-]{11})</yt:videoId>[\\s\\S]*?<title>([^<]*)</title>").findAll(xml)
            .map { Video(it.groupValues[1], android.text.Html.fromHtml(it.groupValues[2], 0).toString(), name) }.toList()
    }
}
