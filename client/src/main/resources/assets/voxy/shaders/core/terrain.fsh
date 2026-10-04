#version 150
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler3;
uniform vec3 ClipSize;
uniform vec3 SectionOffset;
uniform float LodScale;
uniform int OpaquePass;
uniform mat4 NativeToWorld;
uniform vec4 NativeViewport;
uniform vec3 NativeOrigin;
uniform vec4 ColorModulator;
uniform float AlphaCutoff;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in vec4 vertexColor;
in vec2 uv;
flat in vec3 sourceCell;
in float distanceFromCamera;
out vec4 fragColor;
bool nativeOwns(ivec3 cell) {
    float depth = texelFetch(Sampler3, ivec2(gl_FragCoord.xy), 0).r;
    if (depth >= 1.0) return false;
    vec2 ndc = (gl_FragCoord.xy - NativeViewport.xy) / NativeViewport.zw * 2.0 - 1.0;
    vec4 position = NativeToWorld * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    vec3 point = position.xyz / position.w + NativeOrigin;
    vec3 low = vec3(cell) * 16.0;
    // Shared boundary planes belong to either adjacent section; native geometry wins there.
    return all(greaterThanEqual(point, low)) && all(lessThanEqual(point, low + vec3(16.0)));
}
void main() {
    vec3 coveragePosition = SectionOffset + (sourceCell + vec3(0.25)) * LodScale;
    ivec3 cell = ivec3(floor(coveragePosition / 16.0));
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(ClipSize)))
            && texelFetch(Sampler1, ivec2(cell.x, cell.y * int(ClipSize.z) + cell.z), 0).r > 0.5
            && (OpaquePass == 0 || OpaquePass == 1 && nativeOwns(cell))) discard;
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    // Native SOLID writes depth without alpha discard; cutout and fluid keep their thresholds.
    if (AlphaCutoff >= 0.0 && (color.a <= 0.0 || color.a < AlphaCutoff)) discard;
    fragColor = linear_fog(color, distanceFromCamera, FogStart, FogEnd, FogColor);
}
