//! wgpu implementation of the glass pipeline.
//!
//! Frame layout: the backdrop texture (the screen content behind the panels) is colour-filtered and
//! optionally downsampled, blurred with a separable Gaussian, then every panel draws shadow, lens +
//! tint + highlight into the target. Panel rects are in target pixels; the backdrop is sampled with
//! `pixel / target_size`, so the backdrop must cover the same area as the target.
//!
//! Deviations from Kyant/Skia, all in the doc of the item that causes them: the blur runs on a
//! downsampled copy (2x steps until sigma <= 4 texels), the backdrop is the whole screen rather
//! than a per-panel layer with padding, and shadow/highlight blur are analytic Gaussian edges.

use crate::math::plan_blur;
use crate::shaders::{panel_source, post_source};
use crate::math::ColorMatrix;
use crate::spec::{
    matrix_to_gpu, panel_uniform, FrameSpec, GlobalsUniform, PanelUniform, PostUniform,
};
use std::mem::size_of;

pub const MAX_PANELS: usize = 64;
const POST_SLOTS: usize = 16;
const INTERMEDIATE_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8Unorm;

#[derive(Debug, thiserror::Error)]
pub enum GlassError {
    #[error("backdrop or target size is zero or exceeds the device texture limit ({limit})")]
    BadSize { limit: u32 },
    #[error("{count} panels exceed the limit of {MAX_PANELS}")]
    TooManyPanels { count: usize },
    #[error("panel {index} has a non-finite rect, radius or colour")]
    NonFinitePanel { index: usize },
    #[error("backdrop filter or blur value is not finite")]
    NonFiniteBackdrop,
    #[error("the blur needs more than {POST_SLOTS} passes")]
    TooManyPasses,
}

struct Tex {
    _texture: wgpu::Texture,
    view: wgpu::TextureView,
    size: (u32, u32),
}

fn make_tex(device: &wgpu::Device, size: (u32, u32), label: &str) -> Tex {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some(label),
        size: wgpu::Extent3d { width: size.0, height: size.1, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: INTERMEDIATE_FORMAT,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    Tex { _texture: texture, view, size }
}

struct Scratch {
    backdrop_size: (u32, u32),
    steps: u32,
    needs_blur: bool,
    chain: Vec<Tex>,
    tmp: Option<Tex>,
}

/// Colour filters, optional downsample and separable Gaussian blur of a source texture. Used by
/// `GlassRenderer` for the glass backdrop and by the player background for the artwork blur.
pub struct BackdropFilter {
    sampler: wgpu::Sampler,
    post_bgl: wgpu::BindGroupLayout,
    color_pipeline: wgpu::RenderPipeline,
    color_ds2_pipeline: wgpu::RenderPipeline,
    ds2_pipeline: wgpu::RenderPipeline,
    blur_pipeline: wgpu::RenderPipeline,
    post_stride: u64,
    scratch: Option<Scratch>,
}

fn align_up(v: u64, a: u64) -> u64 {
    v.div_ceil(a) * a
}

fn filterable_texture_entry(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::FRAGMENT,
        ty: wgpu::BindingType::Texture {
            sample_type: wgpu::TextureSampleType::Float { filterable: true },
            view_dimension: wgpu::TextureViewDimension::D2,
            multisampled: false,
        },
        count: None,
    }
}

fn filtering_sampler_entry(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::FRAGMENT,
        ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
        count: None,
    }
}

/// Linear, clamp-to-edge sampler (Compose `TileMode.Clamp`).
pub fn clamp_linear_sampler(device: &wgpu::Device) -> wgpu::Sampler {
    device.create_sampler(&wgpu::SamplerDescriptor {
        label: Some("glass sampler"),
        address_mode_u: wgpu::AddressMode::ClampToEdge,
        address_mode_v: wgpu::AddressMode::ClampToEdge,
        address_mode_w: wgpu::AddressMode::ClampToEdge,
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        mipmap_filter: wgpu::FilterMode::Nearest,
        ..Default::default()
    })
}

