#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform WarpConfig {
    float Strength;
    float Glow;
    float Speed;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// 原创「奥术扭曲」后处理：屏幕径向涟漪 + 色散 + 边缘辉光 + 冷紫调。
// 作为第二个 post_effect 与 arcane_bloom 串联，形成多级后处理链。
void main() {
    vec2 centered = texCoord - 0.5;
    float radius = length(centered);
    vec2 dir = radius > 0.0001 ? centered / radius : vec2(0.0);

    // 径向涟漪（中心弱、边缘强），随时间流动
    float wave = sin(radius * 24.0 - GameTime * Speed) * Strength
               * smoothstep(0.04, 0.55, radius);
    vec2 uv = texCoord + dir * wave * (0.35 + radius);

    // 色散
    float chroma = Strength * (0.5 + radius) * 0.9;
    vec3 color;
    color.r = texture(InSampler, uv + dir * chroma).r;
    color.g = texture(InSampler, uv).g;
    color.b = texture(InSampler, uv - dir * chroma).b;

    // 边缘辉光：在涟漪峰值附近额外采样，形成能量边缘
    vec3 edgeGlow = vec3(0.0);
    float edge = smoothstep(0.22, 0.5, radius);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 2.39996323;
        vec2 off = vec2(cos(a), sin(a)) * (0.006 + radius * 0.012);
        vec3 s = texture(InSampler, uv + off).rgb;
        float luma = dot(s, vec3(0.2126, 0.7152, 0.0722));
        edgeGlow += s * max(luma - 0.62, 0.0);
    }
    edgeGlow /= 8.0;
    color += edgeGlow * Glow * edge * 1.6;

    // 冷紫调 + 暗角；中心保留原色，避免整屏发灰
    vec3 tint = vec3(0.86, 0.80, 1.08);
    color = mix(color, color * tint, 0.32 * smoothstep(0.15, 0.5, radius));
    float vignette = clamp(1.0 - radius * radius * 0.42, 0.0, 1.0);
    color *= vignette;
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
