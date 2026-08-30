package me.cortex.voxy.client.core.gl.shader;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glUseProgram;

public class Shader extends TrackedObject {
    private final int id;
    Shader(int program) {
        id = program;
    }

    public int id() {
        return this.id;
    }

    public void bind() {
        glUseProgram(this.id);
    }

    public void free() {
        super.free0();
        glDeleteProgram(this.id);
    }

    @SafeVarargs
    public static Builder<Shader> make(BiFunction<ShaderType, String, String>... processor) {
        return makeInternal((a,b)->new Shader(b), processor);
    }

    @SafeVarargs
    public static Builder<AutoBindingShader> makeAuto(BiFunction<ShaderType, String, String>... processor) {
        return makeInternal(AutoBindingShader::new, processor);
    }


    static <T extends Shader> Builder<T> makeInternal(BiFunction<Builder<T>, Integer, T> constructor, BiFunction<ShaderType, String, String>[] processors) {
        BiFunction<ShaderType, String, String> applicator = (type,source)->source;
        for (BiFunction<ShaderType, String, String> processor : processors) {
            BiFunction<ShaderType, String, String> finalApplicator = applicator;
            applicator = (type, source) -> finalApplicator.apply(type, processor.apply(type, source));
        }
        return new Builder<>(constructor, applicator);
    }

    public static class Builder <T extends Shader> {
        final Map<String, String> defines = new HashMap<>();
        final Map<String, String> replacements = new LinkedHashMap<>();
        private final Map<ShaderType, String> sources = new HashMap<>();
        private final BiFunction<ShaderType, String, String> processor;
        private final BiFunction<Builder<T>, Integer, T> constructor;
        private Builder(BiFunction<Builder<T>, Integer, T> constructor, BiFunction<ShaderType, String, String> processor) {
            this.constructor = constructor;
            this.processor = processor;
        }

        public Builder<T> clone() {
            var clone = new Builder<>(this.constructor, this.processor);
            clone.defines.putAll(this.defines);
            clone.sources.putAll(this.sources);
            clone.replacements.putAll(this.replacements);
            return clone;
        }

        public Builder<T> define(String name) {
            this.defines.put(name, "");
            return this;
        }

        public Builder<T> defineIf(String name, boolean condition) {
            if (condition) {
                this.defines.put(name, "");
            }
            return this;
        }

        public Builder<T> defineIf(String name, boolean condition, int value) {
            if (condition) {
                this.defines.put(name, Integer.toString(value));
            }
            return this;
        }

        public Builder<T> define(String name, int value) {
            this.defines.put(name, Integer.toString(value));
            return this;
        }

        public Builder<T> define(String name, float value) {
            this.defines.put(name, Float.toString(value)+"f");
            return this;
        }

        public Builder<T> define(String name, String value) {
            this.defines.put(name, value);
            return this;
        }

        public Builder<T> replace(String value, String replacement) {
            this.replacements.put(value, replacement);
            return this;
        }

        public Builder<T> add(ShaderType type, String id) {
            this.addSource(type, ShaderLoader.parse(id));
            return this;
        }

        public Builder<T> addSource(ShaderType type, String source) {
            this.sources.put(type, this.processor.apply(type, source));
            return this;
        }

        public Builder<T> apply(Consumer<Builder<T>> applyer) {
            applyer.accept(this);
            return this;
        }


        private int compileToProgram() {
            int program = GL20C.glCreateProgram();
            int[] shaders = new int[this.sources.size()];
            {
                String defs = this.defines.entrySet().stream().map(a->"#define " + a.getKey() + " " + a.getValue() + "\n").collect(Collectors.joining());
                int i = 0;
                for (var entry : this.sources.entrySet()) {
                    String src = entry.getValue();

                    //Inject defines
                    src = src.substring(0, src.indexOf('\n')+1) +
                            defs
                            + src.substring(src.indexOf('\n')+1);

                    for (var replacement : this.replacements.entrySet()) {
                        src = src.replace(replacement.getKey(), replacement.getValue());
                    }

                    shaders[i++] = createShader(entry.getKey(), src);
                }
            }

            for (int i : shaders) {
                GL20C.glAttachShader(program, i);
            }
            GL20C.glLinkProgram(program);
            for (int i : shaders) {
                GL20C.glDetachShader(program, i);
                GL20C.glDeleteShader(i);
            }
            printProgramLinkLog(program);
            verifyProgramLinked(program);
            return program;
        }

        public T compile() {
            this.defineIf("IS_INTEL", Capabilities.INSTANCE.isIntel);
            this.defineIf("IS_WINDOWS", ThreadUtils.isWindows);
            return this.constructor.apply(this, this.compileToProgram());
        }

        private static void printProgramLinkLog(int program) {
            String log = GL20C.glGetProgramInfoLog(program);

            if (!log.isEmpty()) {
                Logger.error(log);
            }
        }

        private static void verifyProgramLinked(int program) {
            int result = GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS);

            if (result != GL20C.GL_TRUE) {
                throw new RuntimeException("Shader program linking failed, see log for details");
            }
        }

        private static int createShader(ShaderType type, String src) {
            int shader = GL20C.glCreateShader(type.gl);
            {//https://github.com/CaffeineMC/sodium/blob/fc42a7b19836c98a35df46e63303608de0587ab6/src/main/java/net/caffeinemc/mods/sodium/client/gl/shader/ShaderWorkarounds.java
                long ptr = MemoryUtil.memAddress(MemoryUtil.memUTF8(src, true));
                try (var stack = MemoryStack.stackPush()) {
                    GL20C.nglShaderSource(shader, 1, stack.pointers(ptr).address0(), 0);
                }
                MemoryUtil.nmemFree(ptr);
            }
            GL20C.glCompileShader(shader);
            String log = GL20C.glGetShaderInfoLog(shader);

            if (!log.isEmpty()) {
                Logger.warn(log);
            }

            int result = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);

            if (result != GL20C.GL_TRUE) {
                GL20C.glDeleteShader(shader);
                try {
                    Files.writeString(Path.of("SHADER_DUMP.txt"), src);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                throw new RuntimeException("Shader compilation failed of type " + type.name() + ", see log for details, dumped shader");
            }

            return shader;
        }
    }
}
