#version 330

#moj_import <minecraft:globals.glsl>

// Film noir: luminance only, an S-curve that crushes the blacks, a soft vignette and moving grain.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    l = smoothstep(0.03, 0.97, l);
    l = mix(l, l * l * (3.0 - 2.0 * l), 0.65);
    vec2 d = texCoord - 0.5;
    float vignette = 1.0 - dot(d, d) * 1.2;
    float tick = floor(GameTime * 24000.0);
    float grain = (hash(gl_FragCoord.xy + vec2(tick * 7.13, tick * 3.71)) - 0.5) * 0.07;
    fragColor = vec4(vec3(clamp(l * vignette + grain, 0.0, 1.0)), 1.0);
}
