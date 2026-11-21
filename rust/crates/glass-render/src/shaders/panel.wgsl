// Glass panel passes: drop shadow, then lens + surface tint + highlight. Output is premultiplied
// alpha; draw with blend (One, OneMinusSrcAlpha). Draw order matches Kyant drawBackdrop:
// shadow, backdrop effects + surface, highlight.
// Lens and highlight formulas: Kyant backdrop 2.0.1 Shaders.kt. Shadow and highlight blur are
// analytic Gaussian-edge approximations of Skia's blur mask filter, not pixel-identical.

struct Globals {
    target_size: vec2<f32>,
    srgb_target: f32,
    _pad: f32,
};

struct Panel {
    rect: vec4<f32>,       // x, y, width, height in target pixels
    radii: vec4<f32>,      // top-left, top-right, bottom-right, bottom-left in pixels
    tint: vec4<f32>,       // surface colour, straight alpha
    overlay: vec4<f32>,    // second surface colour (pink overlay), alpha 0 = none
    lens: vec4<f32>,       // refraction height px, refraction amount px, depth effect 0/1, chromatic 0/1
    hl_color: vec4<f32>,   // rgb, alpha = style alpha * highlight alpha
    hl: vec4<f32>,         // half stroke width px, blur sigma px, angle rad, falloff
    hl_mode: vec4<f32>,    // x: 0 off, 1 default style, 2 plain style
    sh_color: vec4<f32>,   // rgb, alpha = colour alpha * shadow alpha
    sh: vec4<f32>,         // sigma px, offset x px, offset y px, enabled 0/1
};

@group(0) @binding(0) var<uniform> globals: Globals;
@group(0) @binding(1) var samp: sampler;
@group(0) @binding(2) var backdrop: texture_2d<f32>;
@group(1) @binding(0) var<uniform> panel: Panel;

fn quad_pos(vi: u32, expand: f32) -> vec4<f32> {
    let corner = vec2<f32>(f32(vi & 1u), f32((vi >> 1u) & 1u));
    let p0 = panel.rect.xy - vec2<f32>(expand);
    let p1 = panel.rect.xy + panel.rect.zw + vec2<f32>(expand);
    let p = mix(p0, p1, corner);
    return vec4<f32>(p.x / globals.target_size.x * 2.0 - 1.0, 1.0 - p.y / globals.target_size.y * 2.0, 0.0, 1.0);
}

@vertex
fn vs_glass(@builtin(vertex_index) vi: u32) -> @builtin(position) vec4<f32> {
    return quad_pos(vi, 1.0);
}

@vertex
fn vs_shadow(@builtin(vertex_index) vi: u32) -> @builtin(position) vec4<f32> {
    let reach = 3.0 * panel.sh.x + max(abs(panel.sh.y), abs(panel.sh.z)) + 1.0;
    return quad_pos(vi, reach);
}

fn srgb_to_linear(c: vec3<f32>) -> vec3<f32> {
    let lo = c / 12.92;
    let hi = pow((c + vec3<f32>(0.055)) / 1.055, vec3<f32>(2.4));
    return select(hi, lo, c <= vec3<f32>(0.04045));
}

fn encode_out(rgb: vec3<f32>) -> vec3<f32> {
    if (globals.srgb_target > 0.5) { return srgb_to_linear(rgb); }
    return rgb;
}

fn fetch(px: vec2<f32>) -> vec4<f32> {
    return textureSampleLevel(backdrop, samp, px / globals.target_size, 0.0);
}

@fragment
fn fs_shadow(@builtin(position) pos: vec4<f32>) -> @location(0) vec4<f32> {
    let half_size = panel.rect.zw * 0.5;
    let c = pos.xy - panel.rect.xy - half_size;
    let off = vec2<f32>(panel.sh.y, panel.sh.z);
    let sigma = max(panel.sh.x, 1e-3);
    let c_off = c - off;
    let sd_off = sd_rounded_rect(c_off, half_size, radius_at(c_off, panel.radii));
    let sd_shape = sd_rounded_rect(c, half_size, radius_at(c, panel.radii));
    let blurred = phi(-sd_off / sigma);
    let keep_outside = 1.0 - clamp(0.5 - sd_shape, 0.0, 1.0);
    let a = panel.sh_color.a * blurred * keep_outside * panel.sh.w;
    return vec4<f32>(encode_out(panel.sh_color.rgb) * a, a);
}

