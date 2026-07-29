package com.wavlin.innertube.pages

import com.wavlin.innertube.models.Album
import com.wavlin.innertube.models.AlbumItem
import com.wavlin.innertube.models.Artist
import com.wavlin.innertube.models.ArtistItem
import com.wavlin.innertube.models.MusicResponsiveListItemRenderer
import com.wavlin.innertube.models.MusicTwoRowItemRenderer
import com.wavlin.innertube.models.PlaylistItem
import com.wavlin.innertube.models.SongItem
import com.wavlin.innertube.models.YTItem
import com.wavlin.innertube.models.oddElements
import com.wavlin.innertube.utils.parseTime

data class LibraryAlbumsPage(
    val albums: List<AlbumItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): AlbumItem? {
            return AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = null,
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
        }
    }
}
