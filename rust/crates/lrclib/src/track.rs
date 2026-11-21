use crate::text::max_similarity;
use serde::Deserialize;

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Track {
    pub id: i32,
    pub track_name: String,
    pub artist_name: String,
    pub duration: f64,
    pub plain_lyrics: Option<String>,
    pub synced_lyrics: Option<String>,
}

impl Track {
    pub fn duration_seconds(&self) -> i32 {
        self.duration as i32
    }

    pub fn duration_difference(&self, duration: i32) -> i64 {
        (i64::from(self.duration_seconds()) - i64::from(duration)).abs()
    }

    pub fn has_lyrics(&self) -> bool {
        self.synced_lyrics.is_some() || self.plain_lyrics.is_some()
    }
}

fn first_synced_or_first(tracks: &[Track]) -> Option<&Track> {
    tracks
        .iter()
        .find(|track| track.synced_lyrics.is_some())
        .or_else(|| tracks.first())
}

fn closest_within<'a>(
    tracks: impl Iterator<Item = &'a Track>,
    duration: i32,
    tolerance: i64,
) -> Option<&'a Track> {
    tracks
        .min_by_key(|track| track.duration_difference(duration))
        .filter(|track| track.duration_difference(duration) <= tolerance)
}

/// Closest track within two seconds of `duration`; `-1` means any duration is acceptable.
pub fn best_matching_for_duration(tracks: &[Track], duration: i32) -> Option<&Track> {
    if tracks.is_empty() {
        return None;
    }
    if duration == -1 {
        return first_synced_or_first(tracks);
    }
    closest_within(tracks.iter(), duration, 2)
}

/// Prefers synced lyrics within five seconds of `duration`, then any lyrics within five seconds.
pub fn best_matching_for_relaxed(tracks: &[Track], duration: i32) -> Option<&Track> {
    if tracks.is_empty() {
        return None;
    }
    if duration == -1 {
        return first_synced_or_first(tracks);
    }

    let synced = closest_within(
        tracks.iter().filter(|track| track.synced_lyrics.is_some()),
        duration,
        5,
    );
    synced.or_else(|| closest_within(tracks.iter(), duration, 5))
}

pub fn best_matching_for<'a>(
    tracks: &'a [Track],
    duration: i32,
    track_name: Option<&str>,
    artist_name: Option<&str>,
) -> Option<&'a Track> {
    if tracks.is_empty() {
        return None;
    }
    if duration == -1 {
        if let (Some(track_name), Some(artist_name)) = (track_name, artist_name) {
            return find_best_match(tracks, track_name, artist_name);
        }
        return first_synced_or_first(tracks);
    }
    best_matching_for_relaxed(tracks, duration)
}

fn pair_similarity(track: &Track, track_name: &str, artist_name: &str) -> f64 {
    let track_name_similarity =
        max_similarity(track_name, &track.track_name.trim().to_lowercase());
    let artist_name_similarity =
        max_similarity(artist_name, &track.artist_name.trim().to_lowercase());
    (track_name_similarity + artist_name_similarity) / 2.0
}

fn find_best_match<'a>(
    tracks: &'a [Track],
    track_name: &str,
    artist_name: &str,
) -> Option<&'a Track> {
    let track_name = track_name.trim().to_lowercase();
    let artist_name = artist_name.trim().to_lowercase();

    let mut best: Option<(&Track, f64)> = None;
    for track in tracks {
        let mut score = pair_similarity(track, &track_name, &artist_name);
        if track.synced_lyrics.is_some() {
            score += 0.1;
        }
        if best.map_or(true, |(_, best_score)| score > best_score) {
            best = Some((track, score));
        }
    }

    best.map(|(track, _)| track)
        .filter(|track| pair_similarity(track, &track_name, &artist_name) > 0.6)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: i32, name: &str, artist: &str, duration: f64, synced: bool, plain: bool) -> Track {
        Track {
            id,
            track_name: name.to_string(),
            artist_name: artist.to_string(),
            duration,
            plain_lyrics: plain.then(|| "plain".to_string()),
            synced_lyrics: synced.then(|| "[00:01.00] synced".to_string()),
        }
    }

    #[test]
    fn deserializes_lrclib_json() {
        let json = r#"[{"id":7,"trackName":"A","artistName":"B","albumName":"C","duration":201.4,"instrumental":false,"plainLyrics":"p","syncedLyrics":null}]"#;
        let tracks: Vec<Track> = serde_json::from_str(json).unwrap();
        assert_eq!(tracks[0].id, 7);
        assert_eq!(tracks[0].duration_seconds(), 201);
        assert_eq!(tracks[0].plain_lyrics.as_deref(), Some("p"));
        assert!(tracks[0].synced_lyrics.is_none());
    }

    #[test]
    fn relaxed_prefers_synced_within_five_seconds() {
        let tracks = vec![
            track(1, "a", "b", 200.0, false, true),
            track(2, "a", "b", 204.0, true, false),
        ];
        assert_eq!(best_matching_for_relaxed(&tracks, 200).unwrap().id, 2);
        assert!(best_matching_for_relaxed(&tracks, 100).is_none());
    }

    #[test]
    fn exact_duration_uses_two_second_tolerance() {
        let tracks = vec![track(1, "a", "b", 203.0, true, false)];
        assert!(best_matching_for_duration(&tracks, 200).is_none());
        assert_eq!(best_matching_for_duration(&tracks, 201).unwrap().id, 1);
    }

    #[test]
    fn unknown_duration_prefers_title_artist_match() {
        let tracks = vec![
            track(1, "Completely Different", "Nobody", 100.0, true, false),
            track(2, "Hello", "Adele", 295.0, false, true),
        ];
        let best = best_matching_for(&tracks, -1, Some("Hello"), Some("Adele")).unwrap();
        assert_eq!(best.id, 2);
        assert!(best_matching_for(&tracks, -1, Some("zzzzzz"), Some("qqqqqq")).is_none());
    }

    #[test]
    fn ties_keep_the_first_candidate() {
        let tracks = vec![
            track(1, "same", "same", 100.0, false, true),
            track(2, "same", "same", 100.0, false, true),
        ];
        assert_eq!(best_matching_for(&tracks, -1, Some("same"), Some("same")).unwrap().id, 1);
    }
}
