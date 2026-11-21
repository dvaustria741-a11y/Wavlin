//! wgpu player renderer: background layers (Blur / Gradient) into a backdrop texture, a blit to
//! the target, then the glass panels over it using that backdrop.

use crate::background::{
    blur_radius_dp, blur_sigma_small_px, crop_uv, gradient_stops, small_layer_size, BLUR_DOWNSCALE_FACTOR,
    BLUR_OVERLAY_ALPHA, GRADIENT_OVERLAY_ALPHA,
};
use bytemuck::{Pod, Zeroable};
use std::mem::size_of;
use wavlin_glass_render::math::ColorMatrix;
use wavlin_glass_render::{clamp_linear_sampler, BackdropFilter, FrameSpec, GlassError, GlassRenderer};
use wavlin_ui_style::Rgba;

pub const MAX_LAYERS: usize = 4;
const WORK_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8Unorm;

#[derive(Debug, thiserror::Error)]
pub enum PlayerError {
    #[error("size is zero or exceeds the device texture limit ({limit})")]
    BadSize { limit: u32 },
    #[error("artwork pixel data has {got} bytes, expected {want} for {w}x{h} RGBA8")]
    BadArtwork { got: usize, want: usize, w: u32, h: u32 },
    #[error("{count} background layers exceed the limit of {MAX_LAYERS}")]
    TooManyLayers { count: usize },
    #[error("a density, alpha or colour value is not finite")]
    NonFinite,
    #[error(transparent)]
    Glass(#[from] GlassError),
}

/// Decoded artwork bitmap on the GPU (RGBA8, straight alpha). Freed on drop.
pub struct Artwork {
    _texture: wgpu::Texture,
    view: wgpu::TextureView,
    size: (u32, u32),
}

impl Artwork {
    pub fn from_rgba8(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        width: u32,
        height: u32,
        rgba: &[u8],
    ) -> Result<Self, PlayerError> {
        let limit = device.limits().max_texture_dimension_2d;
        if width == 0 || height == 0 || width > limit || height > limit {
            return Err(PlayerError::BadSize { limit });
        }
        let want = width as usize * height as usize * 4;
        if rgba.len() != want {
            return Err(PlayerError::BadArtwork { got: rgba.len(), want, w: width, h: height });
        }
        let size = wgpu::Extent3d { width, height, depth_or_array_layers: 1 };
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("player artwork"),
            size,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: WORK_FORMAT,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        queue.write_texture(
            wgpu::ImageCopyTexture {
                texture: &texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            rgba,
            wgpu::ImageDataLayout { offset: 0, bytes_per_row: Some(width * 4), rows_per_image: Some(height) },
            size,
        );
        let view = texture.create_view(&Default::default());
        Ok(Self { _texture: texture, view, size: (width, height) })
    }

    pub fn size(&self) -> (u32, u32) {
        self.size
    }
}

pub enum LayerContent<'a> {
    /// `PlayerBackgroundStyle.BLUR`: the artwork, cropped, blurred by `DownscaledBlurBox`.
    Blur { artwork: &'a Artwork },
    /// `PlayerBackgroundStyle.GRADIENT`: colours from `PlayerColorExtractor`.
    Gradient { colors: &'a [Rgba] },
}

pub struct BackgroundLayer<'a> {
    pub content: LayerContent<'a>,
    /// Group alpha: the sheet progress, or a cross-fade alpha times it.
    pub alpha: f32,
}

pub struct PlayerFrame<'a> {
    pub size: (u32, u32),
    pub density: f32,
    pub dark_theme: bool,
    /// The sheet colour under the layers (`bottomSheetBackgroundColor`), supplied by the host.
    pub base_color: Rgba,
    /// Drawn in order; empty for `PlayerBackgroundStyle.Default`.
    pub layers: &'a [BackgroundLayer<'a>],
    pub glass: FrameSpec,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct LayerUniform {
    c0: [f32; 4],
    c1: [f32; 4],
    c2: [f32; 4],
    pos: [f32; 4],
    misc: [f32; 4],
    uv: [f32; 4],
}

struct Tex {
    _texture: wgpu::Texture,
    view: wgpu::TextureView,
    size: (u32, u32),
}

fn make_tex(device: &wgpu::Device, size: (u32, u32), usage: wgpu::TextureUsages, label: &str) -> Tex {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some(label),
        size: wgpu::Extent3d { width: size.0, height: size.1, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: WORK_FORMAT,
        usage,
        view_formats: &[],
    });
    let view = texture.create_view(&Default::default());
    Tex { _texture: texture, view, size }
}

