use crate::color::Rgba;

pub const PINK_GLASS_TINT_ARGB: u32 = 0xFFFF_C4D9;
pub const ARTWORK_CORNER_APPLE_DP: f32 = 8.0;
pub const ARTWORK_CORNER_DEFAULT_DP: f32 = 3.0;
pub const ANDROID_13_SDK_INT: u32 = 33;

/// Lens refraction runs on an AGSL runtime shader, which needs Android 13+.
pub fn apple_glass_supported(sdk_int: u32) -> bool {
    sdk_int >= ANDROID_13_SDK_INT
}

pub fn artwork_corner_dp(apple_music_style: bool) -> f32 {
    if apple_music_style {
        ARTWORK_CORNER_APPLE_DP
    } else {
        ARTWORK_CORNER_DEFAULT_DP
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GlassMode {
    Backdrop,
    HazeFallback,
}

pub fn glass_mode(sdk_int: u32, has_backdrop: bool) -> GlassMode {
    if has_backdrop && apple_glass_supported(sdk_int) {
        GlassMode::Backdrop
    } else {
        GlassMode::HazeFallback
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GlassInputs {
    pub is_dark: bool,
    pub pink: bool,
    pub tint_alpha: Option<f32>,
    pub lens_enabled: bool,
    pub surface_min_dimension_px: f32,
    pub density: f32,
}

/// The two numeric arguments and the flag the Kotlin code passes positionally to Kyant's
/// `lens(...)`; their meaning is defined by that library and is not verified here.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LensParams {
    pub arg0_px: f32,
    pub arg1_px: f32,
    pub arg2_flag: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GlassParams {
    pub tint: Rgba,
    pub pink_overlay: Option<Rgba>,
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    pub blur_radius_px: f32,
    pub lens: Option<LensParams>,
}

/// Port of the parameter selection inside `Modifier.appleGlass` for the backdrop path.
pub fn resolve_glass_params(inputs: GlassInputs) -> GlassParams {
    let default_tint = if inputs.pink {
        0.38
    } else if inputs.is_dark {
        0.22
    } else {
        0.28
    };
    let tint_alpha = inputs.tint_alpha.unwrap_or(default_tint);
    let tint_color = if inputs.pink {
        Rgba::from_argb(PINK_GLASS_TINT_ARGB)
    } else if inputs.is_dark {
        Rgba::BLACK
    } else {
        Rgba::WHITE
    };

    let lens = inputs.lens_enabled.then(|| LensParams {
        arg0_px: (inputs.surface_min_dimension_px / 4.0).min(24.0 * inputs.density),
        arg1_px: (inputs.surface_min_dimension_px / 2.0).min(32.0 * inputs.density),
        arg2_flag: false,
    });

    GlassParams {
        tint: tint_color.with_alpha(tint_alpha),
        pink_overlay: inputs.pink.then(|| Rgba::WHITE.with_alpha(0.12)),
        brightness: if inputs.pink { 0.1 } else { 0.05 },
        contrast: 1.0,
        saturation: if inputs.pink { 1.05 } else { 1.5 },
        blur_radius_px: 10.0 * inputs.density,
        lens,
    }
}

/// Haze fallback style (used before Android 13 and where no backdrop source exists).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HazeStyleSpec {
    pub background_argb: u32,
    pub tints: &'static [(u32, f32)],
    pub blur_radius_dp: f32,
    pub noise_factor: f32,
}

pub const PURE_BLACK_HAZE: HazeStyleSpec = HazeStyleSpec {
    background_argb: 0xFF00_0000,
    tints: &[(0xFF00_0000, 0.75)],
    blur_radius_dp: 20.0,
    noise_factor: 0.15,
};

pub const PINK_LILY_NAV_HAZE: HazeStyleSpec = HazeStyleSpec {
    background_argb: 0xFFFF_F1F6,
    tints: &[(0xFFFF_D3E2, 0.62), (0xFFFF_FFFF, 0.14)],
    blur_radius_dp: 32.0,
    noise_factor: 0.03,
};

pub const PINK_LILY_HAZE: HazeStyleSpec = HazeStyleSpec {
    background_argb: 0xFFFF_F1F6,
    tints: &[(0xFFFF_D0E0, 0.40), (0xFFFF_FFFF, 0.16)],
    blur_radius_dp: 20.0,
    noise_factor: 0.04,
};

/// White alpha stops of the glass edge stroke gradient, start to end.
pub const GLASS_STROKE_ALPHAS: [f32; 3] = [0.55, 0.06, 0.28];

#[cfg(test)]
mod tests {
    use super::*;

    fn inputs() -> GlassInputs {
        GlassInputs {
            is_dark: true,
            pink: false,
            tint_alpha: None,
            lens_enabled: true,
            surface_min_dimension_px: 400.0,
            density: 3.0,
        }
    }

    #[test]
    fn dark_default_matches_kotlin_constants() {
        let params = resolve_glass_params(inputs());
        assert_eq!(params.tint, Rgba::BLACK.with_alpha(0.22));
        assert_eq!((params.brightness, params.contrast, params.saturation), (0.05, 1.0, 1.5));
        assert_eq!(params.blur_radius_px, 30.0);
        assert!(params.pink_overlay.is_none());
    }

    #[test]
    fn light_and_pink_variants() {
        let light = resolve_glass_params(GlassInputs { is_dark: false, ..inputs() });
        assert_eq!(light.tint, Rgba::WHITE.with_alpha(0.28));

        let pink = resolve_glass_params(GlassInputs { pink: true, ..inputs() });
        assert_eq!(pink.tint.a, 0.38);
        assert_eq!(pink.tint, Rgba::from_argb(PINK_GLASS_TINT_ARGB).with_alpha(0.38));
        assert_eq!(pink.pink_overlay, Some(Rgba::WHITE.with_alpha(0.12)));
        assert_eq!((pink.brightness, pink.saturation), (0.1, 1.05));
    }

    #[test]
    fn explicit_tint_alpha_wins() {
        let params = resolve_glass_params(GlassInputs { tint_alpha: Some(0.6), ..inputs() });
        assert_eq!(params.tint.a, 0.6);
    }

    #[test]
    fn lens_values_clamp_to_surface_and_density() {
        let big = resolve_glass_params(inputs()).lens.unwrap();
        assert_eq!((big.arg0_px, big.arg1_px, big.arg2_flag), (72.0, 96.0, false));

        let small = resolve_glass_params(GlassInputs { surface_min_dimension_px: 100.0, ..inputs() })
            .lens
            .unwrap();
        assert_eq!((small.arg0_px, small.arg1_px), (25.0, 50.0));

        assert!(resolve_glass_params(GlassInputs { lens_enabled: false, ..inputs() }).lens.is_none());
    }

    #[test]
    fn mode_and_artwork_helpers() {
        assert_eq!(glass_mode(33, true), GlassMode::Backdrop);
        assert_eq!(glass_mode(32, true), GlassMode::HazeFallback);
        assert_eq!(glass_mode(34, false), GlassMode::HazeFallback);
        assert_eq!(artwork_corner_dp(true), 8.0);
        assert_eq!(artwork_corner_dp(false), 3.0);
    }
}
