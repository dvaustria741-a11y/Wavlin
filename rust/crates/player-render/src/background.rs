//! Pure parts of the player background, ported from `Player.kt` (the `background` lambda of the
//! `BottomSheet`), `DownscaledBlur.kt` and the fade transition specs used there.

use wavlin_ui_style::Rgba;

/// `PlayerBackgroundStyle` in `constants/`. `Default` draws only the sheet colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayerBackgroundStyle {
    Default,
    Blur,
    Gradient,
}

/// `DownscaledBlurBox(radius = if (useDarkTheme) 150.dp else 100.dp)` in Player.kt.
pub const BLUR_RADIUS_DARK_DP: f32 = 150.0;
pub const BLUR_RADIUS_LIGHT_DP: f32 = 100.0;
/// `DownscaledBlurBox` default `factor = 8f`.
pub const BLUR_DOWNSCALE_FACTOR: f32 = 8.0;
/// Black overlay over the blurred artwork: `Color.Black.copy(alpha = 0.3f)`.
pub const BLUR_OVERLAY_ALPHA: f32 = 0.3;
/// Black overlay over the gradient: `Color.Black.copy(alpha = 0.2f)`.
pub const GRADIENT_OVERLAY_ALPHA: f32 = 0.2;
/// `fadeIn(tween(800)).togetherWith(fadeOut(tween(800)))`.
pub const CROSSFADE_MS: f32 = 800.0;

pub fn blur_radius_dp(dark_theme: bool) -> f32 {
    if dark_theme {
        BLUR_RADIUS_DARK_DP
    } else {
        BLUR_RADIUS_LIGHT_DP
    }
}

/// Size of the small layer `DownscaledBlurBox` measures its content at.
pub fn small_layer_size(width: u32, height: u32, factor: f32) -> (u32, u32) {
    let s = |v: u32| ((v as f32 / factor).round() as u32).max(1);
    (s(width), s(height))
}

/// `Modifier.blur(radius / factor)` runs on the small layer, so its pixel radius is
/// `radius_dp / factor * density` there. Used as the Gaussian sigma (assumption shared with the
/// glass crate: Compose passes the radius to Skia as sigma).
pub fn blur_sigma_small_px(radius_dp: f32, density: f32, factor: f32) -> f32 {
    radius_dp / factor * density
}

