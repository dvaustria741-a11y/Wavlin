/// Non-premultiplied sRGB colour with float channels, like Compose's `Color`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rgba {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub a: f32,
}

impl Rgba {
    pub const BLACK: Rgba = Rgba { r: 0.0, g: 0.0, b: 0.0, a: 1.0 };
    pub const WHITE: Rgba = Rgba { r: 1.0, g: 1.0, b: 1.0, a: 1.0 };

    pub fn from_argb(argb: u32) -> Self {
        Self {
            a: (argb >> 24) as f32 / 255.0,
            r: ((argb >> 16) & 0xFF) as f32 / 255.0,
            g: ((argb >> 8) & 0xFF) as f32 / 255.0,
            b: (argb & 0xFF) as f32 / 255.0,
        }
    }

    pub fn with_alpha(self, a: f32) -> Self {
        Self { a, ..self }
    }
}

/// One Palette swatch: opaque `0xRRGGBB` plus its pixel population.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Swatch {
    pub rgb: u32,
    pub population: u32,
}

/// The swatches androidx `Palette` produced for an artwork bitmap. Producing them (colour
/// quantisation) stays on the Android side.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Palette {
    pub dominant: Option<Swatch>,
    pub vibrant: Option<Swatch>,
    pub dark_vibrant: Option<Swatch>,
    pub light_vibrant: Option<Swatch>,
    pub muted: Option<Swatch>,
    pub dark_muted: Option<Swatch>,
    pub light_muted: Option<Swatch>,
}

impl Palette {
    /// Same order as the Kotlin `listOfNotNull(...)`; ties keep the earlier entry.
    fn candidates(&self) -> impl Iterator<Item = Swatch> {
        [
            self.dominant,
            self.vibrant,
            self.dark_vibrant,
            self.light_vibrant,
            self.muted,
            self.dark_muted,
            self.light_muted,
        ]
        .into_iter()
        .flatten()
    }
}

fn byte_div(numerator: i32, denominator: i32) -> f32 {
    numerator as f32 / denominator as f32
}

/// `[hue 0..360, saturation 0..1, value 0..1]`; alpha is ignored.
pub fn color_to_hsv(argb: u32) -> [f32; 3] {
    let r = ((argb >> 16) & 0xFF) as i32;
    let g = ((argb >> 8) & 0xFF) as i32;
    let b = (argb & 0xFF) as i32;
    let min = r.min(g).min(b);
    let max = r.max(g).max(b);
    let delta = max - min;
    let value = max as f32 / 255.0;
    if delta == 0 {
        return [0.0, 0.0, value];
    }
    let saturation = byte_div(delta, max);
    let mut hue = if r == max {
        byte_div(g - b, delta)
    } else if g == max {
        2.0 + byte_div(b - r, delta)
    } else {
        4.0 + byte_div(r - g, delta)
    };
    hue *= 60.0;
    if hue < 0.0 {
        hue += 360.0;
    }
    [hue, saturation, value]
}

fn round_to_byte(value: f32) -> u32 {
    (value + 0.5).floor().clamp(0.0, 255.0) as u32
}

/// Opaque `0xFFRRGGBB` for `[hue, saturation, value]`.
pub fn hsv_to_color(hsv: [f32; 3]) -> u32 {
    let saturation = hsv[1].clamp(0.0, 1.0);
    let value = hsv[2].clamp(0.0, 1.0);
    let value_byte = round_to_byte(value * 255.0);
    if saturation.abs() <= 1.0 / 4096.0 {
        return 0xFF00_0000 | (value_byte << 16) | (value_byte << 8) | value_byte;
    }

    let scaled_hue = if hsv[0] < 0.0 || hsv[0] >= 360.0 { 0.0 } else { hsv[0] / 60.0 };
    let sector = scaled_hue.floor();
    let fraction = scaled_hue - sector;
    let p = round_to_byte((1.0 - saturation) * value * 255.0);
    let q = round_to_byte((1.0 - saturation * fraction) * value * 255.0);
    let t = round_to_byte((1.0 - saturation * (1.0 - fraction)) * value * 255.0);

    let (r, g, b) = match sector as i32 {
        0 => (value_byte, t, p),
        1 => (q, value_byte, p),
        2 => (p, value_byte, t),
        3 => (p, q, value_byte),
        4 => (t, p, value_byte),
        _ => (value_byte, p, q),
    };
    0xFF00_0000 | (r << 16) | (g << 8) | b
}

fn is_color_vibrant(argb: u32) -> bool {
    let [_, saturation, brightness] = color_to_hsv(argb);
    saturation > 0.25 && brightness > 0.2 && brightness < 0.9
}

fn enhance_color_vividness(argb: u32, saturation_factor: f32) -> u32 {
    let mut hsv = color_to_hsv(argb);
    hsv[1] = (hsv[1] * saturation_factor).min(1.0);
    hsv[2] = (hsv[2] * 0.9).clamp(0.4, 0.85);
    hsv_to_color(hsv)
}

