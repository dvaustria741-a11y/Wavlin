//! Pure, GPU-free parts of Wavlin's Apple Music interface style and the player artwork colour
//! extraction, ported from `AppleGlass.kt`, `GlassEffect.kt` and `PlayerColorExtractor.kt`.
//! Not wired into the Android app yet. The blur, vibrancy and lens passes themselves are GPU
//! work done by Kyant backdrop / Haze on the Android side and are not part of this crate.
//! `hsv_to_color` and `color_to_hsv` follow Skia's algorithm as reimplemented here; they have not
//! been compared against output from a device.

mod color;
mod glass;

pub use color::{
    color_to_hsv, extract_gradient_colors, hsv_to_color, Palette, Rgba, Swatch,
};
pub use glass::{
    apple_glass_supported, artwork_corner_dp, glass_mode, resolve_glass_params, GlassInputs,
    GlassMode, GlassParams, HazeStyleSpec, LensParams, ANDROID_13_SDK_INT,
    ARTWORK_CORNER_APPLE_DP, ARTWORK_CORNER_DEFAULT_DP, GLASS_STROKE_ALPHAS, PINK_GLASS_TINT_ARGB,
    PINK_LILY_HAZE, PINK_LILY_NAV_HAZE, PURE_BLACK_HAZE,
};