impl BackdropFilter {
    pub fn new(device: &wgpu::Device) -> Self {
        let align = u64::from(device.limits().min_uniform_buffer_offset_alignment);
        let sampler = clamp_linear_sampler(device);
        let post_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("glass post bgl"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: true,
                        min_binding_size: wgpu::BufferSize::new(size_of::<PostUniform>() as u64),
                    },
                    count: None,
                },
                filtering_sampler_entry(1),
                filterable_texture_entry(2),
            ],
        });
        let post_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glass post"),
            source: wgpu::ShaderSource::Wgsl(post_source().into()),
        });
        let post_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("glass post layout"),
            bind_group_layouts: &[&post_bgl],
            push_constant_ranges: &[],
        });
        let post_pipeline = |fs: &str| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(fs),
                layout: Some(&post_layout),
                vertex: wgpu::VertexState {
                    module: &post_module,
                    entry_point: "vs_full",
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                fragment: Some(wgpu::FragmentState {
                    module: &post_module,
                    entry_point: fs,
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: INTERMEDIATE_FORMAT,
                        blend: None,
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview: None,
                cache: None,
            })
        };
        let post_stride = align_up(size_of::<PostUniform>() as u64, align);
        Self {
            color_pipeline: post_pipeline("fs_color"),
            color_ds2_pipeline: post_pipeline("fs_color_ds2"),
            ds2_pipeline: post_pipeline("fs_ds2"),
            blur_pipeline: post_pipeline("fs_blur"),
            sampler,
            post_bgl,
            post_stride,
            scratch: None,
        }
    }

    fn ensure_scratch(&mut self, device: &wgpu::Device, size: (u32, u32), steps: u32, needs_blur: bool) {
        let ok = self
            .scratch
            .as_ref()
            .is_some_and(|s| s.backdrop_size == size && s.steps == steps && s.needs_blur == needs_blur);
        if ok {
            return;
        }
        let mut chain = Vec::new();
        if steps == 0 {
            chain.push(make_tex(device, size, "glass chain 0"));
        } else {
            for k in 1..=steps {
                let div = 1u32 << k;
                let s = (size.0.div_ceil(div).max(1), size.1.div_ceil(div).max(1));
                chain.push(make_tex(device, s, "glass chain"));
            }
        }
        let tmp = needs_blur.then(|| make_tex(device, chain.last().map_or(size, |t| t.size), "glass blur tmp"));
        self.scratch = Some(Scratch { backdrop_size: size, steps, needs_blur, chain, tmp });
    }

    /// Records colour filters, downsample steps and blur of `src` into `encoder` and returns the
    /// result view with its size. The view stays valid until the next call with a different size
    /// or blur plan, or until the filter is dropped.
    #[allow(clippy::too_many_arguments)]
    pub fn run(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        src: &wgpu::TextureView,
        src_size: (u32, u32),
        filters: &[ColorMatrix; 2],
        blur_sigma_px: f32,
    ) -> Result<(&wgpu::TextureView, (u32, u32)), GlassError> {
        let limit = device.limits().max_texture_dimension_2d;
        if src_size.0 == 0 || src_size.1 == 0 || src_size.0 > limit || src_size.1 > limit {
            return Err(GlassError::BadSize { limit });
        }
        if !blur_sigma_px.is_finite() || !filters.iter().all(|m| m.rows.iter().flatten().all(|v| v.is_finite())) {
            return Err(GlassError::NonFiniteBackdrop);
        }
        let plan = plan_blur(blur_sigma_px);
        let needs_blur = plan.radius_texels > 0;
        self.ensure_scratch(device, src_size, plan.downsample_steps, needs_blur);
        let scratch = self.scratch.as_ref().expect("scratch was just ensured");

        let (m0, o0) = matrix_to_gpu(&filters[0]);
        let (m1, o1) = matrix_to_gpu(&filters[1]);
        let base = PostUniform { m0, o0, m1, o1, step_uv: [0.0; 2], sigma: 1.0, radius: 0 };

        struct Pass<'a> {
            pipeline: &'a wgpu::RenderPipeline,
            src: &'a wgpu::TextureView,
            dst: &'a wgpu::TextureView,
            uniform: PostUniform,
        }
        let mut passes: Vec<Pass> = Vec::new();
        passes.push(Pass {
            pipeline: if plan.downsample_steps == 0 { &self.color_pipeline } else { &self.color_ds2_pipeline },
            src,
            dst: &scratch.chain[0].view,
            uniform: base,
        });
        for k in 1..scratch.chain.len() {
            passes.push(Pass {
                pipeline: &self.ds2_pipeline,
                src: &scratch.chain[k - 1].view,
                dst: &scratch.chain[k].view,
                uniform: base,
            });
        }
        let final_tex = scratch.chain.last().expect("chain is never empty");
        if let (true, Some(tmp)) = (needs_blur, scratch.tmp.as_ref()) {
            let (w, h) = (final_tex.size.0 as f32, final_tex.size.1 as f32);
            let blur = |step_uv| PostUniform { step_uv, sigma: plan.sigma_texels, radius: plan.radius_texels, ..base };
            passes.push(Pass {
                pipeline: &self.blur_pipeline,
                src: &final_tex.view,
                dst: &tmp.view,
                uniform: blur([1.0 / w, 0.0]),
            });
            passes.push(Pass {
                pipeline: &self.blur_pipeline,
                src: &tmp.view,
                dst: &final_tex.view,
                uniform: blur([0.0, 1.0 / h]),
            });
        }
        if passes.len() > POST_SLOTS {
            return Err(GlassError::TooManyPasses);
        }

        // One uniform buffer per run, so several runs recorded into one submission cannot overwrite
        // each other's parameters.
        let post_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass post uniforms"),
            size: self.post_stride * passes.len() as u64,
            usage: wgpu::BufferUsages::UNIFORM,
            mapped_at_creation: true,
        });
        {
            let mut mapped = post_buffer.slice(..).get_mapped_range_mut();
            for (i, p) in passes.iter().enumerate() {
                let at = i * self.post_stride as usize;
                mapped[at..at + size_of::<PostUniform>()].copy_from_slice(bytemuck::bytes_of(&p.uniform));
            }
        }
        post_buffer.unmap();

        for (i, p) in passes.iter().enumerate() {
            let bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("glass post bg"),
                layout: &self.post_bgl,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                            buffer: &post_buffer,
                            offset: 0,
                            size: wgpu::BufferSize::new(size_of::<PostUniform>() as u64),
                        }),
                    },
                    wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                    wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(p.src) },
                ],
            });
            let mut rp = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("glass post pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: p.dst,
                    resolve_target: None,
                    ops: wgpu::Operations { load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT), store: wgpu::StoreOp::Store },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            rp.set_pipeline(p.pipeline);
            rp.set_bind_group(0, &bg, &[(i as u64 * self.post_stride) as u32]);
            rp.draw(0..3, 0..1);
        }
        Ok((&final_tex.view, final_tex.size))
    }
}

