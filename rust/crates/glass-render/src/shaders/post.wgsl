// Backdrop preparation: colour filters (+ optional 2x downsample) and separable Gaussian blur.
// Filter order matches the effect chain in AppleGlass.kt: vibrancy(), colorControls(), blur().
// Sampler is clamp-to-edge, matching TileMode.Clamp.

struct PostParams {
    m0: mat4x4<f32>,
    o0: vec4<f32>,
    m1: mat4x4<f32>,
    o1: vec4<f32>,
    step_uv: vec2<f32>,   // blur: direction * (1 / texture size)
    sigma: f32,           // blur: Gaussian sigma in texels of the source texture
    radius: i32,          // blur: taps on each side of the centre
};

@group(0) @binding(0) var<uniform> params: PostParams;
@group(0) @binding(1) var samp: sampler;
@group(0) @binding(2) var src: texture_2d<f32>;

struct VOut {
    @builtin(position) pos: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_full(@builtin(vertex_index) i: u32) -> VOut {
    let uv = vec2<f32>(f32((i << 1u) & 2u), f32(i & 2u));
    var out: VOut;
    out.pos = vec4<f32>(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
    out.uv = uv;
    return out;
}

fn apply_filters(c: vec4<f32>) -> vec4<f32> {
    let a = clamp(params.m0 * c + params.o0, vec4<f32>(0.0), vec4<f32>(1.0));
    return clamp(params.m1 * a + params.o1, vec4<f32>(0.0), vec4<f32>(1.0));
}

// Source and destination have the same size.
@fragment
fn fs_color(in: VOut) -> @location(0) vec4<f32> {
    return apply_filters(textureSampleLevel(src, samp, in.uv, 0.0));
}

// Destination is half the source size: one bilinear tap on the shared corner is the exact 2x2 mean
// for even source sizes.
@fragment
fn fs_color_ds2(in: VOut) -> @location(0) vec4<f32> {
    return apply_filters(textureSampleLevel(src, samp, in.uv, 0.0));
}

@fragment
fn fs_ds2(in: VOut) -> @location(0) vec4<f32> {
    return textureSampleLevel(src, samp, in.uv, 0.0);
}

@fragment
fn fs_blur(in: VOut) -> @location(0) vec4<f32> {
    var acc = vec4<f32>(0.0);
    var wsum = 0.0;
    let inv = 1.0 / (params.sigma * params.sigma);
    for (var i = -params.radius; i <= params.radius; i = i + 1) {
        let fi = f32(i);
        let w = exp(-0.5 * fi * fi * inv);
        acc = acc + w * textureSampleLevel(src, samp, in.uv + fi * params.step_uv, 0.0);
        wsum = wsum + w;
    }
    return acc / wsum;
}
