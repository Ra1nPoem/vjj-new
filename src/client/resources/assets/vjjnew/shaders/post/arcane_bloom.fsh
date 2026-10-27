#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BloomConfig {
    float Intensity;
    float Aberration;
    float Vignette;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// 原创后处理：亮部径向泛光（伪 bloom）+ 色散 + 星云紫调 + 暗角 + 呼吸脉冲。
// 参考 ArcaneVortex 的后处理观感，但完全独立实现（不复制其 ARR shader）。
void main() {
    vec2 uv = texCoord;
    vec2 centered = uv - 0.5;
    float radius = length(centered);
    vec2 dir = radius > 0.0001 ? centered / radius : vec2(0.0);

    // 1) 色散：R/B 沿径向错位，中心几乎无偏移、边缘更强
    float chroma = Aberration * (0.35 + radius);
    vec3 base;
    base.r = texture(InSampler, uv + dir * chroma).r;
    base.g = texture(InSampler, uv).g;
    base.b = texture(InSampler, uv - dir * chroma).b;

    // 2) 亮部提取 + 径向采样，形成辉光（13 个方向，避免过大性能开销）
    vec3 glow = vec3(0.0);
    float weightSum = 0.0;
    const int SAMPLES = 13;
    for (int i = 0; i < SAMPLES; i++) {
        float angle = float(i) * 2.39996323;
        float dist = mix(0.0018, 0.028, float(i) / float(SAMPLES - 1));
        vec2 offset = vec2(cos(angle), sin(angle)) * dist;
        vec3 sampleColor = texture(InSampler, uv + offset).rgb;
        float luma = dot(sampleColor, vec3(0.2126, 0.7152, 0.0722));
        float bright = max(luma - 0.66, 0.0);
        glow += sampleColor * bright;
        weightSum += bright;
    }
    glow = weightSum > 0.0001 ? glow / weightSum : vec3(0.0);

    // 3) 紫色星云染色 + 呼吸
    float pulse = 0.92 + 0.08 * sin(GameTime * 0.11);
    vec3 tint = mix(vec3(1.0), vec3(0.82, 0.78, 1.12), 0.55);
    glow *= tint;

    // 4) 暗角 + 中心轻微提亮
    float vignette = clamp(1.0 - Vignette * radius * radius * 1.8, 0.0, 1.0);
    vec3 color = base + glow * (Intensity * 0.62 * pulse);
    color *= vignette;
    color += vec3(0.018, 0.010, 0.028) * (1.0 - radius);

    // 5) 轻微冷色调，避免高光过曝成纯白
    color = mix(color, color * vec3(0.90, 0.94, 1.08), 0.28);
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
