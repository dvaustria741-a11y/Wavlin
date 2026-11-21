#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Lyrics {
    pub text: String,
}

impl Lyrics {
    pub fn new(text: impl Into<String>) -> Self {
        Self { text: text.into() }
    }

    /// Parses `[mm:ss.xx]` lines into `(milliseconds, text)` pairs in insertion order,
    /// starting with an empty line at 0. Returns `None` when any line is malformed.
    pub fn sentences(&self) -> Option<Vec<(i64, String)>> {
        let mut entries: Vec<(i64, String)> = vec![(0, String::new())];

        for line in split_lines(self.text.trim()) {
            let units: Vec<u16> = line.encode_utf16().collect();
            if units.len() < 10 {
                continue;
            }
            let digit = |index: usize| -> Option<i64> {
                char::from_u32(u32::from(units[index]))
                    .and_then(|c| c.to_digit(10))
                    .map(i64::from)
            };
            let timestamp = digit(8)? * 10
                + digit(7)? * 100
                + digit(5)? * 1000
                + digit(4)? * 10_000
                + digit(2)? * 60 * 1000
                + digit(1)? * 600 * 1000;
            let text = String::from_utf16_lossy(&units[10..]);

            match entries.iter_mut().find(|entry| entry.0 == timestamp) {
                Some(entry) => entry.1 = text,
                None => entries.push((timestamp, text)),
            }
        }
        Some(entries)
    }
}

/// Splits on `\n`, `\r\n` and lone `\r`, like Kotlin's `String.lines()`.
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_timestamps_and_text() {
        let lyrics = Lyrics::new("[00:01.50] first\n[01:02.03] second");
        let sentences = lyrics.sentences().unwrap();
        assert_eq!(sentences[0], (0, String::new()));
        assert_eq!(sentences[1], (1_500, " first".to_string()));
        assert_eq!(sentences[2], (62_030, " second".to_string()));
    }

    #[test]
    fn short_lines_are_skipped_and_malformed_lines_fail_the_whole_parse() {
        assert_eq!(Lyrics::new("short\n[00:01.00] ok").sentences().unwrap().len(), 2);
        assert!(Lyrics::new("[xx:01.00] bad line").sentences().is_none());
    }

    #[test]
    fn later_duplicate_timestamp_replaces_earlier_text_in_place() {
        let sentences = Lyrics::new("[00:01.00] a\n[00:02.00] b\n[00:01.00] c").sentences().unwrap();
        assert_eq!(sentences[1], (1_000, " c".to_string()));
        assert_eq!(sentences.len(), 3);
    }

    #[test]
    fn splits_all_kotlin_line_terminators() {
        assert_eq!(split_lines("a\r\nb\rc\nd"), vec!["a", "b", "c", "d"]);
    }
}
