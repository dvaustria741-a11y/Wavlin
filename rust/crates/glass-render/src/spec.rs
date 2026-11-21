//! Description of one glass frame in pixels, plus packing into GPU uniform layouts.
//! Every default below names the Kyant backdrop 2.0.1 or Wavlin source it comes from.

use crate::math::{
    android_blur_mask_radius_to_sigma, ColorMatrix,
};
use bytemuck::{Pod, Zeroable};
use wavlin_ui_style::{GlassParams, Rgba};

pub type Color = [f32; 4];

fn color_of(c: Rgba) -> Color {
    [c.r, c.g, c.b, c.a]
}

/// `lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration)` in Kyant Lens.kt.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LensSpec {
    pub refraction_height_px: f32,
    pub refraction_amount_px: f32,
    pub depth_effect: bool,
    pub chromatic_aberration: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum HighlightStyleSpec {
    /// `HighlightStyle.Default`: colour white 0.5, angle 45 degrees, falloff 1.
    Default { color: Color, angle_deg: f32, falloff: f32 },
    /// `HighlightStyle.Plain`: colour white 0.38, constant around the outline.
    Plain { color: Color },
}

/// `Highlight` in Kyant Highlight.kt. `HighlightStyle.Ambient` is not ported: Wavlin does not use it.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HighlightSpec {
    pub width_px: f32,
    pub blur_radius_px: f32,
    pub alpha: f32,
    pub style: HighlightStyleSpec,
}

impl HighlightSpec {
    /// `Highlight.Default`: width 0.5dp, blur radius width/2, alpha 1, `HighlightStyle.Default`.
    pub fn kyant_default(density: f32) -> Self {
        let width = 0.5 * density;
        Self {
            width_px: width,
            blur_radius_px: width / 2.0,
            alpha: 1.0,
            style: HighlightStyleSpec::Default { color: [1.0, 1.0, 1.0, 0.5], angle_deg: 45.0, falloff: 1.0 },
        }
    }
}

/// `Shadow` in Kyant Shadow.kt.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ShadowSpec {
    pub radius_px: f32,
    pub offset_px: [f32; 2],
    pub color: Color,
    pub alpha: f32,
}

