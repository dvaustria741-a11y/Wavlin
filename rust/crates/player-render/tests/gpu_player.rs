//! Renders player backgrounds on whatever wgpu adapter exists and compares with the CPU reference
//! in `wavlin_player_render::background`. Missing adapter: SKIPPED, unless WAVLIN_REQUIRE_GPU is set.

use std::sync::mpsc;
use wavlin_glass_render::math::ColorMatrix;
use wavlin_glass_render::{BackdropSpec, FrameSpec, LensSpec, PanelSpec};
use wavlin_player_render::background::{gradient_color_at, gradient_stops, layer_over_base};
use wavlin_player_render::{Artwork, BackgroundLayer, LayerContent, PlayerFrame, PlayerRenderer};
use wavlin_ui_style::Rgba;

const W: u32 = 128;
const H: u32 = 128;
const TOL: f32 = 3.5 / 255.0;

fn gpu() -> Option<(wgpu::Device, wgpu::Queue)> {
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor::default());
    let Some(adapter) = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default())) else {
        assert!(std::env::var("WAVLIN_REQUIRE_GPU").is_err(), "no wgpu adapter and WAVLIN_REQUIRE_GPU is set");
        eprintln!("SKIPPED: no wgpu adapter available");
        return None;
    };
    let (device, queue) =
        pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default(), None)).expect("request_device");
    Some((device, queue))
}

