package me.cortex.voxy.client.core.gl.shader;

/** Headless stand-in for the two GL program deletions at the end of the real stop method. */
public final class ShutdownShaderBehaviorTest extends Shader {
    public int frees;
    public ShutdownShaderBehaviorTest() { super(0); }
    @Override public void free() {
        if (++frees != 1) throw new AssertionError("shader freed twice");
        super.free0();
    }
}