impl ShadowSpec {
    /// `Shadow.Default`: radius 24dp, offset (0, radius / 6), black 0.1, alpha 1.
    pub fn kyant_default(density: f32) -> Self {
        let radius = 24.0 * density;
        Self { radius_px: radius, offset_px: [0.0, radius / 6.0], color: [0.0, 0.0, 0.0, 0.1], alpha: 1.0 }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PanelSpec {
    /// x, y, width, height in target pixels.
    pub rect: [f32; 4],
    /// top-left, top-right, bottom-right, bottom-left, already clamped to half the short side.
    pub corner_radii_px: [f32; 4],
    pub tint: Color,
    pub overlay: Option<Color>,
    pub lens: Option<LensSpec>,
    pub highlight: Option<HighlightSpec>,
    pub shadow: Option<ShadowSpec>,
}

impl PanelSpec {
    /// A panel as `Modifier.appleGlass` builds it on the backdrop path: `drawBackdrop` with its
    /// default highlight and shadow (AppleGlass.kt passes neither).
    pub fn from_glass_params(rect: [f32; 4], corner_radii_px: [f32; 4], params: &GlassParams, density: f32) -> Self {
        let max_radius = rect[2].min(rect[3]) / 2.0;
        Self {
            rect,
            corner_radii_px: corner_radii_px.map(|r| r.clamp(0.0, max_radius)),
            tint: color_of(params.tint),
            overlay: params.pink_overlay.map(color_of),
            // ui-style names the three lens values positionally; Kyant 2.0.1 Lens.kt and the call in
            // AppleGlass.kt (`lens(minOf(..), minOf(..), false)`) fix them as height, amount, depthEffect.
            lens: params.lens.map(|l| LensSpec {
                refraction_height_px: l.arg0_px,
                refraction_amount_px: l.arg1_px,
                depth_effect: l.arg2_flag,
                chromatic_aberration: false,
            }),
            highlight: Some(HighlightSpec::kyant_default(density)),
            shadow: Some(ShadowSpec::kyant_default(density)),
        }
    }
}

/// Effects shared by every panel in a frame: the backdrop is filtered once.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BackdropSpec {
    /// Applied in order, each result clamped to 0..1.
    pub filters: [ColorMatrix; 2],
    /// Gaussian sigma of the backdrop blur in pixels. Assumes Compose `BlurEffect(radius)` uses the
    /// radius as sigma; not verified against device output.
    pub blur_sigma_px: f32,
}

impl BackdropSpec {
    /// vibrancy(), then colorControls(brightness, contrast, saturation), then blur(radius).
    pub fn from_glass_params(params: &GlassParams) -> Self {
        Self {
            filters: [
                ColorMatrix::vibrancy(),
                ColorMatrix::color_controls(params.brightness, params.contrast, params.saturation),
            ],
            blur_sigma_px: params.blur_radius_px,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct FrameSpec {
    pub backdrop: BackdropSpec,
    pub panels: Vec<PanelSpec>,
}

// ---- GPU layouts (must match shaders/*.wgsl) -------------------------------------------------

#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
pub(crate) struct GlobalsUniform {
    pub target_size: [f32; 2],
    pub srgb_target: f32,
    pub _pad: f32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
pub(crate) struct PostUniform {
    pub m0: [[f32; 4]; 4],
    pub o0: [f32; 4],
    pub m1: [[f32; 4]; 4],
    pub o1: [f32; 4],
    pub step_uv: [f32; 2],
    pub sigma: f32,
    pub radius: i32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
pub(crate) struct PanelUniform {
    pub rect: [f32; 4],
    pub radii: [f32; 4],
    pub tint: [f32; 4],
    pub overlay: [f32; 4],
    pub lens: [f32; 4],
    pub hl_color: [f32; 4],
    pub hl: [f32; 4],
    pub hl_mode: [f32; 4],
    pub sh_color: [f32; 4],
    pub sh: [f32; 4],
}

/// Column-major mat4 for WGSL from a Compose row-layout matrix, plus the offset column.
pub(crate) fn matrix_to_gpu(m: &ColorMatrix) -> ([[f32; 4]; 4], [f32; 4]) {
    let mut cols = [[0.0f32; 4]; 4];
    for (i, row) in m.rows.iter().enumerate() {
        for (j, col) in cols.iter_mut().enumerate() {
            col[i] = row[j];
        }
    }
    (cols, [m.rows[0][4], m.rows[1][4], m.rows[2][4], m.rows[3][4]])
}

pub(crate) fn panel_uniform(p: &PanelSpec) -> PanelUniform {
    let lens = match p.lens {
        Some(l) if l.refraction_height_px > 0.0 && l.refraction_amount_px > 0.0 => [
            l.refraction_height_px,
            l.refraction_amount_px,
            f32::from(u8::from(l.depth_effect)),
            f32::from(u8::from(l.chromatic_aberration)),
        ],
        _ => [0.0; 4],
    };
    let overlay = p.overlay.unwrap_or([0.0; 4]);

    let min_dim = p.rect[2].min(p.rect[3]);
    let (hl_color, hl, hl_mode) = match p.highlight {
        Some(h) if h.width_px > 0.0 => {
            // Kyant: stroke width = ceil(min(width, minDim / 2)) * 2, clipped to the shape, so the
            // visible half width is ceil(min(width, minDim / 2)).
            let half_width = h.width_px.min(min_dim / 2.0).ceil();
            let sigma = android_blur_mask_radius_to_sigma(h.blur_radius_px);
            match h.style {
                HighlightStyleSpec::Default { color, angle_deg, falloff } => (
                    [color[0], color[1], color[2], color[3] * h.alpha],
                    [half_width, sigma, angle_deg.to_radians(), falloff],
                    [1.0, 0.0, 0.0, 0.0],
                ),
                HighlightStyleSpec::Plain { color } => (
                    [color[0], color[1], color[2], color[3] * h.alpha],
                    [half_width, sigma, 0.0, 1.0],
                    [2.0, 0.0, 0.0, 0.0],
                ),
            }
        }
        _ => ([0.0; 4], [0.0; 4], [0.0; 4]),
    };

    let (sh_color, sh) = match p.shadow {
        Some(s) if s.radius_px > 0.0 => (
            [s.color[0], s.color[1], s.color[2], s.color[3] * s.alpha],
            [android_blur_mask_radius_to_sigma(s.radius_px), s.offset_px[0], s.offset_px[1], 1.0],
        ),
        _ => ([0.0; 4], [0.0; 4]),
    };

    PanelUniform {
        rect: p.rect,
        radii: p.corner_radii_px,
        tint: p.tint,
        overlay,
        lens,
        hl_color,
        hl,
        hl_mode,
        sh_color,
        sh,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wavlin_ui_style::{resolve_glass_params, GlassInputs};

    fn params() -> GlassParams {
        resolve_glass_params(GlassInputs {
            is_dark: true,
            pink: false,
            tint_alpha: None,
            lens_enabled: true,
            surface_min_dimension_px: 400.0,
            density: 3.0,
        })
    }

    #[test]
    fn panel_from_wavlin_params_maps_lens_positions() {
        let panel = PanelSpec::from_glass_params([10.0, 20.0, 400.0, 120.0], [60.0; 4], &params(), 3.0);
        let lens = panel.lens.unwrap();
        assert_eq!((lens.refraction_height_px, lens.refraction_amount_px, lens.depth_effect), (72.0, 96.0, false));
        assert_eq!(panel.corner_radii_px, [60.0; 4]);
        assert_eq!(panel.shadow.unwrap().offset_px, [0.0, 12.0]);
        assert_eq!(panel.highlight.unwrap().blur_radius_px, 0.75);
    }

    #[test]
    fn radii_are_clamped_to_half_the_short_side() {
        let panel = PanelSpec::from_glass_params([0.0, 0.0, 200.0, 50.0], [90.0; 4], &params(), 1.0);
        assert_eq!(panel.corner_radii_px, [25.0; 4]);
    }

    #[test]
    fn backdrop_spec_chains_vibrancy_then_color_controls() {
        let b = BackdropSpec::from_glass_params(&params());
        assert_eq!(b.filters[0], ColorMatrix::vibrancy());
        assert_eq!(b.filters[1], ColorMatrix::color_controls(0.05, 1.0, 1.5));
        assert_eq!(b.blur_sigma_px, 30.0);
    }

    #[test]
    fn matrix_packs_column_major() {
        let m = ColorMatrix::color_controls(0.1, 1.0, 1.5);
        let (cols, off) = matrix_to_gpu(&m);
        assert_eq!(cols[1][0], m.rows[0][1]);
        assert_eq!(cols[0][1], m.rows[1][0]);
        assert_eq!(off[0], m.rows[0][4]);
    }

    #[test]
    fn uniform_sizes_match_wgsl_layout() {
        assert_eq!(std::mem::size_of::<PostUniform>(), 176);
        assert_eq!(std::mem::size_of::<PanelUniform>(), 160);
        assert_eq!(std::mem::size_of::<GlobalsUniform>(), 16);
    }

    #[test]
    fn highlight_half_width_is_ceiled_like_kyant() {
        let panel = PanelSpec::from_glass_params([0.0, 0.0, 200.0, 100.0], [50.0; 4], &params(), 3.0);
        let u = panel_uniform(&panel);
        assert_eq!(u.hl[0], 2.0); // ceil(1.5)
        assert_eq!(u.hl_mode[0], 1.0);
        assert!((u.hl[2] - std::f32::consts::FRAC_PI_4).abs() < 1e-6);
    }
}
