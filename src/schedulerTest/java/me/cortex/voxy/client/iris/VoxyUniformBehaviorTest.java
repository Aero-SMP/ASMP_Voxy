package me.cortex.voxy.client.iris;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** The production matrix callbacks, with only the absent render viewport as the boundary. */
public final class VoxyUniformBehaviorTest {
    public static void run() {
        Matrix4f identity = new Matrix4f();
        List<Supplier<Matrix4fc>> unavailable = List.of(
                () -> VoxyUniforms.getProjection(null),
                () -> VoxyUniforms.getModelView(null),
                () -> VoxyUniforms.getViewProjection(null));
        for (Supplier<Matrix4fc> parent : unavailable) {
            // Includes the exact PreviousMat -> getProjection crash, plus all eight siblings.
            for (Supplier<Matrix4fc> callback : List.of(parent, new VoxyUniforms.Inverted(parent),
                    new VoxyUniforms.PreviousMat(parent))) {
                for (int frame = 0; frame < 3; frame++) {
                    Matrix4fc value = callback.get();
                    check(value.isFinite() && value.equals(identity, 0), "absent viewport uniform is invalid");
                    ((Matrix4f) value).zero(); // No callback may hand out shared mutable fallback state.
                }
            }
        }

        AtomicReference<Matrix4fc> current = new AtomicReference<>(identity);
        var previous = new VoxyUniforms.PreviousMat(current::get);
        var inverse = new VoxyUniforms.Inverted(current::get);
        check(previous.get().equals(identity, 0), "initial previous matrix is not identity");
        Matrix4f resumed = new Matrix4f().perspective(1.1f, 1.6f, 16, 48000);
        current.set(resumed);
        check(previous.get().equals(identity, 0), "first resumed frame lost previous state");
        Matrix4fc expectedInverse = new Matrix4f(resumed).invert();
        check(inverse.get().equals(expectedInverse, 0), "resumed inverse matrix is wrong");
        Matrix4f captured = new Matrix4f(resumed);
        resumed.zero();
        current.set(VoxyUniforms.getProjection(null));
        check(previous.get().equals(captured, 0), "previous matrix aliases current viewport state");
        check(previous.get().equals(identity, 0), "suspended matrix did not return to safe fallback");
        System.out.println("Iris unavailable-viewport uniform regression tests passed");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
