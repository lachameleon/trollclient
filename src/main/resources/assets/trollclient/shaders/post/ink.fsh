#version 330

// Pen and ink: Sobel edges become black lines on paper, dark areas get cross-hatched.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

float lum(vec2 uv) {
    return dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 px = 1.0 / InSize;
    float tl = lum(texCoord + px * vec2(-1.0, 1.0));
    float tc = lum(texCoord + px * vec2(0.0, 1.0));
    float tr = lum(texCoord + px * vec2(1.0, 1.0));
    float ml = lum(texCoord + px * vec2(-1.0, 0.0));
    float mr = lum(texCoord + px * vec2(1.0, 0.0));
    float bl = lum(texCoord + px * vec2(-1.0, -1.0));
    float bc = lum(texCoord + px * vec2(0.0, -1.0));
    float br = lum(texCoord + px * vec2(1.0, -1.0));
    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (tl + 2.0 * tc + tr) - (bl + 2.0 * bc + br);
    float edge = sqrt(gx * gx + gy * gy);

    float l = lum(texCoord);
    float tone = l > 0.55 ? 1.0 : (l > 0.28 ? 0.86 : 0.72);
    float hatch = 1.0;
    if (l < 0.32 && mod(gl_FragCoord.x + gl_FragCoord.y, 5.0) < 1.0) {
        hatch = 0.3;
    }
    if (l < 0.16 && mod(gl_FragCoord.x - gl_FragCoord.y, 5.0) < 1.0) {
        hatch = 0.3;
    }
    float line = smoothstep(0.1, 0.32, edge);
    vec3 col = vec3(0.97) * tone * hatch;
    fragColor = vec4(mix(col, vec3(0.04), line), 1.0);
}
