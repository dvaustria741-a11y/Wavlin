//! Port of the Kotlin `lastfm` module. The Kotlin singleton with a mutable session key and
//! credentials becomes an owned client; credentials are required at construction.
//! JSON parsing is strict, except `subscriber` and `error`, which also accept numeric strings.

mod models;

pub use models::{Authentication, LastFmApiError, Session, TokenResponse};

use std::collections::BTreeMap;

pub const DEFAULT_SCROBBLE_DELAY_PERCENT: f32 = 0.5;
pub const DEFAULT_SCROBBLE_MIN_SONG_DURATION: i32 = 30;
pub const DEFAULT_SCROBBLE_DELAY_SECONDS: i32 = 180;

const API_URL: &str = "https://ws.audioscrobbler.com/2.0/";
const USER_AGENT: &str = "Wavlin (https://github.com/dvaustria741-a11y/Wavlin)";

#[derive(Debug, thiserror::Error)]
pub enum LastFmError {
    #[error("{message}")]
    Api { code: i32, message: String },
    #[error("no Last.fm session key set")]
    NotAuthenticated,
    #[error(transparent)]
    Http(#[from] reqwest::Error),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
}

/// Lowercase hex MD5 of the key-sorted `key + value` concatenation followed by `secret`.
pub fn api_signature(params: &BTreeMap<String, String>, secret: &str) -> String {
    let mut to_hash: String = params.iter().map(|(key, value)| format!("{key}{value}")).collect();
    to_hash.push_str(secret);
    format!("{:x}", md5::compute(to_hash.as_bytes()))
}

#[derive(Debug, Clone)]
pub struct LastFm {
    http: reqwest::Client,
    api_key: String,
    secret: String,
    pub session_key: Option<String>,
}

impl LastFm {
    pub fn new(api_key: impl Into<String>, secret: impl Into<String>) -> Result<Self, LastFmError> {
        Ok(Self {
            http: reqwest::Client::builder().user_agent(USER_AGENT).build()?,
            api_key: api_key.into(),
            secret: secret.into(),
            session_key: None,
        })
    }

    pub fn is_initialized(&self) -> bool {
        !self.api_key.is_empty() && !self.secret.is_empty()
    }

    pub fn auth_url(&self, token: &str) -> String {
        format!("https://www.last.fm/api/auth/?api_key={}&token={token}", self.api_key)
    }

    fn signed_form(
        &self,
        method: &str,
        session_key: Option<&str>,
        extra: Vec<(String, String)>,
    ) -> Vec<(String, String)> {
        let mut params: BTreeMap<String, String> = BTreeMap::new();
        params.insert("method".into(), method.into());
        params.insert("api_key".into(), self.api_key.clone());
        if let Some(session_key) = session_key {
            params.insert("sk".into(), session_key.into());
        }
        params.extend(extra);

        let signature = api_signature(&params, &self.secret);
        let mut form: Vec<(String, String)> = params.into_iter().collect();
        form.push(("api_sig".into(), signature));
        form.push(("format".into(), "json".into()));
        form
    }

    async fn post(
        &self,
        form: Vec<(String, String)>,
        format_in_query: bool,
    ) -> Result<reqwest::Response, reqwest::Error> {
        let mut request = self.http.post(API_URL).form(&form);
        if format_in_query {
            request = request.query(&[("format", "json")]);
        }
        request.send().await
    }

    fn require_session(&self) -> Result<&str, LastFmError> {
        self.session_key.as_deref().ok_or(LastFmError::NotAuthenticated)
    }

    pub async fn get_token(&self) -> Result<TokenResponse, LastFmError> {
        let form = self.signed_form("auth.getToken", None, Vec::new());
        Ok(self.post(form, false).await?.json::<TokenResponse>().await?)
    }

    pub async fn get_session(&self, token: &str) -> Result<Authentication, LastFmError> {
        let form = self.signed_form("auth.getSession", None, vec![("token".into(), token.into())]);
        Ok(self.post(form, false).await?.json::<Authentication>().await?)
    }

    pub async fn get_mobile_session(
        &self,
        username: &str,
        password: &str,
    ) -> Result<Authentication, LastFmError> {
        let form = self.signed_form(
            "auth.getMobileSession",
            None,
            vec![("username".into(), username.into()), ("password".into(), password.into())],
        );
        let text = self.post(form, true).await?.text().await?;
        if text.contains("\"error\"") {
            let error: LastFmApiError = serde_json::from_str(&text)?;
            return Err(LastFmError::Api { code: error.error, message: error.message });
        }
        Ok(serde_json::from_str(&text)?)
    }