pub struct GlassRenderer {
    target_format: wgpu::TextureFormat,
    filter: BackdropFilter,
    sampler: wgpu::Sampler,
    panel_globals_bgl: wgpu::BindGroupLayout,
    shadow_pipeline: wgpu::RenderPipeline,
    glass_pipeline: wgpu::RenderPipeline,
    panel_bgl: wgpu::BindGroupLayout,
    panel_stride: u64,
}

impl GlassRenderer {
    /// `target_format` is the format of the view later passed to `render`. A non-sRGB view gives
    /// output closest to Android, which blends in encoded sRGB space; an sRGB view is supported by
    /// converting the shader output to linear, but edge and shadow blending then happen in linear.
    pub fn new(device: &wgpu::Device, target_format: wgpu::TextureFormat) -> Self {
        let align = u64::from(device.limits().min_uniform_buffer_offset_alignment);
        let sampler = clamp_linear_sampler(device);

        let panel_globals_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("glass panel globals bgl"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: wgpu::BufferSize::new(size_of::<GlobalsUniform>() as u64),
                    },
                    count: None,
                },
                filtering_sampler_entry(1),
                filterable_texture_entry(2),
            ],
        });
        let panel_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("glass panel bgl"),
            entries: &[wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: true,
                    min_binding_size: wgpu::BufferSize::new(size_of::<PanelUniform>() as u64),
                },
                count: None,
            }],
        });
        let panel_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glass panel"),
            source: wgpu::ShaderSource::Wgsl(panel_source().into()),
        });
        let panel_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("glass panel layout"),
            bind_group_layouts: &[&panel_globals_bgl, &panel_bgl],
            push_constant_ranges: &[],
        });
        let panel_pipeline = |vs: &str, fs: &str| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(fs),
                layout: Some(&panel_layout),
                vertex: wgpu::VertexState {
                    module: &panel_module,
                    entry_point: vs,
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                fragment: Some(wgpu::FragmentState {
                    module: &panel_module,
                    entry_point: fs,
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: target_format,
                        blend: Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                primitive: wgpu::PrimitiveState {
                    topology: wgpu::PrimitiveTopology::TriangleStrip,
                    ..Default::default()
                },
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview: None,
                cache: None,
            })
        };
        let panel_stride = align_up(size_of::<PanelUniform>() as u64, align);
        Self {
            target_format,
            filter: BackdropFilter::new(device),
            sampler,
            panel_globals_bgl,
            shadow_pipeline: panel_pipeline("vs_shadow", "fs_shadow"),
            glass_pipeline: panel_pipeline("vs_glass", "fs_glass"),
            panel_bgl,
            panel_stride,
        }
    }

    /// Records all glass passes into `encoder`. The caller keeps ownership of every texture it
    /// passes in. Resources created here are freed on drop or when the size changes.
    #[allow(clippy::too_many_arguments)]
    pub fn render(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        backdrop: &wgpu::TextureView,
        backdrop_size: (u32, u32),
        target: &wgpu::TextureView,
        target_size: (u32, u32),
        frame: &FrameSpec,
    ) -> Result<(), GlassError> {
        let limit = device.limits().max_texture_dimension_2d;
        if target_size.0 == 0 || target_size.1 == 0 || target_size.0 > limit || target_size.1 > limit {
            return Err(GlassError::BadSize { limit });
        }
        if frame.panels.len() > MAX_PANELS {
            return Err(GlassError::TooManyPanels { count: frame.panels.len() });
        }
        for (index, p) in frame.panels.iter().enumerate() {
            let u = panel_uniform(p);
            let all: [&[f32; 4]; 10] =
                [&u.rect, &u.radii, &u.tint, &u.overlay, &u.lens, &u.hl_color, &u.hl, &u.hl_mode, &u.sh_color, &u.sh];
            if !all.iter().all(|a| a.iter().all(|v| v.is_finite())) {
                return Err(GlassError::NonFinitePanel { index });
            }
        }

        let (blurred, _) = self.filter.run(
            device,
            encoder,
            backdrop,
            backdrop_size,
            &frame.backdrop.filters,
            frame.backdrop.blur_sigma_px,
        )?;

        if frame.panels.is_empty() {
            return Ok(());
        }
        let globals = GlobalsUniform {
            target_size: [target_size.0 as f32, target_size.1 as f32],
            srgb_target: if self.target_format.is_srgb() { 1.0 } else { 0.0 },
            _pad: 0.0,
        };
        let globals_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass globals"),
            size: size_of::<GlobalsUniform>() as u64,
            usage: wgpu::BufferUsages::UNIFORM,
            mapped_at_creation: true,
        });
        globals_buffer.slice(..).get_mapped_range_mut().copy_from_slice(bytemuck::bytes_of(&globals));
        globals_buffer.unmap();

        let panel_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass panel uniforms"),
            size: self.panel_stride * frame.panels.len() as u64,
            usage: wgpu::BufferUsages::UNIFORM,
            mapped_at_creation: true,
        });
        {
            let mut mapped = panel_buffer.slice(..).get_mapped_range_mut();
            for (i, p) in frame.panels.iter().enumerate() {
                let at = i * self.panel_stride as usize;
                mapped[at..at + size_of::<PanelUniform>()].copy_from_slice(bytemuck::bytes_of(&panel_uniform(p)));
            }
        }
        panel_buffer.unmap();
        let panel_bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glass panel bg"),
            layout: &self.panel_bgl,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                    buffer: &panel_buffer,
                    offset: 0,
                    size: wgpu::BufferSize::new(size_of::<PanelUniform>() as u64),
                }),
            }],
        });

        let globals_bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glass panel globals bg"),
            layout: &self.panel_globals_bgl,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: globals_buffer.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(blurred) },
            ],
        });

        let mut rp = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("glass panel pass"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view: target,
                resolve_target: None,
                ops: wgpu::Operations { load: wgpu::LoadOp::Load, store: wgpu::StoreOp::Store },
            })],
            depth_stencil_attachment: None,
            timestamp_writes: None,
            occlusion_query_set: None,
        });
        rp.set_bind_group(0, &globals_bg, &[]);
        for (i, p) in frame.panels.iter().enumerate() {
            if p.rect[2] <= 0.0 || p.rect[3] <= 0.0 {
                continue;
            }
            let off = (i as u64 * self.panel_stride) as u32;
            rp.set_bind_group(1, &panel_bind_group, &[off]);
            if p.shadow.is_some_and(|s| s.radius_px > 0.0) {
                rp.set_pipeline(&self.shadow_pipeline);
                rp.draw(0..4, 0..1);
            }
            rp.set_pipeline(&self.glass_pipeline);
            rp.draw(0..4, 0..1);
        }
        Ok(())
    }
}
