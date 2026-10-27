#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#extension GL_OES_EGL_image_external : enable
#extension GL_OES_EGL_image_external_essl3 : enable
precision highp float;
uniform samplerExternalOES u_texture;

@MATERIAL_UNIFORMS

in vec2 diffuse_coord;
out vec4 outColor;

void main()
{
    vec2 horizontal = dFdx(diffuse_coord) * 0.35;
    vec2 vertical = dFdy(diffuse_coord) * 0.35;
    vec4 color = (
        texture(u_texture, diffuse_coord - horizontal - vertical) +
        texture(u_texture, diffuse_coord + horizontal - vertical) +
        texture(u_texture, diffuse_coord - horizontal + vertical) +
        texture(u_texture, diffuse_coord + horizontal + vertical)
    ) * 0.25;
    color = mix(texture(u_texture, diffuse_coord), color, 0.35);
    outColor = vec4(color.rgb * u_color * u_opacity, color.a * u_opacity);
}