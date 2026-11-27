#version 120

uniform sampler2D scene;
uniform vec2 resolution;
uniform float time;

varying vec2 uv;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

void main() {
    vec2 centered = uv - 0.5;
    float vignette = smoothstep(0.82, 0.22, length(centered * vec2(1.0, 1.12)));

    // Gentle chromatic split inspired by the reference pack, implemented independently.
    float aberration = 0.0018 + 0.0009 * length(centered);
    vec2 redUv = uv + vec2(aberration, 0.0);
    vec2 blueUv = uv - vec2(aberration, 0.0);

    vec3 source;
    source.r = texture2D(scene, redUv).r;
    source.g = texture2D(scene, uv).g;
    source.b = texture2D(scene, blueUv).b;

    float luminance = dot(source, vec3(0.2126, 0.7152, 0.0722));
    vec3 desaturated = mix(vec3(luminance), source, 0.48);

    // Muted crimson / bruised-violet parasite palette.
    vec3 parasite = desaturated * vec3(0.82, 0.58, 0.70);
    parasite += vec3(0.035, 0.006, 0.025) * (1.0 - luminance);
    parasite = mix(vec3(0.012, 0.004, 0.018), parasite, smoothstep(0.015, 0.22, luminance));

    float scan = 0.94 + 0.06 * sin(uv.y * resolution.y * 1.15);
    float grain = (hash(floor(uv * resolution) + floor(time * 24.0)) - 0.5) * 0.035;
    float pulse = 0.985 + 0.015 * sin(time * 1.7);

    vec3 result = parasite * vignette * scan * pulse + grain;
    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