const WORK_USAGE: wgpu::TextureUsages =
    wgpu::TextureUsages::RENDER_ATTACHMENT.union(wgpu::TextureUsages::TEXTURE_BINDING);

fn color4(c: Rgba) -> [f32; 4] {
    [c.r, c.g, c.b, c.a]
}

#[allow(clippy::too_many_arguments)]
fn run_pass(
    device: &wgpu::Device,
    encoder: &mut wgpu::CommandEncoder,
    bgl: &wgpu::BindGroupLayout,
    sampler: &wgpu::Sampler,
    pipeline: &wgpu::RenderPipeline,
    uniform: &LayerUniform,
    src: &wgpu::TextureView,
    dst: &wgpu::TextureView,
    load: wgpu::LoadOp<wgpu::Color>,
) {
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("player layer uniform"),
        size: size_of::<LayerUniform>() as u64,
        usage: wgpu::BufferUsages::UNIFORM,
        mapped_at_creation: true,
    });
    buffer.slice(..).get_mapped_range_mut().copy_from_slice(bytemuck::bytes_of(uniform));
    buffer.unmap();
    let bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("player bg"),
        layout: bgl,
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: buffer.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(sampler) },
            wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(src) },
        ],
    });
    let mut rp = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
        label: Some("player pass"),
        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
            view: dst,
            resolve_target: None,
            ops: wgpu::Operations { load, store: wgpu::StoreOp::Store },
        })],
        depth_stencil_attachment: None,
        timestamp_writes: None,
        occlusion_query_set: None,
    });
    rp.set_pipeline(pipeline);
    rp.set_bind_group(0, &bg, &[]);
    rp.draw(0..3, 0..1);
}

pub struct PlayerRenderer {
    glass: GlassRenderer,
    blur: BackdropFilter,
    sampler: wgpu::Sampler,
    bgl: wgpu::BindGroupLayout,
    crop_pipeline: wgpu::RenderPipeline,
    gradient_pipeline: wgpu::RenderPipeline,
    blur_layer_pipeline: wgpu::RenderPipeline,
    blit_pipeline: wgpu::RenderPipeline,
    dummy: Tex,
    backdrop: Option<Tex>,
    small: Option<Tex>,
}

