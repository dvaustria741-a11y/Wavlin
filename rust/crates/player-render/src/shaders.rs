const PLAYER: &str = include_str!("shaders/player.wgsl");

pub fn player_source() -> String {
    PLAYER.to_owned()
}