@fragment
fn fs_glass(@builtin(position) pos: vec4<f32>) -> @location(0) vec4<f32> {
    let p = pos.xy;
    let half_size = panel.rect.zw * 0.5;
    let c = p - panel.rect.xy - half_size;
    let radius = radius_at(c, panel.radii);
    let sd = sd_rounded_rect(c, half_size, radius);
    let cov = clamp(0.5 - sd, 0.0, 1.0);
    if (cov <= 0.0) { discard; }

    let grad_radius = min(radius * 1.5, min(half_size.x, half_size.y));
    let ref_h = panel.lens.x;
    var color = vec4<f32>(0.0);
    if (ref_h > 0.0 && panel.lens.y > 0.0 && -sd < ref_h) {
        let sd_in = min(sd, 0.0);
        let d = circle_map(1.0 - (-sd_in) / ref_h) * (-panel.lens.y);
        let grad = safe_normalize(grad_sd_rounded_rect(c, half_size, grad_radius) + panel.lens.z * safe_normalize(c));
        let refracted = p + d * grad;
        if (panel.lens.w > 0.5) {
            let disp_int = 1.0 * ((c.x * c.y) / (half_size.x * half_size.y));
            let disp = d * grad * disp_int;
            let red = fetch(refracted + disp);
            color.r = color.r + red.r / 3.5;
            color.a = color.a + red.a / 7.0;
            let orange = fetch(refracted + disp * (2.0 / 3.0));
            color.r = color.r + orange.r / 3.5;
            color.g = color.g + orange.g / 7.0;
            color.a = color.a + orange.a / 7.0;
            let yellow = fetch(refracted + disp * (1.0 / 3.0));
            color.r = color.r + yellow.r / 3.5;
            color.g = color.g + yellow.g / 3.5;
            color.a = color.a + yellow.a / 7.0;
            let green = fetch(refracted);
            color.g = color.g + green.g / 3.5;
            color.a = color.a + green.a / 7.0;
            let cyan = fetch(refracted - disp * (1.0 / 3.0));
            color.g = color.g + cyan.g / 3.5;
            color.b = color.b + cyan.b / 3.0;
            color.a = color.a + cyan.a / 7.0;
            let blue = fetch(refracted - disp * (2.0 / 3.0));
            color.b = color.b + blue.b / 3.0;
            color.a = color.a + blue.a / 7.0;
            let purple = fetch(refracted - disp);
            color.r = color.r + purple.r / 7.0;
            color.b = color.b + purple.b / 3.0;
            color.a = color.a + purple.a / 7.0;
        } else {
            color = fetch(refracted);
        }
    } else {
        color = fetch(p);
    }

    // onDrawSurface: tint, then optional overlay, both source-over on the lens output.
    var rgb = mix(color.rgb, panel.tint.rgb, panel.tint.a);
    rgb = mix(rgb, panel.overlay.rgb, panel.overlay.a);

    // Highlight, additive (BlendMode.Plus), clipped to the shape.
    if (panel.hl_mode.x > 0.5) {
        var intensity = 1.0;
        if (panel.hl_mode.x < 1.5) {
            let n = vec2<f32>(cos(panel.hl.z), sin(panel.hl.z));
            let dd = dot(grad_sd_rounded_rect(c, half_size, grad_radius), n);
            intensity = pow(abs(dd), panel.hl.w);
        }
        let s = max(panel.hl.y, 1e-3);
        let hw = panel.hl.x;
        let ring = phi((hw - sd) / s) - phi((-hw - sd) / s);
        rgb = rgb + panel.hl_color.rgb * (panel.hl_color.a * intensity * ring);
    }

    rgb = clamp(rgb, vec3<f32>(0.0), vec3<f32>(1.0));
    return vec4<f32>(encode_out(rgb) * cov, cov);
}
