//! WGSL sources. `panel_source` and `post_source` return the complete modules so that tests can
//! validate exactly what the renderer compiles.

const COMMON: &str = include_str!("shaders/common.wgsl");
const POST: &str = include_str!("shaders/post.wgsl");
const PANEL: &str = include_str!("shaders/panel.wgsl");

pub fn post_source() -> String {
    POST.to_owned()
}

pub fn panel_source() -> String {
    format!("{COMMON}\n{PANEL}")
}
