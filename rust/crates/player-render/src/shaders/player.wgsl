// Player background layers. Output is premultiplied alpha, drawn with (One, OneMinusSrcAlpha).
// A layer is its content with a black overlay on top (`.background(Color.Black.copy(alpha))`),
// then the whole group is multiplied by the sheet-progress alpha (`Modifier.alpha`).

struct LayerU {
    c0: vec4<f32>,    // gradient colours, straight alpha
    c1: vec4<f32>,
    c2: vec4<f32>,
    pos: vec4<f32>,   // gradient stop positions in x, y, z
    misc: vec4<f32>,  // x: group alpha, y: black overlay alpha
    uv: vec4<f32>,    // crop: uv scale in xy, uv offset in zw
};

@group(0) @binding(0) var<uniform> u: LayerU;
@group(0) @binding(1) var samp: sampler;
@group(0) @binding(2) var tex: texture_2d<f32>;

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

fn layer_out(c: vec4<f32>) -> vec4<f32> {
    let pm = vec4<f32>(c.rgb * c.a, c.a);
    let ao = u.misc.y;
    let g = vec4<f32>(pm.rgb * (1.0 - ao), pm.a * (1.0 - ao) + ao);
    return g * u.misc.x;
}

@fragment
fn fs_crop(in: VOut) -> @location(0) vec4<f32> {
    return textureSampleLevel(tex, samp, in.uv * u.uv.xy + u.uv.zw, 0.0);
}

@fragment
fn fs_gradient(in: VOut) -> @location(0) vec4<f32> {
    let y = in.uv.y;
    var c: vec4<f32>;
    if (y <= u.pos.y) {
        let t = (y - u.pos.x) / max(u.pos.y - u.pos.x, 1e-6);
        c = mix(u.c0, u.c1, clamp(t, 0.0, 1.0));
    } else {
        let t = (y - u.pos.y) / max(u.pos.z - u.pos.y, 1e-6);
        c = mix(u.c1, u.c2, clamp(t, 0.0, 1.0));
    }
    return layer_out(c);
}

@fragment
fn fs_blur_layer(in: VOut) -> @location(0) vec4<f32> {
    return layer_out(textureSampleLevel(tex, samp, in.uv, 0.0));
}

@fragment
fn fs_blit(in: VOut) -> @location(0) vec4<f32> {
    return textureSampleLevel(tex, samp, in.uv, 0.0);
}
