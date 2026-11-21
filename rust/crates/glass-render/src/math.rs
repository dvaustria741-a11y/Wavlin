//! Pure, GPU-free math shared by the renderer and its tests. The functions here mirror the WGSL
//! in `shaders/` line for line so that the GPU output can be checked against them.
//! Conventions: pixels, +x right, +y down, angles in radians.

use std::f32::consts::SQRT_2;

pub type V2 = [f32; 2];

fn sub(a: V2, b: V2) -> V2 {
    [a[0] - b[0], a[1] - b[1]]
}
fn add(a: V2, b: V2) -> V2 {
    [a[0] + b[0], a[1] + b[1]]
}
fn mul(a: V2, k: f32) -> V2 {
    [a[0] * k, a[1] * k]
}
fn len(a: V2) -> f32 {
    (a[0] * a[0] + a[1] * a[1]).sqrt()
}
fn sign(x: f32) -> f32 {
    if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        0.0
    }
}

pub fn safe_normalize(v: V2) -> V2 {
    let l = len(v);
    if l > 1e-8 {
        mul(v, 1.0 / l)
    } else {
        [0.0, 0.0]
    }
}

/// Corner radii in the order top-left, top-right, bottom-right, bottom-left.
pub fn radius_at(centered: V2, radii: [f32; 4]) -> f32 {
    if centered[0] >= 0.0 {
        if centered[1] <= 0.0 {
            radii[1]
        } else {
            radii[2]
        }
    } else if centered[1] <= 0.0 {
        radii[0]
    } else {
        radii[3]
    }
}

pub fn sd_rounded_rect(coord: V2, half: V2, radius: f32) -> f32 {
    let corner = [coord[0].abs() - (half[0] - radius), coord[1].abs() - (half[1] - radius)];
    let outside = len([corner[0].max(0.0), corner[1].max(0.0)]) - radius;
    let inside = corner[0].max(corner[1]).min(0.0);
    outside + inside
}

pub fn grad_sd_rounded_rect(coord: V2, half: V2, radius: f32) -> V2 {
    let corner = [coord[0].abs() - (half[0] - radius), coord[1].abs() - (half[1] - radius)];
    if corner[0] >= 0.0 || corner[1] >= 0.0 {
        let n = safe_normalize([corner[0].max(0.0), corner[1].max(0.0)]);
        [sign(coord[0]) * n[0], sign(coord[1]) * n[1]]
    } else {
        let gx = if corner[1] <= corner[0] { 1.0 } else { 0.0 };
        [sign(coord[0]) * gx, sign(coord[1]) * (1.0 - gx)]
    }
}

pub fn circle_map(x: f32) -> f32 {
    1.0 - (1.0 - x * x).max(0.0).sqrt()
}

/// Where the lens samples the backdrop for the pixel at `coord` of a panel whose centre is
/// `center`. Returns `coord` itself outside the refraction band. `amount` is the positive
/// refraction amount; the shader negates it, as Kyant's `lens()` does.
pub fn lens_sample_pos(
    coord: V2,
    center: V2,
    half: V2,
    radii: [f32; 4],
    height: f32,
    amount: f32,
    depth_effect: bool,
) -> V2 {
    if height <= 0.0 || amount <= 0.0 {
        return coord;
    }
    let c = sub(coord, center);
    let radius = radius_at(c, radii);
    let sd = sd_rounded_rect(c, half, radius);
    if -sd >= height {
        return coord;
    }
    let sd = sd.min(0.0);
    let d = circle_map(1.0 - (-sd) / height) * -amount;
    let grad_radius = (radius * 1.5).min(half[0].min(half[1]));
    let depth = if depth_effect { 1.0 } else { 0.0 };
    let grad = safe_normalize(add(grad_sd_rounded_rect(c, half, grad_radius), mul(safe_normalize(c), depth)));
    add(coord, mul(grad, d))
}

