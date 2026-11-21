use regex::Regex;
use std::sync::OnceLock;

const KEYWORDS: &str = "official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit";
const WS: &str = r"[ \t\n\x0B\x0C\r]";

const ARTIST_SEPARATORS: [&str; 11] = [
    " & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ",
];

fn title_cleanup_patterns() -> &'static [Regex] {
    static PATTERNS: OnceLock<Vec<Regex>> = OnceLock::new();
    PATTERNS.get_or_init(|| {
        let sources = [
            format!(r"(?i){WS}*\(.*?({KEYWORDS}).*?\)"),
            format!(r"(?i){WS}*\[.*?({KEYWORDS}).*?\]"),
            format!(r"{WS}*【.*?】"),
            format!(r"{WS}*\|.*$"),
            format!(r"(?i){WS}*-{WS}*(official|video|audio|lyrics|lyric|visualizer).*$"),
            format!(r"(?i){WS}*\(feat\..*?\)"),
            format!(r"(?i){WS}*\(ft\..*?\)"),
            format!(r"(?i){WS}*feat\..*$"),
            format!(r"(?i){WS}*ft\..*$"),
        ];
        sources
            .iter()
            .map(|source| Regex::new(source).expect("static title cleanup pattern"))
            .collect()
    })
}

pub fn clean_title(title: &str) -> String {
    let mut cleaned = title.trim().to_string();
    for pattern in title_cleanup_patterns() {
        cleaned = pattern.replace_all(&cleaned, "").into_owned();
    }
    cleaned.trim().to_string()
}

fn find_ascii_case_insensitive(haystack: &str, needle: &str) -> Option<usize> {
    let needle = needle.as_bytes();
    haystack
        .as_bytes()
        .windows(needle.len())
        .position(|window| window.eq_ignore_ascii_case(needle))
}

pub fn clean_artist(artist: &str) -> String {
    let mut cleaned = artist.trim().to_string();
    for separator in ARTIST_SEPARATORS {
        if let Some(index) = find_ascii_case_insensitive(&cleaned, separator) {
            cleaned.truncate(index);
            break;
        }
    }
    cleaned.trim().to_string()
}

pub fn levenshtein_distance(first: &str, second: &str) -> usize {
    let a: Vec<u16> = first.encode_utf16().collect();
    let b: Vec<u16> = second.encode_utf16().collect();
    let mut previous: Vec<usize> = (0..=b.len()).collect();
    let mut current = vec![0usize; b.len() + 1];

    for i in 1..=a.len() {
        current[0] = i;
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            current[j] = (previous[j] + 1)
                .min(current[j - 1] + 1)
                .min(previous[j - 1] + cost);
        }
        std::mem::swap(&mut previous, &mut current);
    }
    previous[b.len()]
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

fn distance_score(first: &str, second: &str) -> f64 {
    let max_length = utf16_len(first).max(utf16_len(second));
    1.0 - (levenshtein_distance(first, second) as f64 / max_length as f64)
}

/// Similarity used when ranking candidates in `LrcLib::get_all_lyrics`: a containment hit
/// scores a flat 0.8 and replaces the edit-distance score instead of competing with it.
pub fn string_similarity(first: &str, second: &str) -> f64 {
    let a = first.trim().to_lowercase();
    let b = second.trim().to_lowercase();

    if a == b {
        return 1.0;
    }
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    if a.contains(&b) || b.contains(&a) {
        0.8
    } else {
        distance_score(&a, &b)
    }
}

/// Similarity used by `Track` matching: the better of the containment score and the
/// edit-distance score. Inputs are expected to be trimmed and lowercased already.
pub fn max_similarity(first: &str, second: &str) -> f64 {
    if first == second {
        return 1.0;
    }
    if first.is_empty() || second.is_empty() {
        return 0.0;
    }
    let contains_score: f64 = if first.contains(second) || second.contains(first) {
        0.8
    } else {
        0.0
    };
    contains_score.max(distance_score(first, second))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cleans_decorations_from_titles() {
        assert_eq!(clean_title("Song Name (Official Video)"), "Song Name");
        assert_eq!(clean_title("Song Name [Remastered 2011]"), "Song Name");
        assert_eq!(clean_title("Song Name | Channel"), "Song Name");
        assert_eq!(clean_title("Song Name - Official Audio"), "Song Name");
        assert_eq!(clean_title("Song Name (feat. Someone)"), "Song Name");
        assert_eq!(clean_title("Song Name ft. Someone"), "Song Name");
        assert_eq!(clean_title("  Plain Title  "), "Plain Title");
        assert_eq!(clean_title("曲名 【MV】"), "曲名");
    }

    #[test]
    fn keeps_parentheses_without_keywords() {
        assert_eq!(clean_title("Song (Part 2)"), "Song (Part 2)");
    }

    #[test]
    fn takes_primary_artist_before_first_matching_separator() {
        assert_eq!(clean_artist("A & B"), "A");
        assert_eq!(clean_artist("A AND B"), "A");
        assert_eq!(clean_artist("A, B & C"), "A, B");
        assert_eq!(clean_artist("Solo"), "Solo");
        assert_eq!(clean_artist("A FEAT. B"), "A");
    }

    #[test]
    fn levenshtein_matches_known_values() {
        assert_eq!(levenshtein_distance("kitten", "sitting"), 3);
        assert_eq!(levenshtein_distance("", "abc"), 3);
        assert_eq!(levenshtein_distance("same", "same"), 0);
    }

    #[test]
    fn similarity_variants_differ_on_containment() {
        assert_eq!(string_similarity("abc", "abcd"), 0.8);
        assert_eq!(max_similarity("abc", "abcd"), 0.8);
        assert_eq!(string_similarity("", "x"), 0.0);
        assert_eq!(string_similarity(" ABC ", "abc"), 1.0);
        assert!(max_similarity("abcdefghij", "abcdefghiz") > 0.8);
    }
}
