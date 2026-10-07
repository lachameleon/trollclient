#version 330

// Newspaper halftone: a 45 degree grid of ink dots whose size follows the darkness underneath.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

void main() {
    float cell = 6.0;
    float a = 0.7853982;
    mat2 rot = mat2(cos(a), sin(a), -sin(a), cos(a));
    vec2 p = rot * gl_FragCoord.xy;
    vec2 center = (floor(p / cell) + 0.5) * cell;
    vec2 uv = clamp((transpose(rot) * center) / OutSize, vec2(0.0), vec2(1.0));
    float l = dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
    float radius = sqrt(clamp(1.0 - l, 0.0, 1.0)) * cell * 0.64;
    float d = length(p - center);
    float ink = 1.0 - smoothstep(radius - 0.8, radius + 0.8, d);
    vec3 paper = vec3(0.94, 0.925, 0.885);
    fragColor = vec4(mix(paper, vec3(0.08), ink), 1.0);
}
