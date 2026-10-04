#version 150
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec3 ClipSize;
uniform vec4 ColorModulator;
uniform float AlphaCutoff;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in vec4 vertexColor;
in vec2 uv;
in vec3 clipPosition;
in vec3 faceNormal;
in float distanceFromCamera;
out vec4 fragColor;
void main() {
    vec3 coveragePosition = clipPosition;
    float normalLength = length(faceNormal);
    if (normalLength > 0.0) coveragePosition -= faceNormal * (0.01 / normalLength);
    ivec3 cell = ivec3(floor(coveragePosition / 16.0));
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(ClipSize)))
            && texelFetch(Sampler1, ivec2(cell.x, cell.y * int(ClipSize.z) + cell.z), 0).r > 0.5) discard;
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    if (color.a <= 0.0 || color.a < AlphaCutoff) discard;
    fragColor = linear_fog(color, distanceFromCamera, FogStart, FogEnd, FogColor);
}