fn render(device: &wgpu::Device, queue: &wgpu::Queue, frame: &PlayerFrame<'_>) -> Vec<u8> {
    let size = wgpu::Extent3d { width: W, height: H, depth_or_array_layers: 1 };
    let target = device.create_texture(&wgpu::TextureDescriptor {
        label: None,
        size,
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let view = target.create_view(&Default::default());
    let mut renderer = PlayerRenderer::new(device, wgpu::TextureFormat::Rgba8Unorm);
    let mut encoder = device.create_command_encoder(&Default::default());
    renderer.render(device, &mut encoder, &view, frame).expect("render");
    let buf = device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
        size: u64::from(W * H * 4),
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    encoder.copy_texture_to_buffer(
        wgpu::ImageCopyTexture { texture: &target, mip_level: 0, origin: wgpu::Origin3d::ZERO, aspect: wgpu::TextureAspect::All },
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

fn at(img: &[u8], x: u32, y: u32) -> [f32; 3] {
    let i = ((y * W + x) * 4) as usize;
    [img[i], img[i + 1], img[i + 2]].map(|v| f32::from(v) / 255.0)
}

fn frame<'a>(layers: &'a [BackgroundLayer<'a>], panels: Vec<PanelSpec>, dark: bool) -> PlayerFrame<'a> {
    PlayerFrame {
        size: (W, H),
        density: 1.0,
        dark_theme: dark,
        base_color: Rgba { r: 0.2, g: 0.25, b: 0.3, a: 1.0 },
        layers,
        glass: FrameSpec { backdrop: BackdropSpec { filters: [ColorMatrix::IDENTITY; 2], blur_sigma_px: 0.0 }, panels },
    }
}

const BASE: [f32; 3] = [0.2, 0.25, 0.3];

#[test]
fn three_stop_gradient_matches_reference() {
    let Some((device, queue)) = gpu() else { return };
    let colors = [
        Rgba { r: 0.9, g: 0.2, b: 0.3, a: 1.0 },
        Rgba { r: 0.2, g: 0.6, b: 0.4, a: 1.0 },
        Rgba { r: 0.1, g: 0.1, b: 0.5, a: 1.0 },
    ];
    let layers = [BackgroundLayer { content: LayerContent::Gradient { colors: &colors }, alpha: 0.8 }];
    let img = render(&device, &queue, &frame(&layers, vec![], true));
    let stops = gradient_stops(&colors).unwrap();
    for y in [2u32, 30, 64, 100, 125] {
        let t = (y as f32 + 0.5) / H as f32;
        let want = layer_over_base(gradient_color_at(&stops, t), 0.2, 0.8, BASE);
        let got = at(&img, 60, y);
        for k in 0..3 {
            assert!((got[k] - want[k]).abs() < TOL, "row {y} ch{k}: got {} want {}", got[k], want[k]);
        }
    }
}

#[test]
fn single_colour_gradient_uses_the_fallback_stops() {
    let Some((device, queue)) = gpu() else { return };
    let colors = [Rgba { r: 0.8, g: 0.4, b: 0.1, a: 1.0 }];
    let layers = [BackgroundLayer { content: LayerContent::Gradient { colors: &colors }, alpha: 1.0 }];
    let img = render(&device, &queue, &frame(&layers, vec![], true));
    let stops = gradient_stops(&colors).unwrap();
    for y in [5u32, 70, 90, 120] {
        let t = (y as f32 + 0.5) / H as f32;
        let want = layer_over_base(gradient_color_at(&stops, t), 0.2, 1.0, BASE);
        let got = at(&img, 10, y);
        for k in 0..3 {
            assert!((got[k] - want[k]).abs() < TOL, "row {y} ch{k}: got {} want {}", got[k], want[k]);
        }
    }
}

#[test]
fn no_layers_shows_only_the_sheet_colour() {
    let Some((device, queue)) = gpu() else { return };
    let img = render(&device, &queue, &frame(&[], vec![], false));
    let got = at(&img, 50, 50);
    for k in 0..3 {
        assert!((got[k] - BASE[k]).abs() < 1.5 / 255.0);
    }
}

#[test]
fn blur_layer_flat_artwork_gets_the_overlay_only() {
    let Some((device, queue)) = gpu() else { return };
    let px: Vec<u8> = (0..100 * 100).flat_map(|_| [200u8, 100, 50, 255]).collect();
    let art = Artwork::from_rgba8(&device, &queue, 100, 100, &px).unwrap();
    let layers = [BackgroundLayer { content: LayerContent::Blur { artwork: &art }, alpha: 1.0 }];
    let img = render(&device, &queue, &frame(&layers, vec![], true));
    let want = layer_over_base([200.0 / 255.0, 100.0 / 255.0, 50.0 / 255.0, 1.0], 0.3, 1.0, BASE);
    for (x, y) in [(5u32, 5u32), (64, 64), (120, 100)] {
        let got = at(&img, x, y);
        for k in 0..3 {
            assert!((got[k] - want[k]).abs() < TOL, "({x},{y}) ch{k}: got {} want {}", got[k], want[k]);
        }
    }
}

#[test]
fn blur_layer_mixes_a_two_colour_artwork() {
    let Some((device, queue)) = gpu() else { return };
    let mut px = Vec::new();
    for _y in 0..100 {
        for x in 0..100 {
            px.extend_from_slice(if x < 50 { &[255u8, 0, 0, 255] } else { &[0u8, 0, 255, 255] });
        }
    }
    let art = Artwork::from_rgba8(&device, &queue, 100, 100, &px).unwrap();
    let layers = [BackgroundLayer { content: LayerContent::Blur { artwork: &art }, alpha: 1.0 }];
    let img = render(&device, &queue, &frame(&layers, vec![], false));
    // Reference: Gaussian blur (sigma = 100 dp / 8 at density 1, clamped edges) of the 16x16 small
    // layer the Kotlin code blurs, then bilinear upscale to 128 px. The GPU path blurs a further
    // downsampled copy, so a small difference is expected; it is measured here, not assumed.
    let small = 16usize;
    let sigma = 100.0f32 / 8.0;
    let red_at = |i: i32| if i.clamp(0, small as i32 - 1) < 8 { 1.0f32 } else { 0.0 };
    let radius = (sigma * 3.0).ceil() as i32;
    let blurred: Vec<f32> = (0..small as i32)
        .map(|c| {
            let (mut acc, mut wsum) = (0.0, 0.0);
            for d in -radius..=radius {
                let w = (-0.5 * (d as f32 / sigma).powi(2)).exp();
                acc += w * red_at(c + d);
                wsum += w;
            }
            acc / wsum
        })
        .collect();
    let sample = |x: u32| {
        let pos = (x as f32 + 0.5) / W as f32 * small as f32 - 0.5;
        let i0 = pos.floor();
        let f = pos - i0;
        let at_i = |i: f32| blurred[(i as i32).clamp(0, small as i32 - 1) as usize];
        at_i(i0) * (1.0 - f) + at_i(i0 + 1.0) * f
    };
    let mut worst = 0.0f32;
    for x in [2u32, 20, 64, 100, 125] {
        let red = sample(x);
        let want = layer_over_base([red, 0.0, 1.0 - red, 1.0], 0.3, 1.0, BASE);
        let got = at(&img, x, 64);
        for k in 0..3 {
            worst = worst.max((got[k] - want[k]).abs());
        }
    }
    eprintln!("blur layer worst error vs CPU reference: {worst:.4}");
    assert!(worst < 0.05, "worst error {worst}");
}

#[test]
fn glass_panel_samples_the_background_it_sits_on() {
    let Some((device, queue)) = gpu() else { return };
    let colors = [Rgba { r: 0.9, g: 0.9, b: 0.1, a: 1.0 }; 3];
    let layers = [BackgroundLayer { content: LayerContent::Gradient { colors: &colors }, alpha: 1.0 }];
    let mut panel = PanelSpec {
        rect: [20.0, 40.0, 88.0, 48.0],
        corner_radii_px: [24.0; 4],
        tint: [0.0, 0.0, 0.0, 0.5],
        overlay: None,
        lens: None,
        highlight: None,
        shadow: None,
    };
    panel.lens = Some(LensSpec { refraction_height_px: 8.0, refraction_amount_px: 4.0, depth_effect: false, chromatic_aberration: false });
    let img = render(&device, &queue, &frame(&layers, vec![panel], true));
    let outside = at(&img, 5, 5);
    let inside = at(&img, 64, 64);
    // Outside: the gradient over the base. Inside: the same colour halved by the 0.5 black tint.
    for k in 0..3 {
        assert!((inside[k] - outside[k] * 0.5).abs() < TOL, "ch{k}: inside {} outside {}", inside[k], outside[k]);
    }
}