fn color_weight(swatch: Swatch) -> f32 {
    let [_, saturation, brightness] = color_to_hsv(0xFF00_0000 | swatch.rgb);
    let population_weight = swatch.population as f32 * 2.0;
    let vibrancy_bonus = if saturation > 0.3 && brightness > 0.3 { 1.5 } else { 1.0 };
    population_weight * vibrancy_bonus * (saturation + brightness) / 2.0
}

/// Port of `PlayerColorExtractor.extractGradientColors`: primary colour, a 0.6x darker variant,
/// then black. `fallback_argb` replaces a missing dominant swatch.
pub fn extract_gradient_colors(palette: &Palette, fallback_argb: u32) -> [Rgba; 3] {
    let mut best: Option<(Swatch, f32)> = None;
    for swatch in palette.candidates() {
        let weight = color_weight(swatch);
        if best.map_or(true, |(_, best_weight)| weight > best_weight) {
            best = Some((swatch, weight));
        }
    }

    let fallback_dominant = palette
        .dominant
        .map_or(fallback_argb, |swatch| 0xFF00_0000 | swatch.rgb);

    let primary_argb = match best {
        Some((swatch, _)) if is_color_vibrant(0xFF00_0000 | swatch.rgb) => {
            enhance_color_vividness(0xFF00_0000 | swatch.rgb, 1.3)
        }
        _ => enhance_color_vividness(fallback_dominant, 1.1),
    };

    let primary = Rgba::from_argb(primary_argb);
    let darker = Rgba {
        r: (primary.r * 0.6).max(0.0),
        g: (primary.g * 0.6).max(0.0),
        b: (primary.b * 0.6).max(0.0),
        a: primary.a,
    };
    [primary, darker, Rgba::BLACK]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hsv_of_primaries() {
        assert_eq!(color_to_hsv(0xFFFF0000), [0.0, 1.0, 1.0]);
        assert_eq!(color_to_hsv(0xFF00FF00), [120.0, 1.0, 1.0]);
        assert_eq!(color_to_hsv(0xFF0000FF), [240.0, 1.0, 1.0]);
        assert_eq!(color_to_hsv(0xFF808080), [0.0, 0.0, 128.0 / 255.0]);
        assert_eq!(color_to_hsv(0xFF000000), [0.0, 0.0, 0.0]);
    }

    #[test]
    fn hsv_round_trips_every_byte_triple_sample() {
        for r in (0..=255u32).step_by(15) {
            for g in (0..=255u32).step_by(15) {
                for b in (0..=255u32).step_by(15) {
                    let argb = 0xFF00_0000 | (r << 16) | (g << 8) | b;
                    assert_eq!(hsv_to_color(color_to_hsv(argb)), argb);
                }
            }
        }
    }

    #[test]
    fn hsv_to_color_handles_out_of_range_input() {
        assert_eq!(hsv_to_color([400.0, 2.0, 2.0]), 0xFFFF0000);
        assert_eq!(hsv_to_color([-10.0, 0.0, 0.5]), 0xFF808080);
    }

    #[test]
    fn vibrant_high_population_swatch_beats_dull_dominant() {
        let palette = Palette {
            dominant: Some(Swatch { rgb: 0x808080, population: 100 }),
            vibrant: Some(Swatch { rgb: 0xE02020, population: 100 }),
            ..Palette::default()
        };
        let [primary, darker, end] = extract_gradient_colors(&palette, 0xFF123456);
        assert!(primary.r > 0.75 && primary.r < 0.83);
        assert_eq!((primary.g, primary.b), (0.0, 0.0));
        assert_eq!(primary.a, 1.0);
        assert!((darker.r - primary.r * 0.6).abs() < 1e-6);
        assert_eq!(end, Rgba::BLACK);
    }

    #[test]
    fn empty_palette_uses_the_enhanced_fallback() {
        let [primary, _, _] = extract_gradient_colors(&Palette::default(), 0xFF2040C0);
        let expected = Rgba::from_argb(enhance_color_vividness(0xFF2040C0, 1.1));
        assert_eq!(primary, expected);
        assert_eq!(primary.a, 1.0);
    }

    #[test]
    fn non_vibrant_best_swatch_falls_back_to_dominant() {
        let palette = Palette {
            dominant: Some(Swatch { rgb: 0x606060, population: 50 }),
            ..Palette::default()
        };
        let [primary, _, _] = extract_gradient_colors(&palette, 0xFF000000);
        let expected = Rgba::from_argb(enhance_color_vividness(0xFF606060, 1.1));
        assert_eq!(primary, expected);
    }

    #[test]
    fn equal_weights_keep_the_earlier_candidate() {
        let palette = Palette {
            vibrant: Some(Swatch { rgb: 0xE02020, population: 10 }),
            muted: Some(Swatch { rgb: 0xE02020, population: 10 }),
            ..Palette::default()
        };
        let [first, _, _] = extract_gradient_colors(&palette, 0xFF000000);
        assert_eq!(first, Rgba::from_argb(enhance_color_vividness(0xFFE02020, 1.3)));
    }
}
