use serde::Deserialize;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Keyword {
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DownloadLyricsResponse {
    pub content: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SearchLyricsResponse {
    pub status: i32,
    pub info: String,
    pub errcode: i32,
    pub errmsg: String,
    pub expire: i32,
    pub candidates: Vec<LyricsCandidate>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct LyricsCandidate {
    pub id: i64,
    pub product_from: String,
    pub duration: i64,
    pub accesskey: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SearchSongResponse {
    pub status: i32,
    pub errcode: i32,
    pub error: String,
    pub data: SearchSongData,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SearchSongData {
    pub info: Vec<SongInfo>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SongInfo {
    pub duration: i32,
    pub hash: String,
}
