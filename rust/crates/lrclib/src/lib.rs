//! Port of the Kotlin `lrclib` module. Behavioural differences from the Kotlin original:
//! - `.` in the title-cleanup regexes does not exclude `\r`, U+0085, U+2028 and U+2029, and `$` does
//!   not match before a trailing line terminator (Java semantics).
//! - JSON parsing is strict (kotlinx `isLenient` has no serde_json equivalent).
//! - Digit parsing in `Lyrics::sentences` accepts ASCII digits only.
//! - Cancellation is by dropping the future, replacing `ensureActive`.

mod lyrics;
mod text;
mod track;

pub use lyrics::Lyrics;
pub use text::{clean_artist, clean_title, levenshtein_distance, max_similarity, string_similarity};
pub use track::{
    best_matching_for, best_matching_for_duration, best_matching_for_relaxed, Track,
};

use std::cmp::Ordering;

pub const DEFAULT_BASE_URL: &str = "https://lrclib.net";

#[derive(Debug, thiserror::Error)]
pub enum LrcLibError {
    #[error("Lyrics unavailable")]
    LyricsUnavailable,
    #[error(transparent)]
    Http(#[from] reqwest::Error),
}

#[derive(Debug, Default)]
struct SearchParams<'a> {
    query: Option<&'a str>,
    track_name: Option<&'a str>,
    artist_name: Option<&'a str>,
    album_name: Option<&'a str>,
}

#[derive(Debug, Clone)]
pub struct LrcLib {
    http: reqwest::Client,
    base_url: String,
}

impl LrcLib {
    pub fn new() -> Result<Self, LrcLibError> {
        Self::with_base_url(DEFAULT_BASE_URL)
    }

    pub fn with_base_url(base_url: impl Into<String>) -> Result<Self, LrcLibError> {
        Ok(Self {
            http: reqwest::Client::builder().build()?,
            base_url: base_url.into().trim_end_matches('/').to_string(),
        })
    }

    async fn search_once(&self, params: SearchParams<'_>) -> Result<Vec<Track>, reqwest::Error> {
        let mut pairs: Vec<(&str, &str)> = Vec::new();
        if let Some(value) = params.query {
            pairs.push(("q", value));
        }
        if let Some(value) = params.track_name {
            pairs.push(("track_name", value));
        }
        if let Some(value) = params.artist_name {
            pairs.push(("artist_name", value));
        }
        if let Some(value) = params.album_name {
            pairs.push(("album_name", value));
        }

        self.http
            .get(format!("{}/api/search", self.base_url))
            .query(&pairs)
            .send()
            .await?
            .error_for_status()?
            .json::<Vec<Track>>()
            .await
    }

    async fn search_with_lyrics(&self, params: SearchParams<'_>) -> Vec<Track> {
        self.search_once(params)
            .await
            .unwrap_or_default()
            .into_iter()
            .filter(Track::has_lyrics)
            .collect()
    }

    async fn query_lyrics(&self, artist: &str, title: &str, album: Option<&str>) -> Vec<Track> {
        let cleaned_title = clean_title(title);
        let cleaned_artist = clean_artist(artist);

        let results = self
            .search_with_lyrics(SearchParams {
                track_name: Some(&cleaned_title),
                artist_name: Some(&cleaned_artist),
                album_name: album,
                ..Default::default()
            })
            .await;
        if !results.is_empty() {
            return results;
        }

        let results = self
            .search_with_lyrics(SearchParams {
                track_name: Some(&cleaned_title),
                ..Default::default()
            })
            .await;
        if !results.is_empty() {
            return results;
        }

        let combined = format!("{cleaned_artist} {cleaned_title}");
        let results = self
            .search_with_lyrics(SearchParams {
                query: Some(&combined),
                ..Default::default()
            })
            .await;
        if !results.is_empty() {
            return results;
        }

        let results = self
            .search_with_lyrics(SearchParams {
                query: Some(&cleaned_title),
                ..Default::default()
            })
            .await;
        if !results.is_empty() {
            return results;
        }

        if cleaned_title != title.trim() {
            return self
                .search_with_lyrics(SearchParams {
                    track_name: Some(title.trim()),
                    artist_name: Some(artist.trim()),
                    ..Default::default()
                })
                .await;
        }
        results
    }

    /// Returns the best lyrics text (synced preferred). `duration` is in seconds, `-1` for unknown.
    pub async fn get_lyrics(
        &self,
        title: &str,
        artist: &str,
        duration: i32,
        album: Option<&str>,
    ) -> Result<String, LrcLibError> {
        let tracks = self.query_lyrics(artist, title, album).await;
        let cleaned_title = clean_title(title);
        let cleaned_artist = clean_artist(artist);

        let best = if duration == -1 {
            best_matching_for(&tracks, duration, Some(&cleaned_title), Some(&cleaned_artist))
        } else {
            best_matching_for_relaxed(&tracks, duration)
        };

        best.and_then(|track| track.synced_lyrics.as_ref().or(track.plain_lyrics.as_ref()))
            .cloned()
            .map(|text| Lyrics::new(text).text)
            .ok_or(LrcLibError::LyricsUnavailable)
    }

