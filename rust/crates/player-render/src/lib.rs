//! wgpu port of the Wavlin player background (Blur and Gradient styles from `Player.kt`) drawn
//! into the backdrop that `wavlin-glass-render` panels sample. Text, controls, the artwork card
//! and the video canvas stay in the host UI; panel rectangles and corner radii are inputs.
//!
//! Not built into the Android app yet: there is no surface, JNI or Compose integration here.

pub mod background;
pub mod renderer;
pub mod shaders;

pub use background::PlayerBackgroundStyle;
pub use renderer::{Artwork, BackgroundLayer, LayerContent, PlayerError, PlayerFrame, PlayerRenderer, MAX_LAYERS};

#[cfg(test)]
mod shader_tests {
    #[test]
    fn player_shader_parses_and_validates() {
        let src = crate::shaders::player_source();
        let module = naga::front::wgsl::parse_str(&src)
            .unwrap_or_else(|e| panic!("player.wgsl failed to parse:\n{}", e.emit_to_string(&src)));
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
            .validate(&module)
            .expect("player.wgsl failed validation");
        let names: Vec<_> = module.entry_points.iter().map(|e| e.name.as_str()).collect();
        for want in ["vs_full", "fs_crop", "fs_gradient", "fs_blur_layer", "fs_blit"] {
            assert!(names.contains(&want), "missing entry point {want}");
        }
    }
}
