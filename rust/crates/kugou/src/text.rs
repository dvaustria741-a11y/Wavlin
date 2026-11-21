use crate::models::Keyword;
use regex::Regex;
use std::sync::OnceLock;

const HEAD_CUT_LIMIT: isize = 30;

fn static_regex(cell: &'static OnceLock<Regex>, pattern: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pattern).expect("static pattern"))
}

fn accepted_regex() -> &'static Regex {
    static CELL: OnceLock<Regex> = OnceLock::new();
    static_regex(&CELL, r"\A\[[0-9]{2}:[0-9]{2}\.[0-9]{2,3}\].*\z")
}

fn banned_regex() -> &'static Regex {
    static CELL: OnceLock<Regex> = OnceLock::new();
    static_regex(&CELL, r"\A.+\].+[:：].+\z")
}

fn bracket_regexes() -> &'static [Regex] {
    static CELL: OnceLock<Vec<Regex>> = OnceLock::new();
    CELL.get_or_init(|| {
        ["\\(.*\\)", "（.*）", "「.*」", "『.*』", "<.*>", "《.*》", "〈.*〉", "＜.*＞"]
            .iter()
            .map(|pattern| Regex::new(pattern).expect("static pattern"))
            .collect()
    })
}

fn parentheses_regexes() -> &'static [Regex] {
    static CELL: OnceLock<Vec<Regex>> = OnceLock::new();
    CELL.get_or_init(|| {
        ["\\(.*\\)", "（.*）"]
            .iter()
            .map(|pattern| Regex::new(pattern).expect("static pattern"))
            .collect()
    })
}

fn strip_all(value: &str, patterns: &[Regex]) -> String {
    patterns
        .iter()
        .fold(value.to_string(), |current, pattern| pattern.replace_all(&current, "").into_owned())
}

pub fn normalize_title(title: &str) -> String {
    strip_all(title, bracket_regexes())
}

pub fn normalize_artist(artist: &str) -> String {
    let replaced = artist
        .replace(", ", "、")
        .replace(" & ", "、")
        .replace('.', "")
        .replace('和', "、");
    strip_all(&replaced, parentheses_regexes())
}

pub fn generate_keyword(title: &str, artist: &str, album: Option<&str>) -> Keyword {
    Keyword {
        title: normalize_title(title),
        artist: normalize_artist(artist),
        album: album.map(str::to_string),
    }
}

pub fn search_query(keyword: &Keyword) -> String {
    let mut query = format!("{} - {}", keyword.title, keyword.artist);
    if let Some(album) = keyword.album.as_deref().filter(|album| !album.trim().is_empty()) {
        query.push(' ');
        query.push_str(album);
    }
    query
}

/// Percent-encodes everything except `A-Za-z0-9-._~`; spaces become `%20`.
pub fn percent_encode(value: &str) -> String {
    let mut encoded = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            encoded.push(char::from(byte));
        } else {
            encoded.push_str(&format!("%{byte:02X}"));
        }
    }
    encoded
}

pub fn split_lines(text: &str) -> Vec<&str> {
    let bytes = text.as_bytes();
    let mut lines = Vec::new();
    let mut start = 0;
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'\n' => {
                lines.push(&text[start..index]);
                index += 1;
                start = index;
            }
            b'\r' => {
                lines.push(&text[start..index]);
                index += if bytes.get(index + 1) == Some(&b'\n') { 2 } else { 1 };
                start = index;
            }
            _ => index += 1,
        }
    }
    lines.push(&text[start..]);
    lines
}

/// Keeps only timestamped lines and removes credit lines (singer, writer, composer...) at the
/// head and tail of the lyrics.
pub fn normalize_lyrics(text: &str) -> String {
    let lines: Vec<&str> = split_lines(text)
        .into_iter()
        .filter(|line| accepted_regex().is_match(line))
        .collect();
    let size = lines.len() as isize;
    let last_index = size - 1;

    let mut head_cut = 0usize;
    let mut i = HEAD_CUT_LIMIT.min(last_index);
    while i >= 0 {
        if banned_regex().is_match(lines[i as usize]) {
            head_cut = i as usize + 1;
            break;
        }
        i -= 1;
    }
    let after_head = &lines[head_cut.min(lines.len())..];

    let mut tail_cut = 0usize;
    let mut i = (size - HEAD_CUT_LIMIT).min(last_index);
    while i >= 0 {
        if banned_regex().is_match(lines[(last_index - i) as usize]) {
            tail_cut = i as usize + 1;
            break;
        }
        i -= 1;
    }
    let kept = after_head.len().saturating_sub(tail_cut);

    after_head[..kept].join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_titles_and_artists() {
        assert_eq!(normalize_title("Song (Live)「X」"), "Song ");
        assert_eq!(normalize_title("曲《名》"), "曲");
        assert_eq!(normalize_artist("A, B & C"), "A、B、C");
        assert_eq!(normalize_artist("Foo (JP)"), "Foo ");
        assert_eq!(normalize_artist("A.B和C"), "AB、C");
    }

    #[test]
    fn builds_search_query_with_optional_album() {
        let keyword = generate_keyword("Song", "Artist", Some("Album"));
        assert_eq!(search_query(&keyword), "Song - Artist Album");
        let keyword = generate_keyword("Song", "Artist", Some("  "));
        assert_eq!(search_query(&keyword), "Song - Artist");
    }

    #[test]
    fn percent_encodes_spaces_and_unicode() {
        assert_eq!(percent_encode("a b-c"), "a%20b-c");
        assert_eq!(percent_encode("曲"), "%E6%9B%B2");
    }

    #[test]
    fn strips_credit_lines_at_head_and_tail() {
        let text = "[00:00.00]作词 : Someone\r\n[00:01.00]作曲 : Other\n[00:02.00]real line one\n[00:03.00]real line two\ngarbage without timestamp";
        assert_eq!(normalize_lyrics(text), "[00:02.00]real line one\n[00:03.00]real line two");
    }

    #[test]
    fn leaves_clean_lyrics_untouched_and_handles_empty() {
        assert_eq!(normalize_lyrics("[00:01.00]a\n[00:02.50]b"), "[00:01.00]a\n[00:02.50]b");
        assert_eq!(normalize_lyrics(""), "");
        assert_eq!(normalize_lyrics("no timestamps"), "");
    }
}
