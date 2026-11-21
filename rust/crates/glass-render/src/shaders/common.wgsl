// Rounded-rectangle signed distance helpers. Ported from RoundedRectSDF in Kyant backdrop 2.0.1
// (Shaders.kt, Apache-2.0). Coordinates: pixels, +x right, +y down, origin at the rect centre.
// Deviation from 2.0.1: radius_at receives the centred coordinate, as upstream fixed after 2.0.1
// (2.0.1 passed the uncentred coordinate, which always picked the bottom-right radius).

fn radius_at(centered: vec2<f32>, radii: vec4<f32>) -> f32 {
    if (centered.x >= 0.0) {
        if (centered.y <= 0.0) { return radii.y; }
        return radii.z;
    }
    if (centered.y <= 0.0) { return radii.x; }
    return radii.w;
}

fn sd_rounded_rect(coord: vec2<f32>, half_size: vec2<f32>, radius: f32) -> f32 {
    let corner = abs(coord) - (half_size - vec2<f32>(radius));
    let outside = length(max(corner, vec2<f32>(0.0))) - radius;
    let inside = min(max(corner.x, corner.y), 0.0);
    return outside + inside;
}

fn safe_normalize(v: vec2<f32>) -> vec2<f32> {
    let l = length(v);
    if (l > 1e-8) { return v / l; }
    return vec2<f32>(0.0);
}

fn grad_sd_rounded_rect(coord: vec2<f32>, half_size: vec2<f32>, radius: f32) -> vec2<f32> {
    let corner = abs(coord) - (half_size - vec2<f32>(radius));
    if (corner.x >= 0.0 || corner.y >= 0.0) {
        return sign(coord) * safe_normalize(max(corner, vec2<f32>(0.0)));
    }
    let gx = step(corner.y, corner.x);
    return sign(coord) * vec2<f32>(gx, 1.0 - gx);
}

fn circle_map(x: f32) -> f32 {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

// Abramowitz-Stegun 7.1.26 erf, max abs error 1.5e-7.
fn erf_approx(x: f32) -> f32 {
    let s = sign(x);
    let a = abs(x);
    let t = 1.0 / (1.0 + 0.3275911 * a);
    let poly = ((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592;
    return s * (1.0 - poly * t * exp(-a * a));
}

// Standard normal CDF.
fn phi(x: f32) -> f32 {
    return 0.5 * (1.0 + erf_approx(x * 0.70710678));
}
