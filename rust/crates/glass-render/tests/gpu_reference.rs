//! Renders real frames on whatever wgpu adapter exists and compares them with the CPU reference in
//! `wavlin_glass_render::math`. Without an adapter the tests print a SKIPPED line and pass, unless
//! `WAVLIN_REQUIRE_GPU=1` is set, in which case a missing adapter fails the test.

use std::sync::mpsc;
use wavlin_glass_render::math::{
    blurred_edge_coverage, default_highlight_intensity, highlight_ring, lens_sample_pos, radius_at,
    sd_rounded_rect, ColorMatrix,
};
use wavlin_glass_render::spec::{BackdropSpec, FrameSpec, HighlightSpec, LensSpec, PanelSpec, ShadowSpec};
use wavlin_glass_render::GlassRenderer;

const W: u32 = 256;
const H: u32 = 128;

fn gpu() -> Option<(wgpu::Device, wgpu::Queue)> {
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor::default());
    let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()));
    let Some(adapter) = adapter else {
        assert!(std::env::var("WAVLIN_REQUIRE_GPU").is_err(), "no wgpu adapter and WAVLIN_REQUIRE_GPU is set");
        eprintln!("SKIPPED: no wgpu adapter available");
        return None;
    };
    eprintln!("adapter: {:?}", adapter.get_info());
    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default(), None))
        .expect("request_device");
    Some((device, queue))
}

