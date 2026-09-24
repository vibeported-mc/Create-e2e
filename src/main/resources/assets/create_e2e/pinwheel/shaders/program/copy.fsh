// Copies the input framebuffer to the output, unchanged.
//
// The smallest post-processing shader there is, so that a test built on it fails for one reason: the
// post path did not carry the picture from one framebuffer to another. Anything that filters or
// blends would also fail when its arithmetic was wrong, which is not what that test is asking.
//
// DiffuseSampler0 is the name Veil gives a stage's input framebuffer.

uniform sampler2D DiffuseSampler0;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(DiffuseSampler0, texCoord);
}