/// Abramowitz-Stegun 7.1.26, max abs error 1.5e-7.
pub fn erf_approx(x: f32) -> f32 {
    let s = sign(x);
    let a = x.abs();
    let t = 1.0 / (1.0 + 0.327_591_1 * a);
    let poly = ((((1.061_405_4 * t - 1.453_152) * t) + 1.421_413_7) * t - 0.284_496_72) * t + 0.254_829_6;
    s * (1.0 - poly * t * (-a * a).exp())
}

/// Standard normal CDF.
pub fn phi(x: f32) -> f32 {
    0.5 * (1.0 + erf_approx(x / SQRT_2))
}

/// Kyant `HighlightStyle.Default` intensity: `pow(|dot(grad, normal)|, falloff)`.
pub fn default_highlight_intensity(coord: V2, half: V2, radii: [f32; 4], angle_rad: f32, falloff: f32) -> f32 {
    let radius = radius_at(coord, radii);
    let grad_radius = (radius * 1.5).min(half[0].min(half[1]));
    let g = grad_sd_rounded_rect(coord, half, grad_radius);
    let d = g[0] * angle_rad.cos() + g[1] * angle_rad.sin();
    d.abs().powf(falloff)
}

/// Blurred-stroke coverage of the highlight at signed distance `sd` (negative inside), for a
/// stroke centred on the outline with half width `half_width` and blur sigma `sigma`.
pub fn highlight_ring(sd: f32, half_width: f32, sigma: f32) -> f32 {
    let s = sigma.max(1e-3);
    phi((half_width - sd) / s) - phi((-half_width - sd) / s)
}

/// Shadow alpha factor at signed distance `sd_offset_shape` from the offset shape, before the
/// "keep outside" mask.
pub fn blurred_edge_coverage(sd_offset_shape: f32, sigma: f32) -> f32 {
    phi(-sd_offset_shape / sigma.max(1e-3))
}

/// Android's `BlurMaskFilter(radius)` converts radius to a Gaussian sigma as
/// `0.57735 * radius + 0.5` (Skia `ConvertRadiusToSigma`). Taken from Skia/AOSP knowledge, not
/// from a file the user supplied; it is the only radius-to-sigma assumption in this crate.
pub fn android_blur_mask_radius_to_sigma(radius_px: f32) -> f32 {
    if radius_px > 0.0 {
        0.577_35 * radius_px + 0.5
    } else {
        0.0
    }
}

/// Number of 2x downsample steps so the blur runs at a small sigma, and the kernel at that size.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BlurPlan {
    pub downsample_steps: u32,
    pub sigma_texels: f32,
    pub radius_texels: i32,
}

pub const MAX_BLUR_SIGMA_TEXELS: f32 = 4.0;
pub const MAX_DOWNSAMPLE_STEPS: u32 = 5;
const KERNEL_SIGMAS: f32 = 3.0;

pub fn plan_blur(sigma_px: f32) -> BlurPlan {
    if !sigma_px.is_finite() || sigma_px <= 0.0 {
        return BlurPlan { downsample_steps: 0, sigma_texels: 0.0, radius_texels: 0 };
    }
    let mut steps = 0;
    let mut s = sigma_px;
    while s > MAX_BLUR_SIGMA_TEXELS && steps < MAX_DOWNSAMPLE_STEPS {
        s *= 0.5;
        steps += 1;
    }
    BlurPlan { downsample_steps: steps, sigma_texels: s, radius_texels: (s * KERNEL_SIGMAS).ceil() as i32 }
}

/// A 4x5 colour matrix in Compose's row layout; the last column is in unit range (Compose stores
/// it in 0..255, Kyant multiplies by 255 and this type does not).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ColorMatrix {
    pub rows: [[f32; 5]; 4],
}

impl ColorMatrix {
    pub const IDENTITY: ColorMatrix = ColorMatrix {
        rows: [
            [1.0, 0.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0, 0.0],
            [0.0, 0.0, 1.0, 0.0, 0.0],
            [0.0, 0.0, 0.0, 1.0, 0.0],
        ],
    };

