#version 330

// 1-bit ordered dithering, like a 1984 Macintosh: chunky 2px cells, 4x4 Bayer matrix, two colours.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

const float BAYER[16] = float[](
    0.0, 8.0, 2.0, 10.0,
    12.0, 4.0, 14.0, 6.0,
    3.0, 11.0, 1.0, 9.0,
    15.0, 7.0, 13.0, 5.0
);

void main() {
    float size = 2.0;
    vec2 cell = floor(gl_FragCoord.xy / size);
    vec2 uv = (cell * size + size * 0.5) / OutSize;
    float l = dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
    l = clamp((l - 0.5) * 1.3 + 0.52, 0.0, 1.0);
    int i = int(mod(cell.x, 4.0)) + int(mod(cell.y, 4.0)) * 4;
    float threshold = (BAYER[i] + 0.5) / 16.0;
    vec3 ink = vec3(0.06);
    vec3 paper = vec3(0.92);
    fragColor = vec4(l > threshold ? paper : ink, 1.0);
}