/// `ContentScale.Crop` as a UV transform: `uv_image = uv_box * scale + offset`.
pub fn crop_uv(image: (u32, u32), box_size: (u32, u32)) -> ([f32; 2], [f32; 2]) {
    let (iw, ih) = (image.0 as f32, image.1 as f32);
    let (bw, bh) = (box_size.0 as f32, box_size.1 as f32);
    let scale = (bw / iw).max(bh / ih);
    let visible = [bw / scale / iw, bh / scale / ih];
    (visible, [(1.0 - visible[0]) / 2.0, (1.0 - visible[1]) / 2.0])
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GradientStops {
    pub positions: [f32; 3],
    pub colors: [Rgba; 3],
}

/// The `gradientColorStops` selection in Player.kt. Empty input draws no gradient.
pub fn gradient_stops(colors: &[Rgba]) -> Option<GradientStops> {
    match colors {
        [] => None,
        [a, b, c, ..] => Some(GradientStops { positions: [0.0, 0.5, 1.0], colors: [*a, *b, *c] }),
        [a, ..] => Some(GradientStops {
            positions: [0.0, 0.6, 1.0],
            colors: [*a, a.with_alpha(0.7), Rgba::BLACK],
        }),
    }
}

/// Straight-alpha gradient colour at `t` in 0..1 down the view, interpolating unpremultiplied
/// RGBA (assumption about Skia's default; not compared against device output).
pub fn gradient_color_at(stops: &GradientStops, t: f32) -> [f32; 4] {
    let c = |k: usize| [stops.colors[k].r, stops.colors[k].g, stops.colors[k].b, stops.colors[k].a];
    let (i, j) = if t <= stops.positions[1] { (0, 1) } else { (1, 2) };
    let span = (stops.positions[j] - stops.positions[i]).max(1e-6);
    let f = ((t - stops.positions[i]) / span).clamp(0.0, 1.0);
    let (a, b) = (c(i), c(j));
    [0, 1, 2, 3].map(|k| a[k] + (b[k] - a[k]) * f)
}

/// CPU reference of `layer_out` in the shader composited over an opaque base colour.
pub fn layer_over_base(src: [f32; 4], overlay_alpha: f32, group_alpha: f32, base: [f32; 3]) -> [f32; 3] {
    let pm = [src[0] * src[3], src[1] * src[3], src[2] * src[3], src[3]];
    let g_rgb = [pm[0] * (1.0 - overlay_alpha), pm[1] * (1.0 - overlay_alpha), pm[2] * (1.0 - overlay_alpha)];
    let g_a = pm[3] * (1.0 - overlay_alpha) + overlay_alpha;
    let s_rgb = g_rgb.map(|v| v * group_alpha);
    let s_a = g_a * group_alpha;
    [0, 1, 2].map(|k| s_rgb[k] + base[k] * (1.0 - s_a))
}

/// `FastOutSlowInEasing`, `CubicBezierEasing(0.4, 0.0, 0.2, 1.0)`, the default easing of `tween`.
pub fn fast_out_slow_in(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    let bez = |a: f32, b: f32, t: f32| {
        let u = 1.0 - t;
        3.0 * a * u * u * t + 3.0 * b * u * t * t + t * t * t
    };
    let (mut lo, mut hi) = (0.0f32, 1.0f32);
    for _ in 0..40 {
        let mid = (lo + hi) / 2.0;
        if bez(0.4, 0.2, mid) < x {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    bez(0.0, 1.0, (lo + hi) / 2.0)
}

/// Alphas `(outgoing, incoming)` of the two layers `elapsed_ms` into the cross-fade.
pub fn crossfade_alphas(elapsed_ms: f32) -> (f32, f32) {
    let p = fast_out_slow_in((elapsed_ms / CROSSFADE_MS).clamp(0.0, 1.0));
    (1.0 - p, p)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kotlin_constants_and_sizes() {
        assert_eq!(blur_radius_dp(true), 150.0);
        assert_eq!(blur_radius_dp(false), 100.0);
        assert_eq!(small_layer_size(1080, 2400, 8.0), (135, 300));
        assert_eq!(small_layer_size(3, 3, 8.0), (1, 1));
        assert_eq!(blur_sigma_small_px(150.0, 3.0, 8.0), 56.25);
    }

    #[test]
    fn crop_fills_the_box_and_centres() {
        // Square image into a tall box: full height visible, middle 1/2.25 of the width.
        let (scale, offset) = crop_uv((100, 100), (135, 300));
        assert!((scale[1] - 1.0).abs() < 1e-6);
        assert!((scale[0] - 135.0 / 300.0).abs() < 1e-6);
        assert!((offset[0] - (1.0 - scale[0]) / 2.0).abs() < 1e-6);
        assert_eq!(offset[1], 0.0);
        let (s2, o2) = crop_uv((100, 100), (100, 100));
        assert_eq!((s2, o2), ([1.0, 1.0], [0.0, 0.0]));
    }

    #[test]
    fn gradient_stop_selection_matches_player_kt() {
        let red = Rgba { r: 1.0, g: 0.0, b: 0.0, a: 1.0 };
        let green = Rgba { r: 0.0, g: 1.0, b: 0.0, a: 1.0 };
        let blue = Rgba { r: 0.0, g: 0.0, b: 1.0, a: 1.0 };
        assert!(gradient_stops(&[]).is_none());
        let three = gradient_stops(&[red, green, blue, red]).unwrap();
        assert_eq!(three.positions, [0.0, 0.5, 1.0]);
        assert_eq!(three.colors, [red, green, blue]);
        let one = gradient_stops(&[red]).unwrap();
        assert_eq!(one.positions, [0.0, 0.6, 1.0]);
        assert_eq!(one.colors, [red, red.with_alpha(0.7), Rgba::BLACK]);
        assert_eq!(gradient_stops(&[red, green]).unwrap(), one_two(red, green));
    }

    fn one_two(a: Rgba, _b: Rgba) -> GradientStops {
        // Two colours also take the fallback branch: only colors[0] is used.
        GradientStops { positions: [0.0, 0.6, 1.0], colors: [a, a.with_alpha(0.7), Rgba::BLACK] }
    }

    #[test]
    fn gradient_interpolates_between_stops() {
        let red = Rgba { r: 1.0, g: 0.0, b: 0.0, a: 1.0 };
        let blue = Rgba { r: 0.0, g: 0.0, b: 1.0, a: 1.0 };
        let s = gradient_stops(&[red, Rgba::WHITE, blue]).unwrap();
        assert_eq!(gradient_color_at(&s, 0.0), [1.0, 0.0, 0.0, 1.0]);
        assert_eq!(gradient_color_at(&s, 0.5), [1.0, 1.0, 1.0, 1.0]);
        let q = gradient_color_at(&s, 0.25);
        assert!((q[1] - 0.5).abs() < 1e-6 && (q[0] - 1.0).abs() < 1e-6);
    }

    #[test]
    fn overlay_and_alpha_compose_as_a_group() {
        // Opaque white, black overlay 0.3, group alpha 0.5, over a black base.
        let out = layer_over_base([1.0, 1.0, 1.0, 1.0], 0.3, 0.5, [0.0; 3]);
        assert!((out[0] - 0.35).abs() < 1e-6);
        // Alpha 0 leaves the base untouched.
        assert_eq!(layer_over_base([1.0; 4], 0.3, 0.0, [0.2, 0.3, 0.4]), [0.2, 0.3, 0.4]);
    }

    #[test]
    fn easing_is_monotonic_with_fixed_ends() {
        assert!(fast_out_slow_in(0.0).abs() < 1e-6);
        assert!((fast_out_slow_in(1.0) - 1.0).abs() < 1e-5);
        let mut prev = 0.0;
        for i in 1..=20 {
            let v = fast_out_slow_in(i as f32 / 20.0);
            assert!(v >= prev);
            prev = v;
        }
        assert!(fast_out_slow_in(0.5) > 0.7 && fast_out_slow_in(0.5) < 0.95);
        let (out, inc) = crossfade_alphas(400.0);
        assert!((out + inc - 1.0).abs() < 1e-6);
        let (o0, i0) = crossfade_alphas(0.0);
        assert!((o0 - 1.0).abs() < 1e-6 && i0.abs() < 1e-6);
        assert!((crossfade_alphas(5000.0).1 - 1.0).abs() < 1e-5);
    }
}
