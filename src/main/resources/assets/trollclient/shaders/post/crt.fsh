#version 330

#moj_import <minecraft:globals.glsl>

// Monochrome CRT: barrel distortion, beam bloom, scanlines, a rolling refresh band and dark rounded corners.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 uv = texCoord * 2.0 - 1.0;
    vec2 bend = abs(uv.yx) / vec2(5.5, 4.5);
    uv = uv + uv * bend * bend;
    uv = uv * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 px = 1.0 / InSize;
    float l = luma(texture(InSampler, uv).rgb) * 0.56
            + luma(texture(InSampler, uv + vec2(px.x * 1.5, 0.0)).rgb) * 0.22
            + luma(texture(InSampler, uv - vec2(px.x * 1.5, 0.0)).rgb) * 0.22;
    l = pow(clamp(l, 0.0, 1.0), 0.85) * 1.12;

    float t = GameTime * 24000.0 / 20.0;
    float scan = 0.74 + 0.26 * cos(gl_FragCoord.y * 2.0943951);
    float band = 1.0 + 0.08 * exp(-pow((fract(uv.y * 0.6 - t * 0.12) - 0.5) * 9.0, 2.0));
    float flicker = 1.0 + 0.012 * sin(t * 57.0);
    vec2 edge = smoothstep(vec2(0.0), vec2(0.035), uv) * smoothstep(vec2(0.0), vec2(0.035), 1.0 - uv);
    vec2 d = uv - 0.5;
    float vignette = (1.0 - dot(d, d) * 0.9) * edge.x * edge.y;

    vec3 phosphor = vec3(0.94, 0.98, 1.0);
    fragColor = vec4(phosphor * clamp(l * scan * band * flicker * vignette, 0.0, 1.0), 1.0);
}
