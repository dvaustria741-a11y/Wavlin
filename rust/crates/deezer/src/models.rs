use serde::Deserialize;

#[derive(Debug, Clone, Default, Deserialize)]
pub struct ArtistSearchResponse {
    #[serde(default)]
    pub data: Vec<DeezerArtist>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct DeezerArtist {
    pub id: i64,
    pub name: String,
    #[serde(default)]
    pub picture_medium: Option<String>,
    #[serde(default)]
    pub nb_album: i32,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AlbumsResponse {
    #[serde(default)]
    pub data: Vec<DeezerAlbum>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct DeezerAlbum {
    pub id: i64,
    pub title: String,
    #[serde(default)]
    pub release_date: Option<String>,
    #[serde(default)]
    pub cover_medium: Option<String>,
    /// `album`, `single`, `ep`, `compile`, ...
    #[serde(default)]
    pub record_type: Option<String>,
    #[serde(default)]
    pub explicit_lyrics: bool,
}
