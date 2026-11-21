//! Port of the Kotlin `deezer` module: anonymous Deezer public catalog lookups used only to
//! detect new releases from artists the user listens to. Every failure maps to "nothing found".

mod models;

pub use models::{AlbumsResponse, ArtistSearchResponse, DeezerAlbum, DeezerArtist};

use chrono::{Duration, Local, NaiveDate};

pub const DEFAULT_BASE_URL: &str = "https://api.deezer.com";
const RELEASE_TYPES: [&str; 3] = ["album", "single", "ep"];

#[derive(Debug, thiserror::Error)]
pub enum DeezerError {
    #[error(transparent)]
    Http(#[from] reqwest::Error),
}

/// Keeps albums, singles and EPs released strictly after `today - since_days`.
pub fn filter_recent_releases(
    albums: Vec<DeezerAlbum>,
    today: NaiveDate,
    since_days: i64,
) -> Vec<DeezerAlbum> {
    let cutoff = today - Duration::days(since_days);
    albums
        .into_iter()
        .filter(|album| {
            let Some(release_date) = album
                .release_date
                .as_deref()
                .and_then(|value| NaiveDate::parse_from_str(value, "%Y-%m-%d").ok())
            else {
                return false;
            };
            let is_release_type = album
                .record_type
                .as_deref()
                .map_or(false, |record_type| RELEASE_TYPES.contains(&record_type));
            is_release_type && release_date > cutoff
        })
        .collect()
}

pub fn pick_artist(results: Vec<DeezerArtist>, name: &str) -> Option<DeezerArtist> {
    let wanted = name.trim().to_lowercase();
    let exact = results.iter().position(|artist| artist.name.to_lowercase() == wanted);
    match exact {
        Some(index) => results.into_iter().nth(index),
        None => results.into_iter().next(),
    }
}

#[derive(Debug, Clone)]
pub struct Deezer {
    http: reqwest::Client,
    base_url: String,
}

impl Deezer {
    pub fn new() -> Result<Self, DeezerError> {
        Self::with_base_url(DEFAULT_BASE_URL)
    }

    pub fn with_base_url(base_url: impl Into<String>) -> Result<Self, DeezerError> {
        Ok(Self {
            http: reqwest::Client::builder().build()?,
            base_url: base_url.into().trim_end_matches('/').to_string(),
        })
    }

    async fn get_json<T: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<T, reqwest::Error> {
        self.http
            .get(format!("{}{path}", self.base_url))
            .query(query)
            .send()
            .await?
            .error_for_status()?
            .json::<T>()
            .await
    }

    /// Exact case-insensitive name match if present, otherwise the top search result.
    pub async fn search_artist(&self, name: &str) -> Option<DeezerArtist> {
        let response = self
            .get_json::<ArtistSearchResponse>(
                "/search/artist",
                &[("q", name.to_string()), ("limit", "5".to_string())],
            )
            .await
            .ok()?;
        pick_artist(response.data, name)
    }

    pub async fn get_artist_albums(&self, artist_id: i64, limit: u32) -> Vec<DeezerAlbum> {
        self.get_json::<AlbumsResponse>(
            &format!("/artist/{artist_id}/albums"),
            &[("limit", limit.to_string())],
        )
        .await
        .map(|response| response.data)
        .unwrap_or_default()
    }

    /// Releases by `artist_name` from the last `since_days` days; empty on any failure.
    pub async fn get_recent_releases(&self, artist_name: &str, since_days: i64) -> Vec<DeezerAlbum> {
        let Some(artist) = self.search_artist(artist_name).await else {
            return Vec::new();
        };
        let albums = self.get_artist_albums(artist.id, 25).await;
        filter_recent_releases(albums, Local::now().date_naive(), since_days)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    fn album(id: i64, date: Option<&str>, record_type: Option<&str>) -> DeezerAlbum {
        DeezerAlbum {
            id,
            title: format!("album {id}"),
            release_date: date.map(str::to_string),
            cover_medium: None,
            record_type: record_type.map(str::to_string),
            explicit_lyrics: false,
        }
    }

    #[test]
    fn filters_by_type_and_strict_cutoff() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 21).unwrap();
        let albums = vec![
            album(1, Some("2026-11-20"), Some("single")),
            album(2, Some("2026-10-07"), Some("album")),
            album(3, Some("2026-10-08"), Some("album")),
            album(4, Some("2026-11-20"), Some("compile")),
            album(5, Some("not-a-date"), Some("album")),
            album(6, None, Some("album")),
            album(7, Some("2026-11-20"), None),
        ];
        let ids: Vec<i64> = filter_recent_releases(albums, today, 45).iter().map(|a| a.id).collect();
        assert_eq!(ids, vec![1, 3]);
    }

    #[test]
    fn picks_exact_name_before_top_result() {
        let artists = vec![
            DeezerArtist { id: 1, name: "Muse Tribute".into(), picture_medium: None, nb_album: 0 },
            DeezerArtist { id: 2, name: "MUSE".into(), picture_medium: None, nb_album: 9 },
        ];
        assert_eq!(pick_artist(artists.clone(), " muse ").unwrap().id, 2);
        assert_eq!(pick_artist(artists, "other").unwrap().id, 1);
        assert!(pick_artist(Vec::new(), "x").is_none());
    }

    #[test]
    fn tolerates_error_payloads_and_missing_fields() {
        let response: ArtistSearchResponse = serde_json::from_str(r#"{"error":{"type":"x"}}"#).unwrap();
        assert!(response.data.is_empty());
        let albums: AlbumsResponse = serde_json::from_str(r#"{"data":[{"id":1,"title":"t"}]}"#).unwrap();
        assert!(!albums.data[0].explicit_lyrics);
    }

    #[tokio::test]
    async fn network_failure_yields_empty_results() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = [0u8; 2048];
            let _ = socket.read(&mut buffer).await;
            let _ = socket
                .write_all(b"HTTP/1.1 500 X\r\ncontent-length: 0\r\nconnection: close\r\n\r\n")
                .await;
        });
        let client = Deezer::with_base_url(format!("http://{address}")).unwrap();
        assert!(client.get_recent_releases("anyone", 45).await.is_empty());
    }
}