    pub async fn update_now_playing(
        &self,
        artist: &str,
        track: &str,
        album: Option<&str>,
        track_number: Option<i32>,
        duration: Option<i32>,
    ) -> Result<reqwest::Response, LastFmError> {
        let session_key = self.require_session()?;
        let mut extra = vec![("artist".to_string(), artist.to_string()), ("track".to_string(), track.to_string())];
        if let Some(album) = album {
            extra.push(("album".into(), album.into()));
        }
        if let Some(track_number) = track_number {
            extra.push(("trackNumber".into(), track_number.to_string()));
        }
        if let Some(duration) = duration {
            extra.push(("duration".into(), duration.to_string()));
        }
        let form = self.signed_form("track.updateNowPlaying", Some(session_key), extra);
        Ok(self.post(form, true).await?)
    }

    pub async fn scrobble(
        &self,
        artist: &str,
        track: &str,
        timestamp: i64,
        album: Option<&str>,
        track_number: Option<i32>,
        duration: Option<i32>,
    ) -> Result<reqwest::Response, LastFmError> {
        let session_key = self.require_session()?;
        let mut extra = vec![
            ("artist[0]".to_string(), artist.to_string()),
            ("track[0]".to_string(), track.to_string()),
            ("timestamp[0]".to_string(), timestamp.to_string()),
        ];
        if let Some(album) = album {
            extra.push(("album[0]".into(), album.into()));
        }
        if let Some(track_number) = track_number {
            extra.push(("trackNumber[0]".into(), track_number.to_string()));
        }
        if let Some(duration) = duration {
            extra.push(("duration[0]".into(), duration.to_string()));
        }
        let form = self.signed_form("track.scrobble", Some(session_key), extra);
        Ok(self.post(form, true).await?)
    }

    pub async fn set_love_status(
        &self,
        artist: &str,
        track: &str,
        love: bool,
    ) -> Result<reqwest::Response, LastFmError> {
        let session_key = self.require_session()?;
        let method = if love { "track.love" } else { "track.unlove" };
        let extra = vec![("artist".to_string(), artist.to_string()), ("track".to_string(), track.to_string())];
        let form = self.signed_form(method, Some(session_key), extra);
        Ok(self.post(form, true).await?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signature_sorts_keys_and_appends_secret() {
        let mut params = BTreeMap::new();
        params.insert("b".to_string(), "2".to_string());
        params.insert("a".to_string(), "1".to_string());
        assert_eq!(api_signature(&params, "s"), format!("{:x}", md5::compute("a1b2s")));
        assert_eq!(format!("{:x}", md5::compute("")), "d41d8cd98f00b204e9800998ecf8427e");
    }

    #[test]
    fn signed_form_excludes_format_from_signature_and_includes_session() {
        let client = LastFm::new("key", "secret").unwrap();
        let form = client.signed_form("track.love", Some("sk1"), vec![("artist".into(), "A".into())]);
        let signature = &form.iter().find(|(k, _)| k == "api_sig").unwrap().1;
        assert_eq!(*signature, format!("{:x}", md5::compute("api_keykeyartistAmethodtrack.lovesksk1secret")));
        assert_eq!(form.last().unwrap(), &("format".to_string(), "json".to_string()));
    }

    #[test]
    fn requires_session_for_authenticated_calls() {
        let client = LastFm::new("key", "secret").unwrap();
        assert!(matches!(client.require_session(), Err(LastFmError::NotAuthenticated)));
        assert!(client.is_initialized());
        assert!(!LastFm::new("", "x").unwrap().is_initialized());
    }

    #[test]
    fn parses_session_with_numeric_or_string_subscriber() {
        let numeric: Authentication =
            serde_json::from_str(r#"{"session":{"name":"n","key":"k","subscriber":0}}"#).unwrap();
        let text: Authentication =
            serde_json::from_str(r#"{"session":{"name":"n","key":"k","subscriber":"1"}}"#).unwrap();
        assert_eq!(numeric.session.subscriber, 0);
        assert_eq!(text.session.subscriber, 1);
        let error: LastFmApiError = serde_json::from_str(r#"{"error":4,"message":"Invalid"}"#).unwrap();
        assert_eq!(error.error, 4);
    }

    #[test]
    fn builds_auth_url() {
        let client = LastFm::new("key", "secret").unwrap();
        assert_eq!(client.auth_url("tok"), "https://www.last.fm/api/auth/?api_key=key&token=tok");
    }
}