    /// Port of `colorControlsColorFilter` in Kyant ColorFilter.kt.
    pub fn color_controls(brightness: f32, contrast: f32, saturation: f32) -> ColorMatrix {
        let inv_sat = 1.0 - saturation;
        let r = 0.213 * inv_sat;
        let g = 0.715 * inv_sat;
        let b = 0.072 * inv_sat;
        let c = contrast;
        let t = 0.5 - c * 0.5 + brightness;
        let cs = c * saturation;
        ColorMatrix {
            rows: [
                [c * r + cs, c * g, c * b, 0.0, t],
                [c * r, c * g + cs, c * b, 0.0, t],
                [c * r, c * g, c * b + cs, 0.0, t],
                [0.0, 0.0, 0.0, 1.0, 0.0],
            ],
        }
    }

    /// `vibrancy()` in Kyant ColorFilter.kt is `colorControls(saturation = 1.5)`.
    pub fn vibrancy() -> ColorMatrix {
        ColorMatrix::color_controls(0.0, 1.0, VIBRANCY_SATURATION)
    }

    pub fn apply(&self, rgba: [f32; 4]) -> [f32; 4] {
        let mut out = [0.0; 4];
        for (i, row) in self.rows.iter().enumerate() {
            let v = row[0] * rgba[0] + row[1] * rgba[1] + row[2] * rgba[2] + row[3] * rgba[3] + row[4];
            out[i] = v.clamp(0.0, 1.0);
        }
        out
    }
}

pub const VIBRANCY_SATURATION: f32 = 1.5;

/// Padding Kyant adds around the backdrop layer for the effect chain used by appleGlass
/// (colour filters, then blur, then lens). Blur after a prior effect sets `padding = radius`;
/// lens then subtracts the refraction height, floored at 0.
pub fn backdrop_padding(blur_radius_px: f32, refraction_height_px: f32) -> f32 {
    let mut padding = 0.0;
    if blur_radius_px > 0.0 {
        padding = blur_radius_px;
    }
    if refraction_height_px > 0.0 && padding > 0.0 {
        padding = (padding - refraction_height_px).max(0.0);
    }
    padding
}

#[cfg(test)]
mod tests {
    use super::*;

    const HALF: V2 = [100.0, 40.0];
    const RADII: [f32; 4] = [40.0; 4];

    #[test]
    fn sdf_is_zero_on_edge_and_signed() {
        assert!(sd_rounded_rect([100.0, 0.0], HALF, 40.0).abs() < 1e-4);
        assert!(sd_rounded_rect([0.0, 0.0], HALF, 40.0) < 0.0);
        assert!(sd_rounded_rect([150.0, 0.0], HALF, 40.0) > 0.0);
    }

    #[test]
    fn lens_is_identity_deep_inside_and_pushes_at_the_edge() {
        let center = [200.0, 100.0];
        let deep = lens_sample_pos([200.0, 100.0], center, HALF, RADII, 12.0, 20.0, false);
        assert_eq!(deep, [200.0, 100.0]);
        // 1 px inside the right edge: the displacement points inward (grad.x = +1, amount negated).
        let p = lens_sample_pos([299.0, 100.0], center, HALF, RADII, 12.0, 20.0, false);
        let x: f32 = 1.0 - 1.0 / 12.0;
        let expected = 299.0 - (1.0 - (1.0 - x * x).sqrt()) * 20.0;
        assert!((p[0] - expected).abs() < 1e-3, "expected {expected}, got {p:?}");
        assert!((p[1] - 100.0).abs() < 1e-3);
        // Exactly at the band limit nothing moves.
        let edge = lens_sample_pos([300.0 - 12.0, 100.0], center, HALF, RADII, 12.0, 20.0, false);
        assert_eq!(edge, [288.0, 100.0]);
    }

