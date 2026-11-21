//! wgpu port of the Kyant backdrop glass pipeline as Wavlin's `Modifier.appleGlass` uses it.
//!
//! Sources ported: Kyant backdrop 2.0.1 (Apache-2.0, https://github.com/Kyant0/AndroidLiquidGlass,
//! tag 2.0.1): `Shaders.kt`, `Lens.kt`, `ColorFilter.kt`, `HighlightStyle.kt`, `Highlight.kt`,
//! `Shadow.kt`, `DrawBackdropModifier.kt`; and Wavlin's `AppleGlass.kt`. Parameter values are not
//! changed: they come from `wavlin-ui-style`, which carries the numbers from the Kotlin files.
//!
//! Not built into the Android app yet: nothing here creates a surface or talks to Compose.

pub mod gpu;
pub mod math;
pub mod shaders;
pub mod spec;

pub use gpu::{GlassError, GlassRenderer, MAX_PANELS};
pub use spec::{
    BackdropSpec, FrameSpec, HighlightSpec, HighlightStyleSpec, LensSpec, PanelSpec, ShadowSpec,
};

#[cfg(test)]
mod shader_tests {
    use crate::shaders::{panel_source, post_source};

    fn validate(label: &str, src: &str) -> naga::Module {
        let module = naga::front::wgsl::parse_str(src)
            .unwrap_or_else(|e| panic!("{label} failed to parse:\n{}", e.emit_to_string(src)));
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
            .validate(&module)
            .unwrap_or_else(|e| panic!("{label} failed validation: {e:?}"));
        module
    }

    #[test]
    fn post_shader_parses_and_validates() {
        let m = validate("post.wgsl", &post_source());
        let names: Vec<_> = m.entry_points.iter().map(|e| e.name.as_str()).collect();
        for want in ["vs_full", "fs_color", "fs_color_ds2", "fs_ds2", "fs_blur"] {
            assert!(names.contains(&want), "missing entry point {want}");
        }
    }

    #[test]
    fn panel_shader_parses_and_validates() {
        let m = validate("panel.wgsl", &panel_source());
        let names: Vec<_> = m.entry_points.iter().map(|e| e.name.as_str()).collect();
        for want in ["vs_glass", "vs_shadow", "fs_glass", "fs_shadow"] {
            assert!(names.contains(&want), "missing entry point {want}");
        }
    }
}
