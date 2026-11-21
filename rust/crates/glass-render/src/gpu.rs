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

pub struct GlassRenderer {
    target_format: wgpu::TextureFormat,
    sampler: wgpu::Sampler,
    post_bgl: wgpu::BindGroupLayout,
    panel_globals_bgl: wgpu::BindGroupLayout,
    color_pipeline: wgpu::RenderPipeline,
    color_ds2_pipeline: wgpu::RenderPipeline,
    ds2_pipeline: wgpu::RenderPipeline,
    blur_pipeline: wgpu::RenderPipeline,
    shadow_pipeline: wgpu::RenderPipeline,
    glass_pipeline: wgpu::RenderPipeline,
    post_buffer: wgpu::Buffer,
    post_stride: u64,
    globals_buffer: wgpu::Buffer,
    panel_buffer: wgpu::Buffer,
    panel_stride: u64,
    panel_bind_group: wgpu::BindGroup,
    scratch: Option<Scratch>,
}

fn align_up(v: u64, a: u64) -> u64 {
    v.div_ceil(a) * a
}

impl GlassRenderer {
    /// `target_format` is the format of the view later passed to `render`. A non-sRGB view gives
    /// output closest to Android, which blends in encoded sRGB space; an sRGB view is supported by
    /// converting the shader output to linear, but edge and shadow blending then happen in linear.
    pub fn new(device: &wgpu::Device, target_format: wgpu::TextureFormat) -> Self {
        let limits = device.limits();
        let align = u64::from(limits.min_uniform_buffer_offset_alignment);

        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("glass sampler"),
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::FilterMode::Nearest,
            ..Default::default()
        });

        let tex_entry = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };
        let sampler_entry = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
            count: None,
        };

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
                sampler_entry(1),
                tex_entry(2),
            ],
        });
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
                sampler_entry(1),
                tex_entry(2),
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

        let post_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glass post"),
            source: wgpu::ShaderSource::Wgsl(post_source().into()),
        });
        let panel_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glass panel"),
            source: wgpu::ShaderSource::Wgsl(panel_source().into()),
        });

        let post_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("glass post layout"),
            bind_group_layouts: &[&post_bgl],
            push_constant_ranges: &[],
        });
        let panel_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("glass panel layout"),
            bind_group_layouts: &[&panel_globals_bgl, &panel_bgl],
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

        let post_stride = align_up(size_of::<PostUniform>() as u64, align);
        let post_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass post uniforms"),
            size: post_stride * POST_SLOTS as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let globals_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass globals"),
            size: size_of::<GlobalsUniform>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let panel_stride = align_up(size_of::<PanelUniform>() as u64, align);
        let panel_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass panel uniforms"),
            size: panel_stride * MAX_PANELS as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let panel_bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glass panel bg"),
            layout: &panel_bgl,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                    buffer: &panel_buffer,
                    offset: 0,
                    size: wgpu::BufferSize::new(size_of::<PanelUniform>() as u64),
                }),
            }],
        });

        Self {
            target_format,
            color_pipeline: post_pipeline("fs_color"),
            color_ds2_pipeline: post_pipeline("fs_color_ds2"),
            ds2_pipeline: post_pipeline("fs_ds2"),
            blur_pipeline: post_pipeline("fs_blur"),
            shadow_pipeline: panel_pipeline("vs_shadow", "fs_shadow"),
            glass_pipeline: panel_pipeline("vs_glass", "fs_glass"),
            sampler,
            post_bgl,
            panel_globals_bgl,
            post_buffer,
            post_stride,
            globals_buffer,
            panel_buffer,
            panel_stride,
            panel_bind_group,
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

    /// Records all glass passes into `encoder`. The caller keeps ownership of every texture it
    /// passes in. Resources created here are freed on drop or when the size changes.
    #[allow(clippy::too_many_arguments)]
    pub fn render(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        encoder: &mut wgpu::CommandEncoder,
        backdrop: &wgpu::TextureView,
        backdrop_size: (u32, u32),
        target: &wgpu::TextureView,
        target_size: (u32, u32),
        frame: &FrameSpec,
    ) -> Result<(), GlassError> {
        let limit = device.limits().max_texture_dimension_2d;
        let ok_size = |s: (u32, u32)| s.0 > 0 && s.1 > 0 && s.0 <= limit && s.1 <= limit;
        if !ok_size(backdrop_size) || !ok_size(target_size) {
            return Err(GlassError::BadSize { limit });
        }
        if frame.panels.len() > MAX_PANELS {
            return Err(GlassError::TooManyPanels { count: frame.panels.len() });
        }
        let finite_backdrop = frame.backdrop.blur_sigma_px.is_finite()
            && frame.backdrop.filters.iter().all(|m| m.rows.iter().flatten().all(|v| v.is_finite()));
        if !finite_backdrop {
            return Err(GlassError::NonFiniteBackdrop);
        }
        for (index, p) in frame.panels.iter().enumerate() {
            let u = panel_uniform(p);
            let all: [&[f32; 4]; 10] =
                [&u.rect, &u.radii, &u.tint, &u.overlay, &u.lens, &u.hl_color, &u.hl, &u.hl_mode, &u.sh_color, &u.sh];
            if !all.iter().all(|a| a.iter().all(|v| v.is_finite())) {
                return Err(GlassError::NonFinitePanel { index });
            }
        }

        let plan = plan_blur(frame.backdrop.blur_sigma_px);
        let needs_blur = plan.radius_texels > 0;
        self.ensure_scratch(device, backdrop_size, plan.downsample_steps, needs_blur);
        let scratch = self.scratch.as_ref().expect("scratch was just ensured");

        // ---- post passes: colour (+ds2), further ds2 steps, blur H, blur V ----
        let (m0, o0) = matrix_to_gpu(&frame.backdrop.filters[0]);
        let (m1, o1) = matrix_to_gpu(&frame.backdrop.filters[1]);
        let base = PostUniform { m0, o0, m1, o1, step_uv: [0.0; 2], sigma: 1.0, radius: 0 };

        struct Pass<'a> {
            pipeline: &'a wgpu::RenderPipeline,
            src: &'a wgpu::TextureView,
            dst: &'a wgpu::TextureView,
            uniform: PostUniform,
        }
        let mut passes: Vec<Pass> = Vec::new();
        let first = &scratch.chain[0];
        passes.push(Pass {
            pipeline: if plan.downsample_steps == 0 { &self.color_pipeline } else { &self.color_ds2_pipeline },
            src: backdrop,
            dst: &first.view,
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
            passes.push(Pass {
                pipeline: &self.blur_pipeline,
                src: &final_tex.view,
                dst: &tmp.view,
                uniform: PostUniform {
                    step_uv: [1.0 / w, 0.0],
                    sigma: plan.sigma_texels,
                    radius: plan.radius_texels,
                    ..base
                },
            });
            passes.push(Pass {
                pipeline: &self.blur_pipeline,
                src: &tmp.view,
                dst: &final_tex.view,
                uniform: PostUniform {
                    step_uv: [0.0, 1.0 / h],
                    sigma: plan.sigma_texels,
                    radius: plan.radius_texels,
                    ..base
                },
            });
        }
        debug_assert!(passes.len() <= POST_SLOTS);

        let mut post_bytes = vec![0u8; self.post_stride as usize * passes.len()];
        for (i, p) in passes.iter().enumerate() {
            let at = i * self.post_stride as usize;
            post_bytes[at..at + size_of::<PostUniform>()].copy_from_slice(bytemuck::bytes_of(&p.uniform));
        }
        queue.write_buffer(&self.post_buffer, 0, &post_bytes);

        for (i, p) in passes.iter().enumerate() {
            let bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("glass post bg"),
                layout: &self.post_bgl,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                            buffer: &self.post_buffer,
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

        // ---- panel pass ----
        if frame.panels.is_empty() {
            return Ok(());
        }
        let globals = GlobalsUniform {
            target_size: [target_size.0 as f32, target_size.1 as f32],
            srgb_target: if self.target_format.is_srgb() { 1.0 } else { 0.0 },
            _pad: 0.0,
        };
        queue.write_buffer(&self.globals_buffer, 0, bytemuck::bytes_of(&globals));

        let mut panel_bytes = vec![0u8; self.panel_stride as usize * frame.panels.len()];
        for (i, p) in frame.panels.iter().enumerate() {
            let at = i * self.panel_stride as usize;
            panel_bytes[at..at + size_of::<PanelUniform>()].copy_from_slice(bytemuck::bytes_of(&panel_uniform(p)));
        }
        queue.write_buffer(&self.panel_buffer, 0, &panel_bytes);

        let globals_bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glass panel globals bg"),
            layout: &self.panel_globals_bgl,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: self.globals_buffer.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(&final_tex.view) },
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
            rp.set_bind_group(1, &self.panel_bind_group, &[off]);
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
