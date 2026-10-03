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
in float distanceFromCamera;
out vec4 fragColor;
void main() {
    ivec3 cell = ivec3(floor(clipPosition / 16.0));
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(ClipSize)))
            && texelFetch(Sampler1, ivec2(cell.x, cell.y * int(ClipSize.z) + cell.z), 0).r > 0.5) discard;
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    if (color.a < AlphaCutoff) discard;
    fragColor = linear_fog(color, distanceFromCamera, FogStart, FogEnd, FogColor);
}
