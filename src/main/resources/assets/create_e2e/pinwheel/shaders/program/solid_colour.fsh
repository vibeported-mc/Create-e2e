// A whole-screen fill in one colour, for testing that a Veil shader program can be drawn with.
//
// Deliberately the smallest program that still exercises everything: it declares a loose uniform,
// so it has a gathered block on a backend with no loose uniforms, and it reads no textures, so a
// failure is about the program and the pass rather than about texture binding.
//
// The vertex stage is veil:blit_screen, which builds an oversized triangle from gl_VertexID and
// needs no vertex buffer at all.

uniform vec4 Colour;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = Colour;
}
