//! Port of the Kotlin `kugou` module (derived from ViMusic upstream).
//! Differences from the Kotlin original: `.` in the regexes does not exclude U+0085, U+2028
//! and U+2029; the percent-encoder keeps only `A-Za-z0-9-._~` (Ktor's exact unreserved set was
//! not verified, servers decode both forms identically); JSON parsing is strict.

mod models;
mod text;

pub use models::{
    DownloadLyricsResponse, Keyword, LyricsCandidate, SearchLyricsResponse, SearchSongData,
    SearchSongResponse, SongInfo,
};
pub use text::{generate_keyword, normalize_artist, normalize_lyrics, normalize_title};

use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use base64::{alphabet, Engine};
use text::{percent_encode, search_query};

const PAGE_SIZE: u32 = 8;
const DURATION_TOLERANCE: i64 = 8;
const SONG_SEARCH_URL: &str = "https://mobileservice.kugou.com/api/v3/search/song";
const LYRICS_SEARCH_URL: &str = "https://lyrics.kugou.com/search";
const LYRICS_DOWNLOAD_URL: &str = "https://lyrics.kugou.com/download";

const BASE64: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

#[derive(Debug, thiserror::Error)]
pub enum KuGouError {
    #[error("No lyrics candidate")]
    NoLyricsCandidate,
    #[error(transparent)]
    Http(#[from] reqwest::Error),
    #[error("invalid base64 lyrics content: {0}")]
    InvalidContent(#[from] base64::DecodeError),
}

fn build_url(base: &str, pairs: &[(&str, String)]) -> String {
    let query: Vec<String> = pairs
        .iter()
        .map(|(key, value)| format!("{key}={}", percent_encode(value)))
        .collect();
    format!("{base}?{}", query.join("&"))
}

fn within_tolerance(song_duration: i32, duration: i32) -> bool {
    duration == -1 || (i64::from(song_duration) - i64::from(duration)).abs() <= DURATION_TOLERANCE
}

#[derive(Debug, Clone)]
pub struct KuGou {
    http: reqwest::Client,
    pub use_traditional_chinese: bool,
}

impl KuGou {
    pub fn new() -> Result<Self, KuGouError> {
        Ok(Self {
            http: reqwest::Client::builder().build()?,
            use_traditional_chinese: false,
        })
    }

    pub async fn get_lyrics(
        &self,
        title: &str,
        artist: &str,
        duration: i32,
        album: Option<&str>,
    ) -> Result<String, KuGouError> {
        let keyword = generate_keyword(title, artist, album);
        let candidate = self
            .get_lyrics_candidate(&keyword, duration)
            .await?
            .ok_or(KuGouError::NoLyricsCandidate)?;
        self.download_decoded(&candidate).await
    }

    pub async fn get_all_possible_lyrics_options(
        &self,
        title: &str,
        artist: &str,
        duration: i32,
        album: Option<&str>,
        mut callback: impl FnMut(String),
    ) -> Result<(), KuGouError> {
        let keyword = generate_keyword(title, artist, album);
        let songs = self.search_songs(&keyword).await?;
        for song in &songs.data.info {
            if within_tolerance(song.duration, duration) {
                let candidate = self.search_lyrics_by_hash(&song.hash).await?.candidates.into_iter().next();
                if let Some(candidate) = candidate {
                    callback(self.download_decoded(&candidate).await?);
                }
            }
        }
        for candidate in self.search_lyrics_by_keyword(&keyword, duration).await?.candidates {
            callback(self.download_decoded(&candidate).await?);
        }
        Ok(())
    }

    pub async fn get_lyrics_candidate(
        &self,
        keyword: &Keyword,
        duration: i32,
    ) -> Result<Option<LyricsCandidate>, KuGouError> {
        for song in self.search_songs(keyword).await?.data.info {
            if within_tolerance(song.duration, duration) {
                let candidate = self.search_lyrics_by_hash(&song.hash).await?.candidates.into_iter().next();
                if candidate.is_some() {
                    return Ok(candidate);
                }
            }
        }
        Ok(self.search_lyrics_by_keyword(keyword, duration).await?.candidates.into_iter().next())
    }

    pub async fn search_songs(&self, keyword: &Keyword) -> Result<SearchSongResponse, KuGouError> {
        let url = build_url(
            SONG_SEARCH_URL,
            &[
                ("version", "9108".to_string()),
                ("plat", "0".to_string()),
                ("pagesize", PAGE_SIZE.to_string()),
                ("showtype", "0".to_string()),
                ("keyword", search_query(keyword)),
            ],
        );
        self.get_json(&url).await
    }

    async fn search_lyrics_by_keyword(
        &self,
        keyword: &Keyword,
        duration: i32,
    ) -> Result<SearchLyricsResponse, KuGouError> {
        let mut pairs = vec![
            ("ver", "1".to_string()),
            ("man", "yes".to_string()),
            ("client", "pc".to_string()),
        ];
        if duration != -1 {
            pairs.push(("duration", (i64::from(duration) * 1000).to_string()));
        }
        pairs.push(("keyword", search_query(keyword)));
        self.get_json(&build_url(LYRICS_SEARCH_URL, &pairs)).await
    }

    async fn search_lyrics_by_hash(&self, hash: &str) -> Result<SearchLyricsResponse, KuGouError> {
        let url = build_url(
            LYRICS_SEARCH_URL,
            &[
                ("ver", "1".to_string()),
                ("man", "yes".to_string()),
                ("client", "pc".to_string()),
                ("hash", hash.to_string()),
            ],
        );
        self.get_json(&url).await
    }

    async fn download_lyrics(
        &self,
        id: i64,
        access_key: &str,
    ) -> Result<DownloadLyricsResponse, KuGouError> {
        let url = build_url(
            LYRICS_DOWNLOAD_URL,
            &[
                ("fmt", "lrc".to_string()),
                ("charset", "utf8".to_string()),
                ("client", "pc".to_string()),
                ("ver", "1".to_string()),
                ("id", id.to_string()),
                ("accesskey", access_key.to_string()),
            ],
        );
        self.get_json(&url).await
    }

    async fn download_decoded(&self, candidate: &LyricsCandidate) -> Result<String, KuGouError> {
        let response = self.download_lyrics(candidate.id, &candidate.accesskey).await?;
        let bytes = BASE64.decode(response.content.as_bytes())?;
        Ok(normalize_lyrics(&String::from_utf8_lossy(&bytes)))
    }

    async fn get_json<T: serde::de::DeserializeOwned>(&self, url: &str) -> Result<T, KuGouError> {
        Ok(self.http.get(url).send().await?.error_for_status()?.json::<T>().await?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_urls_with_percent_encoded_values_in_order() {
        let url = build_url("https://x/y", &[("a", "1".into()), ("keyword", "a b".into())]);
        assert_eq!(url, "https://x/y?a=1&keyword=a%20b");
    }

    #[test]
    fn unknown_duration_accepts_any_song() {
        assert!(within_tolerance(500, -1));
        assert!(within_tolerance(208, 200));
        assert!(!within_tolerance(209, 200));
    }

    #[test]
    fn base64_decoding_accepts_missing_padding() {
        assert_eq!(BASE64.decode("aGk").unwrap(), b"hi");
        assert_eq!(BASE64.decode("aGk=").unwrap(), b"hi");
        assert!(BASE64.decode("a*k").is_err());
    }

    #[test]
    fn parses_search_responses() {
        let songs: SearchSongResponse = serde_json::from_str(
            r#"{"status":1,"errcode":0,"error":"","data":{"info":[{"duration":201,"hash":"abc","extra":1}]}}"#,
        )
        .unwrap();
        assert_eq!(songs.data.info[0].hash, "abc");

        let lyrics: SearchLyricsResponse = serde_json::from_str(
            r#"{"status":200,"info":"OK","errcode":200,"errmsg":"OK","expire":86400,"candidates":[{"id":5,"product_from":"x","duration":1000,"accesskey":"k"}]}"#,
        )
        .unwrap();
        assert_eq!(lyrics.candidates[0].id, 5);
    }
}