    #[test]
    fn lens_disabled_by_non_positive_inputs() {
        let c = [10.0, 10.0];
        assert_eq!(lens_sample_pos([12.0, 8.0], c, HALF, RADII, 0.0, 5.0, false), [12.0, 8.0]);
        assert_eq!(lens_sample_pos([12.0, 8.0], c, HALF, RADII, 5.0, 0.0, false), [12.0, 8.0]);
    }

    #[test]
    fn radius_selection_uses_the_centered_quadrant() {
        let r = [1.0, 2.0, 3.0, 4.0];
        assert_eq!(radius_at([-1.0, -1.0], r), 1.0);
        assert_eq!(radius_at([1.0, -1.0], r), 2.0);
        assert_eq!(radius_at([1.0, 1.0], r), 3.0);
        assert_eq!(radius_at([-1.0, 1.0], r), 4.0);
    }

    #[test]
    fn color_controls_matches_kotlin_formula() {
        let m = ColorMatrix::color_controls(0.05, 1.0, 1.5);
        // Neutral grey stays grey and gains the brightness offset (t = 0.5 - 0.5 + 0.05).
        let out = m.apply([0.5, 0.5, 0.5, 1.0]);
        for ch in &out[..3] {
            assert!((ch - 0.55).abs() < 1e-5, "{out:?}");
        }
        assert_eq!(ColorMatrix::color_controls(0.0, 1.0, 1.0).rows, ColorMatrix::IDENTITY.rows);
        // Saturation 1.5 pushes a saturated colour apart: red channel up, green/blue down (clamped).
        let v = ColorMatrix::vibrancy().apply([0.8, 0.4, 0.4, 1.0]);
        assert!(v[0] > 0.8 && v[1] < 0.4);
    }

    #[test]
    fn blur_plan_keeps_kernel_small() {
        assert_eq!(plan_blur(0.0).radius_texels, 0);
        let p = plan_blur(30.0);
        assert_eq!(p.downsample_steps, 3);
        assert!((p.sigma_texels - 3.75).abs() < 1e-6);
        assert_eq!(p.radius_texels, 12);
        assert_eq!(plan_blur(f32::NAN).downsample_steps, 0);
        assert!(plan_blur(1.0e9).downsample_steps <= MAX_DOWNSAMPLE_STEPS);
    }

    #[test]
    fn padding_follows_kyant_effect_chain() {
        assert_eq!(backdrop_padding(30.0, 24.0), 6.0);
        assert_eq!(backdrop_padding(30.0, 72.0), 0.0);
        assert_eq!(backdrop_padding(0.0, 24.0), 0.0);
    }

    #[test]
    fn phi_is_a_cdf() {
        assert!((phi(0.0) - 0.5).abs() < 1e-6);
        assert!(phi(4.0) > 0.9999 && phi(-4.0) < 1e-4);
        assert!((phi(1.0) - 0.841_344_7).abs() < 1e-5);
    }

    #[test]
    fn highlight_intensity_peaks_facing_the_light() {
        // Angle 45 degrees: normal points down-right in y-down pixel space.
        let toward = default_highlight_intensity([99.0, 20.0], HALF, RADII, std::f32::consts::FRAC_PI_4, 1.0);
        let away = default_highlight_intensity([-99.0, -20.0], HALF, RADII, std::f32::consts::FRAC_PI_4, 1.0);
        assert!(toward > 0.0 && away > 0.0);
        let top = default_highlight_intensity([0.0, -40.0], HALF, RADII, std::f32::consts::FRAC_PI_4, 1.0);
        assert!((top - std::f32::consts::FRAC_1_SQRT_2).abs() < 1e-4);
    }

    #[test]
    fn android_blur_radius_conversion() {
        assert_eq!(android_blur_mask_radius_to_sigma(0.0), 0.0);
        assert!((android_blur_mask_radius_to_sigma(72.0) - 42.0692).abs() < 1e-3);
    }
}