fn render(device: &wgpu::Device, queue: &wgpu::Queue, backdrop_px: &[u8], frame: &FrameSpec) -> Vec<u8> {
    let size = wgpu::Extent3d { width: W, height: H, depth_or_array_layers: 1 };
    let mk = |usage| {
        device.create_texture(&wgpu::TextureDescriptor {
            label: None,
            size,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage,
            view_formats: &[],
        })
    };
    let backdrop = mk(wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST);
    let target = mk(wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC);
    queue.write_texture(
        wgpu::ImageCopyTexture {
            texture: &backdrop,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        backdrop_px,
        wgpu::ImageDataLayout { offset: 0, bytes_per_row: Some(W * 4), rows_per_image: Some(H) },
        size,
    );
    let bview = backdrop.create_view(&Default::default());
    let tview = target.create_view(&Default::default());

    let mut renderer = GlassRenderer::new(device, wgpu::TextureFormat::Rgba8Unorm);
    let mut encoder = device.create_command_encoder(&Default::default());
    renderer.render(device, &mut encoder, &bview, (W, H), &tview, (W, H), frame).expect("render");

    let buf = device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
        size: u64::from(W * H * 4),
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    encoder.copy_texture_to_buffer(
        wgpu::ImageCopyTexture {
            texture: &target,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        wgpu::ImageCopyBuffer {
            buffer: &buf,
            layout: wgpu::ImageDataLayout { offset: 0, bytes_per_row: Some(W * 4), rows_per_image: Some(H) },
        },
        size,
    );
    queue.submit([encoder.finish()]);
    let slice = buf.slice(..);
    let (tx, rx) = mpsc::channel();
    slice.map_async(wgpu::MapMode::Read, move |r| tx.send(r).unwrap());
    device.poll(wgpu::Maintain::Wait);
    rx.recv().unwrap().unwrap();
    let out = slice.get_mapped_range().to_vec();
    out
}

/// Horizontal ramp in red, vertical ramp in green, constant blue. Texel centres hold exactly the
/// continuous function, so bilinear sampling reproduces it up to 8-bit rounding.
fn ramp_backdrop() -> Vec<u8> {
    let mut px = Vec::with_capacity((W * H * 4) as usize);
    for y in 0..H {
        for x in 0..W {
            px.push((((x as f32 + 0.5) / W as f32) * 255.0).round() as u8);
            px.push((((y as f32 + 0.5) / H as f32) * 255.0).round() as u8);
            px.push(128);
            px.push(255);
        }
    }
    px
}
fn ramp(p: [f32; 2]) -> [f32; 3] {
    [p[0] / W as f32, p[1] / H as f32, 128.0 / 255.0]
}
fn flat_backdrop(rgb: [u8; 3]) -> Vec<u8> {
    (0..W * H).flat_map(|_| [rgb[0], rgb[1], rgb[2], 255]).collect()
}
fn at(img: &[u8], x: u32, y: u32) -> [f32; 4] {
    let i = ((y * W + x) * 4) as usize;
    [img[i], img[i + 1], img[i + 2], img[i + 3]].map(|v| f32::from(v) / 255.0)
}

fn identity_backdrop(blur: f32) -> BackdropSpec {
    BackdropSpec { filters: [ColorMatrix::IDENTITY; 2], blur_sigma_px: blur }
}
fn bare_panel(rect: [f32; 4], radius: f32) -> PanelSpec {
    PanelSpec {
        rect,
        corner_radii_px: [radius; 4],
        tint: [0.0; 4],
        overlay: None,
        lens: None,
        highlight: None,
        shadow: None,
    }
}

const RECT: [f32; 4] = [40.0, 20.0, 176.0, 88.0];
const TOL: f32 = 3.5 / 255.0;

#[test]
fn lens_and_tint_match_cpu_reference() {
    let Some((device, queue)) = gpu() else { return };
    let mut panel = bare_panel(RECT, 44.0);
    panel.tint = [0.0, 0.0, 0.0, 0.25];
    panel.lens = Some(LensSpec {
        refraction_height_px: 20.0,
        refraction_amount_px: 12.0,
        depth_effect: false,
        chromatic_aberration: false,
    });
    let frame = FrameSpec { backdrop: identity_backdrop(0.0), panels: vec![panel] };
    let img = render(&device, &queue, &ramp_backdrop(), &frame);

    let center = [RECT[0] + RECT[2] / 2.0, RECT[1] + RECT[3] / 2.0];
    let half = [RECT[2] / 2.0, RECT[3] / 2.0];
    let (mut checked, mut moved, mut worst) = (0, 0, 0.0f32);
    for y in 0..H {
        for x in 0..W {
            let p = [x as f32 + 0.5, y as f32 + 0.5];
            let c = [p[0] - center[0], p[1] - center[1]];
            let sd = sd_rounded_rect(c, half, radius_at(c, [44.0; 4]));
            if sd > -1.5 {
                continue; // skip the anti-aliased edge
            }
            let s = lens_sample_pos(p, center, half, [44.0; 4], 20.0, 12.0, false);
            if s != p {
                moved += 1;
            }
            let want = ramp(s).map(|v| v * 0.75);
            let got = at(&img, x, y);
            for k in 0..3 {
                worst = worst.max((got[k] - want[k]).abs());
            }
            assert!((got[3] - 1.0).abs() < 1.0 / 255.0, "alpha at ({x},{y}) = {}", got[3]);
            checked += 1;
        }
    }
    eprintln!("lens: checked {checked} pixels, {moved} refracted, worst error {worst:.4}");
    assert!(moved > 500, "the refraction band should cover many pixels");
    assert!(worst < TOL, "worst channel error {worst}");
}

#[test]
fn outside_the_panel_stays_untouched() {
    let Some((device, queue)) = gpu() else { return };
    let frame = FrameSpec { backdrop: identity_backdrop(0.0), panels: vec![bare_panel(RECT, 44.0)] };
    let img = render(&device, &queue, &ramp_backdrop(), &frame);
    assert_eq!(at(&img, 5, 5), [0.0; 4]);
    assert_eq!(at(&img, 250, 120), [0.0; 4]);
    let inside = at(&img, 128, 64);
    assert!(inside[3] > 0.99);
}

#[test]
fn color_filters_and_blur_chain_preserve_flat_colours() {
    let Some((device, queue)) = gpu() else { return };
    let src = [153u8, 77, 51];
    let m0 = ColorMatrix::vibrancy();
    let m1 = ColorMatrix::color_controls(0.05, 1.0, 1.5);
    let want = m1.apply(m0.apply([f32::from(src[0]) / 255.0, f32::from(src[1]) / 255.0, f32::from(src[2]) / 255.0, 1.0]));
    // sigma 30 takes the 3-step downsample path, 0 takes the no-blur path, 3 the single-resolution blur.
    for sigma in [0.0f32, 3.0, 30.0] {
        let frame = FrameSpec {
            backdrop: BackdropSpec { filters: [m0, m1], blur_sigma_px: sigma },
            panels: vec![bare_panel(RECT, 44.0)],
        };
        let img = render(&device, &queue, &flat_backdrop(src), &frame);
        let got = at(&img, 128, 64);
        for k in 0..3 {
            assert!((got[k] - want[k]).abs() < TOL, "sigma {sigma}: channel {k} got {} want {}", got[k], want[k]);
        }
    }
}

#[test]
fn blur_of_a_linear_ramp_is_the_ramp_away_from_the_edges() {
    let Some((device, queue)) = gpu() else { return };
    for sigma in [3.0f32, 12.0, 30.0] {
        let frame = FrameSpec { backdrop: identity_backdrop(sigma), panels: vec![bare_panel(RECT, 44.0)] };
        let img = render(&device, &queue, &ramp_backdrop(), &frame);
        // Horizontal: far enough from both x edges that clamping cannot matter (> 3 sigma is not
        // possible for sigma 30 on 256 px, so only the vertical ramp is checked there).
        let got = at(&img, 128, 64);
        let want = ramp([128.5, 64.5]);
        assert!((got[1] - want[1]).abs() < 0.03, "sigma {sigma}: green got {} want {}", got[1], want[1]);
        if sigma <= 12.0 {
            assert!((got[0] - want[0]).abs() < 0.03, "sigma {sigma}: red got {} want {}", got[0], want[0]);
        }
    }
}

#[test]
fn shadow_matches_cpu_reference_and_is_clear_inside() {
    let Some((device, queue)) = gpu() else { return };
    let mut panel = bare_panel(RECT, 44.0);
    panel.shadow = Some(ShadowSpec { radius_px: 16.0, offset_px: [0.0, 6.0], color: [0.0, 0.0, 0.0, 0.5], alpha: 1.0 });
    let frame = FrameSpec { backdrop: identity_backdrop(0.0), panels: vec![panel] };
    let img = render(&device, &queue, &ramp_backdrop(), &frame);

    let sigma = wavlin_glass_render::math::android_blur_mask_radius_to_sigma(16.0);
    let center = [RECT[0] + RECT[2] / 2.0, RECT[1] + RECT[3] / 2.0];
    let half = [RECT[2] / 2.0, RECT[3] / 2.0];
    let mut worst = 0.0f32;
    for (x, y) in [(128u32, 112u32), (128, 118), (128, 125), (60, 112), (30, 64), (200, 116)] {
        let c = [x as f32 + 0.5 - center[0], y as f32 + 0.5 - center[1]];
        let sd_shape = sd_rounded_rect(c, half, radius_at(c, [44.0; 4]));
        let c_off = [c[0], c[1] - 6.0];
        let sd_off = sd_rounded_rect(c_off, half, radius_at(c_off, [44.0; 4]));
        let keep = 1.0 - (0.5 - sd_shape).clamp(0.0, 1.0);
        let want_a = 0.5 * blurred_edge_coverage(sd_off, sigma) * keep;
        let got = at(&img, x, y);
        worst = worst.max((got[3] - want_a).abs());
        assert!((got[3] - want_a).abs() < TOL, "({x},{y}): alpha got {} want {want_a}", got[3]);
    }
    eprintln!("shadow worst alpha error {worst:.4}");
    // Inside the shape there is no shadow, only the glass itself (opaque backdrop copy).
    let inside = at(&img, 128, 64);
    assert!(inside[3] > 0.99 && inside[0] > 0.0);
}

#[test]
fn highlight_matches_cpu_reference() {
    let Some((device, queue)) = gpu() else { return };
    let density = 3.0;
    let mut panel = bare_panel(RECT, 44.0);
    panel.highlight = Some(HighlightSpec::kyant_default(density));
    let frame = FrameSpec { backdrop: identity_backdrop(0.0), panels: vec![panel] };
    let img = render(&device, &queue, &ramp_backdrop(), &frame);

    let center = [RECT[0] + RECT[2] / 2.0, RECT[1] + RECT[3] / 2.0];
    let half = [RECT[2] / 2.0, RECT[3] / 2.0];
    let sigma = wavlin_glass_render::math::android_blur_mask_radius_to_sigma(0.75);
    let mut best = 0.0f32;
    for (x, y) in [(128u32, 21u32), (128, 22), (215, 64), (41, 64), (128, 106), (60, 28), (200, 100)] {
        let p = [x as f32 + 0.5, y as f32 + 0.5];
        let c = [p[0] - center[0], p[1] - center[1]];
        let radii = [44.0; 4];
        let sd = sd_rounded_rect(c, half, radius_at(c, radii));
        if sd > -0.5 {
            continue;
        }
        let i = default_highlight_intensity(c, half, radii, 45f32.to_radians(), 1.0);
        let ring = highlight_ring(sd, 2.0, sigma);
        let add = 0.5 * i * ring;
        let base = ramp(p);
        let got = at(&img, x, y);
        for k in 0..3 {
            let want = (base[k] + add).min(1.0);
            assert!((got[k] - want).abs() < TOL, "({x},{y}) ch{k}: got {} want {want}", got[k]);
        }
        best = best.max(add);
    }
    assert!(best > 0.05, "at least one probe should land on a visibly lit edge, best add {best}");
}

#[test]
fn chromatic_aberration_runs_and_differs_from_plain_lens() {
    let Some((device, queue)) = gpu() else { return };
    let lens = |chromatic| {
        let mut p = bare_panel(RECT, 44.0);
        p.lens = Some(LensSpec {
            refraction_height_px: 24.0,
            refraction_amount_px: 16.0,
            depth_effect: false,
            chromatic_aberration: chromatic,
        });
        FrameSpec { backdrop: identity_backdrop(0.0), panels: vec![p] }
    };
    let plain = render(&device, &queue, &ramp_backdrop(), &lens(false));
    let disp = render(&device, &queue, &ramp_backdrop(), &lens(true));
    assert_ne!(plain, disp);
}