    /// Calls `callback` with up to five lyrics variants, best candidates first.
    pub async fn get_all_lyrics(
        &self,
        title: &str,
        artist: &str,
        duration: i32,
        album: Option<&str>,
        mut callback: impl FnMut(String),
    ) {
        let tracks = self.query_lyrics(artist, title, album).await;
        let cleaned_title = clean_title(title);
        let cleaned_artist = clean_artist(artist);

        let sorted: Vec<&Track> = if duration == -1 {
            let mut scored: Vec<(f64, &Track)> = tracks
                .iter()
                .map(|track| {
                    let mut score = if track.synced_lyrics.is_some() { 1.0 } else { 0.0 };
                    let title_similarity = string_similarity(&cleaned_title, &track.track_name);
                    let artist_similarity = string_similarity(&cleaned_artist, &track.artist_name);
                    score += (title_similarity + artist_similarity) / 2.0;
                    (score, track)
                })
                .collect();
            scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(Ordering::Equal));
            scored.into_iter().map(|(_, track)| track).collect()
        } else {
            let mut by_distance: Vec<&Track> = tracks.iter().collect();
            by_distance.sort_by_key(|track| track.duration_difference(duration));
            by_distance
        };

        let mut count = 0;
        let mut plain = 0;
        for track in sorted {
            if count > 4 {
                continue;
            }
            if let (Some(synced), true) = (&track.synced_lyrics, duration == -1) {
                count += 1;
                callback(synced.clone());
                continue;
            }
            let within_tolerance = track.duration_difference(duration) <= 5;
            if let (Some(synced), true) = (&track.synced_lyrics, within_tolerance) {
                count += 1;
                callback(synced.clone());
            }
            if let (Some(plain_text), true, 0) = (&track.plain_lyrics, within_tolerance, plain) {
                count += 1;
                plain += 1;
                callback(plain_text.clone());
            }
        }
    }

    /// All candidate tracks that carry lyrics for the cleaned `artist` / `title`.
    pub async fn lyrics(&self, artist: &str, title: &str) -> Vec<Track> {
        self.query_lyrics(artist, title, None).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    async fn serve(responses: Vec<(u16, String)>) -> (String, Arc<Mutex<Vec<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = Arc::clone(&requests);
        tokio::spawn(async move {
            for (status, body) in responses {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut buffer = vec![0u8; 8192];
                let read = socket.read(&mut buffer).await.unwrap();
                let request = String::from_utf8_lossy(&buffer[..read]).to_string();
                seen.lock().unwrap().push(request.lines().next().unwrap_or_default().to_string());
                let response = format!(
                    "HTTP/1.1 {status} X\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                    body.len()
                );
                socket.write_all(response.as_bytes()).await.unwrap();
            }
        });
        (format!("http://{address}"), requests)
    }

    #[tokio::test]
    async fn falls_through_search_strategies_until_lyrics_are_found() {
        let found = r#"[{"id":1,"trackName":"Song","artistName":"Band","duration":200.0,"plainLyrics":null,"syncedLyrics":"[00:01.00] hi"}]"#;
        let (base, requests) = serve(vec![
            (200, "[]".to_string()),
            (500, "boom".to_string()),
            (200, found.to_string()),
        ])
        .await;
        let client = LrcLib::with_base_url(base).unwrap();

        let lyrics = client
            .get_lyrics("Song (Official Video)", "Band & Friend", 201, None)
            .await
            .unwrap();
        assert_eq!(lyrics, "[00:01.00] hi");

        let requests = requests.lock().unwrap();
        assert!(requests[0].contains("track_name=Song") && requests[0].contains("artist_name=Band"));
        assert!(!requests[0].contains("Official"));
        assert!(requests[1].contains("track_name=Song") && !requests[1].contains("artist_name"));
        assert!(requests[2].contains("q=Band+Song") || requests[2].contains("q=Band%20Song"));
    }

    #[tokio::test]
    async fn unavailable_when_nothing_matches() {
        let (base, _) = serve(vec![(200, "[]".to_string()); 4]).await;
        let client = LrcLib::with_base_url(base).unwrap();
        let error = client.get_lyrics("Song", "Band", -1, None).await.unwrap_err();
        assert!(matches!(error, LrcLibError::LyricsUnavailable));
    }

    #[tokio::test]
    async fn get_all_lyrics_orders_by_duration_and_limits_plain_to_one() {
        let body = r#"[
            {"id":1,"trackName":"S","artistName":"B","duration":210.0,"plainLyrics":"plain far","syncedLyrics":null},
            {"id":2,"trackName":"S","artistName":"B","duration":200.0,"plainLyrics":"plain near","syncedLyrics":"synced near"},
            {"id":3,"trackName":"S","artistName":"B","duration":202.0,"plainLyrics":"plain mid","syncedLyrics":null}
        ]"#;
        let (base, _) = serve(vec![(200, body.to_string())]).await;
        let client = LrcLib::with_base_url(base).unwrap();
        let mut collected = Vec::new();
        client.get_all_lyrics("S", "B", 200, None, |text| collected.push(text)).await;
        assert_eq!(collected, vec!["synced near", "plain near"]);
    }
}