impl PlayerRenderer {
    pub fn new(device: &wgpu::Device, target_format: wgpu::TextureFormat) -> Self {
        let bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("player bgl"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: wgpu::BufferSize::new(size_of::<LayerUniform>() as u64),
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
            ],
        });
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("player"),
            source: wgpu::ShaderSource::Wgsl(crate::shaders::player_source().into()),
        });
        let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("player layout"),
            bind_group_layouts: &[&bgl],
            push_constant_ranges: &[],
        });
        let pipeline = |fs: &str, format: wgpu::TextureFormat, blend: Option<wgpu::BlendState>| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(fs),
                layout: Some(&layout),
                vertex: wgpu::VertexState {
                    module: &module,
                    entry_point: "vs_full",
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                fragment: Some(wgpu::FragmentState {
                    module: &module,
                    entry_point: fs,
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState { format, blend, write_mask: wgpu::ColorWrites::ALL })],
                }),
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview: None,
                cache: None,
            })
        };
        let over = Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING);
        Self {
            glass: GlassRenderer::new(device, target_format),
            blur: BackdropFilter::new(device),
            sampler: clamp_linear_sampler(device),
            crop_pipeline: pipeline("fs_crop", WORK_FORMAT, None),
            gradient_pipeline: pipeline("fs_gradient", WORK_FORMAT, over),
            blur_layer_pipeline: pipeline("fs_blur_layer", WORK_FORMAT, over),
            blit_pipeline: pipeline("fs_blit", target_format, None),
            bgl,
            dummy: make_tex(device, (1, 1), WORK_USAGE, "player dummy"),
            backdrop: None,
            small: None,
        }
    }

    pub fn render(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        target: &wgpu::TextureView,
        frame: &PlayerFrame<'_>,
    ) -> Result<(), PlayerError> {
        let limit = device.limits().max_texture_dimension_2d;
        let (w, h) = frame.size;
        if w == 0 || h == 0 || w > limit || h > limit {
            return Err(PlayerError::BadSize { limit });
        }
        if frame.layers.len() > MAX_LAYERS {
            return Err(PlayerError::TooManyLayers { count: frame.layers.len() });
        }
        let finite = frame.density.is_finite()
            && frame.density > 0.0
            && color4(frame.base_color).iter().all(|v| v.is_finite())
            && frame.layers.iter().all(|l| {
                l.alpha.is_finite()
                    && match &l.content {
                        LayerContent::Gradient { colors } => colors.iter().all(|c| color4(*c).iter().all(|v| v.is_finite())),
                        LayerContent::Blur { .. } => true,
                    }
            });
        if !finite {
            return Err(PlayerError::NonFinite);
        }

        if self.backdrop.as_ref().map(|t| t.size) != Some(frame.size) {
            self.backdrop = Some(make_tex(device, frame.size, WORK_USAGE, "player backdrop"));
        }
        let identity = [ColorMatrix::IDENTITY; 2];
        let blank = LayerUniform::zeroed();

        let base = frame.base_color;
        let clear = wgpu::Color {
            r: f64::from(base.r * base.a),
            g: f64::from(base.g * base.a),
            b: f64::from(base.b * base.a),
            a: f64::from(base.a),
        };
        {
            let backdrop = self.backdrop.as_ref().expect("backdrop was just ensured");
            drop(encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("player base"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &backdrop.view,
                    resolve_target: None,
                    ops: wgpu::Operations { load: wgpu::LoadOp::Clear(clear), store: wgpu::StoreOp::Store },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            }));
        }

        for layer in frame.layers {
            let alpha = layer.alpha.clamp(0.0, 1.0);
            if alpha <= 0.0 {
                continue;
            }
            match &layer.content {
                LayerContent::Gradient { colors } => {
                    let Some(stops) = gradient_stops(colors) else { continue };
                    let u = LayerUniform {
                        c0: color4(stops.colors[0]),
                        c1: color4(stops.colors[1]),
                        c2: color4(stops.colors[2]),
                        pos: [stops.positions[0], stops.positions[1], stops.positions[2], 0.0],
                        misc: [alpha, GRADIENT_OVERLAY_ALPHA, 0.0, 0.0],
                        uv: [0.0; 4],
                    };
                    let dst = &self.backdrop.as_ref().expect("backdrop exists").view;
                    run_pass(device, encoder, &self.bgl, &self.sampler, &self.gradient_pipeline, &u, &self.dummy.view, dst, wgpu::LoadOp::Load);
                }
                LayerContent::Blur { artwork } => {
                    let small_size = small_layer_size(w, h, BLUR_DOWNSCALE_FACTOR);
                    if self.small.as_ref().map(|t| t.size) != Some(small_size) {
                        self.small = Some(make_tex(device, small_size, WORK_USAGE, "player small layer"));
                    }
                    let (scale, offset) = crop_uv(artwork.size, small_size);
                    let crop = LayerUniform { uv: [scale[0], scale[1], offset[0], offset[1]], ..blank };
                    let small_view = &self.small.as_ref().expect("small layer exists").view;
                    run_pass(device, encoder, &self.bgl, &self.sampler, &self.crop_pipeline, &crop, &artwork.view, small_view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));

                    let sigma = blur_sigma_small_px(blur_radius_dp(frame.dark_theme), frame.density, BLUR_DOWNSCALE_FACTOR);
                    let (blurred, _) = self.blur.run(device, encoder, small_view, small_size, &identity, sigma)?;
                    let u = LayerUniform { misc: [alpha, BLUR_OVERLAY_ALPHA, 0.0, 0.0], ..blank };
                    let dst = &self.backdrop.as_ref().expect("backdrop exists").view;
                    run_pass(device, encoder, &self.bgl, &self.sampler, &self.blur_layer_pipeline, &u, blurred, dst, wgpu::LoadOp::Load);
                }
            }
        }

        let backdrop_view = &self.backdrop.as_ref().expect("backdrop exists").view;
        run_pass(device, encoder, &self.bgl, &self.sampler, &self.blit_pipeline, &blank, backdrop_view, target, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
        self.glass.render(device, encoder, backdrop_view, frame.size, target, frame.size, &frame.glass)?;
        Ok(())
    }
}
