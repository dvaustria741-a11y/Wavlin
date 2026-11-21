use serde::{Deserialize, Deserializer};

fn int_or_numeric_string<'de, D: Deserializer<'de>>(deserializer: D) -> Result<i32, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum IntOrString {
        Int(i32),
        Text(String),
    }
    match IntOrString::deserialize(deserializer)? {
        IntOrString::Int(value) => Ok(value),
        IntOrString::Text(text) => text.trim().parse().map_err(serde::de::Error::custom),
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Authentication {
    pub session: Session,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Session {
    pub name: String,
    pub key: String,
    #[serde(deserialize_with = "int_or_numeric_string")]
    pub subscriber: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct TokenResponse {
    pub token: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct LastFmApiError {
    #[serde(deserialize_with = "int_or_numeric_string")]
    pub error: i32,
    pub message: String,
}
