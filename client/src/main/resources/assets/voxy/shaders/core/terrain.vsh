#version 150
#moj_import <light.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 SectionOffset;
uniform sampler2D Sampler2;
out vec4 vertexColor;
out vec2 uv;
out vec3 clipPosition;
out float distanceFromCamera;
void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    distanceFromCamera = length(view.xyz);
    clipPosition = Position + SectionOffset;
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    uv = UV0;
}
