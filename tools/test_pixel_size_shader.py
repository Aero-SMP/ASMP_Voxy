#!/usr/bin/env python3
"""Dispatch the production traversal shader on surfaceless EGL (Mesa is supported).

No Python OpenGL package is required. Imports and build defines match the Java
shader loader; a test-only output buffer observes the unchanged score/bucket.
Run: MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460 python3 tools/test_pixel_size_shader.py
"""
import ctypes as C
import math
from pathlib import Path
import re
import struct

P, I, U, F = C.c_void_p, C.c_int, C.c_uint, C.c_float
EGL = C.CDLL("libEGL.so.1")
GL = C.CDLL("libGL.so.1")


def function(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn


def context():
    display = function(EGL, "eglGetPlatformDisplay", P, U, P, P)(0x31DD, None, None)
    major, minor = I(), I()
    assert function(EGL, "eglInitialize", U, P, P, P)(display, C.byref(major), C.byref(minor)), "EGL initialization failed"
    assert function(EGL, "eglBindAPI", U, U)(0x30A2), "OpenGL API unavailable"
    attributes = (I * 5)(0x3040, 8, 0x3033, 1, 0x3038)
    config, count = P(), I()
    assert function(EGL, "eglChooseConfig", U, P, P, P, I, P)(display, attributes, C.byref(config), 1, C.byref(count)) and count.value
    version = (I * 7)(0x3098, 4, 0x30FB, 5, 0x30FD, 1, 0x3038)
    ctx = function(EGL, "eglCreateContext", P, P, P, P, P)(display, config, None, version)
    assert ctx and function(EGL, "eglMakeCurrent", U, P, P, P, P)(display, None, None, ctx), "GL context unavailable"
    return display, ctx


def source(path):
    root = Path(__file__).resolve().parents[1] / "src/main/resources/assets/voxy/shaders"
    text = (root / path).read_text()
    return re.sub(r"#import <voxy:([^>]+)>", lambda match: source(match[1]), text)


def program(taa, reverse):
    defines = {"USE_ZERO_ONE_DEPTH": 1, "LOCAL_SIZE_BITS": 5, "MAX_ITERATIONS": 5, "DETAIL_BUCKET_COUNT": 32,
               "ACTIONS_PER_BUCKET": 256, "HIZ_BINDING": 0, "SCENE_UNIFORM_BINDING": 1,
               "DETAIL_ACTION_BINDING": 2, "RENDER_QUEUE_BINDING": 3, "NODE_DATA_BINDING": 4,
               "NODE_QUEUE_INDEX_BINDING": 5, "NODE_QUEUE_META_BINDING": 6,
               "NODE_QUEUE_SOURCE_BINDING": 7, "NODE_QUEUE_SINK_BINDING": 8, "RENDER_TRACKER_BINDING": 9}
    text = source("lod/hierarchical/traversal.comp")
    text = text.replace("#version 460 core", "#version 460 core\n" + "\n".join(f"#define {k} {v}" for k, v in defines.items())
                        + ("\n#define TAA" if taa else "")
                        + ("\n#define USE_REVERSE_Z" if reverse else ""))
    text = text.replace("void main()", "void traversalMain()")
    if taa:
        text += "\nvec2 getTAA() { return vec2(0); }\n"
    text += """
layout(binding = 10, std430) buffer TestObservation { float observedScore; uint observedBucket; };
void main() {
    traversalMain();
    if (gl_GlobalInvocationID.x == 0u) {
        observedScore = projectedDetailScore(); observedBucket = detailScoreBucket(observedScore);
    }
}
"""
    shader = function(GL, "glCreateShader", U, U)(0x91B9)
    encoded = C.c_char_p(text.encode())
    function(GL, "glShaderSource", None, U, I, P, P)(shader, 1, C.byref(encoded), None)
    function(GL, "glCompileShader", None, U)(shader)
    ok, log = I(), C.create_string_buffer(32768)
    function(GL, "glGetShaderiv", None, U, U, P)(shader, 0x8B81, C.byref(ok))
    function(GL, "glGetShaderInfoLog", None, U, I, P, P)(shader, len(log), None, log)
    assert ok.value, log.value.decode()
    result = function(GL, "glCreateProgram", U)()
    function(GL, "glAttachShader", None, U, U)(result, shader)
    function(GL, "glLinkProgram", None, U)(result)
    function(GL, "glGetProgramiv", None, U, U, P)(result, 0x8B82, C.byref(ok))
    function(GL, "glGetProgramInfoLog", None, U, I, P, P)(result, len(log), None, log)
    function(GL, "glDeleteShader", None, U)(shader)
    assert ok.value, log.value.decode()
    return result


class Traversal:
    def __init__(self, taa=False, reverse=False):
        self.program = program(taa, reverse)
        self.reverse = reverse
        self.buffers = {}
        texture = U()
        function(GL, "glGenTextures", None, I, P)(1, C.byref(texture))
        self.texture = texture
        function(GL, "glBindTexture", None, U, U)(0x0DE1, texture)
        depth = F(0 if reverse else 1)
        function(GL, "glTexImage2D", None, U, I, I, I, I, I, U, U, P)(0x0DE1, 0, 0x822E, 1, 1, 0, 0x1903, 0x1406, C.byref(depth))
        for name, value in [(0x2801, 0x2600), (0x2800, 0x2600)]:
            function(GL, "glTexParameteri", None, U, U, I)(0x0DE1, name, value)
        names = (C.c_char_p * 4)(b"viewportArea", b"detailThresholdScale", b"finalPass", b"coarsenGraceFrames")
        indices, offsets = (U * 4)(), (I * 4)()
        function(GL, "glGetUniformIndices", None, U, I, P, P)(self.program, 4, names, indices)
        function(GL, "glGetActiveUniformsiv", None, U, I, P, U, P)(self.program, 4, indices, 0x8A3B, offsets)
        assert list(offsets) == [92, 216, 208, 212], list(offsets)
        block = function(GL, "glGetUniformBlockIndex", U, U, C.c_char_p)(self.program, b"SceneUniform")
        size = I()
        function(GL, "glGetActiveUniformBlockiv", None, U, U, U, P)(self.program, block, 0x8A40, C.byref(size))
        assert size.value == 224, size.value

    def buffer(self, binding, data, uniform=False):
        target = 0x8A11 if uniform else 0x90D2
        if binding not in self.buffers:
            value = U()
            function(GL, "glGenBuffers", None, I, P)(1, C.byref(value))
            self.buffers[binding] = value
        value = self.buffers[binding]
        function(GL, "glBindBuffer", None, U, U)(target, value)
        raw = C.create_string_buffer(bytes(data))
        function(GL, "glBufferData", None, U, C.c_ssize_t, P, U)(target, len(data), raw, 0x88E8)
        function(GL, "glBindBufferBase", None, U, U, U)(target, binding, value)

    def read(self, binding, length):
        function(GL, "glBindBuffer", None, U, U)(0x90D2, self.buffers[binding])
        raw = C.create_string_buffer(length)
        function(GL, "glGetBufferSubData", None, U, C.c_ssize_t, C.c_ssize_t, P)(0x90D2, 0, length, raw)
        return raw.raw

    def run(self, score=32, target=64, children=False, dormant=False, mesh=7, lod=1,
            terminal=False, final=True, width=1024, height=1024, projection=None, scale=1):
        side = 32 << lod
        matrix = [0.] * 16
        matrix[0] = matrix[5] = 4 * score / (1.25 * side * math.sqrt(width * height)) * scale
        matrix[14], matrix[15] = 0.5, 1
        camera = (side / 2, side / 2, 0)
        if projection is not None:
            f = 1 / math.tan(math.radians(projection) / 2)
            matrix = [0.] * 16
            matrix[0], matrix[5], matrix[10], matrix[11], matrix[14] = f * height / width, f, -1.00001, -1, -0.2
            camera = (side / 2, side / 2, 1000)
        uniform = bytearray(224)
        struct.pack_into("<16f", uniform, 0, *matrix)
        struct.pack_into("<3iI3ff", uniform, 64, 0, 0, 0, 65537, *camera, width * height)
        for i in range(6):
            struct.pack_into("<4f", uniform, 96 + i * 16, 0, 0, 0, 1)
        struct.pack_into("<3If2If", uniform, 192, 16, 10, 0, -1, int(final), 120, target * 0.5)
        self.buffer(1, uniform, True)
        self.buffer(2, bytes(128 + 32 * 256 * 16))
        self.buffer(3, bytes(68))
        flags = 2 if terminal else 0
        child = 1 if children is True else 0xFFFFFE if children == "incomplete" else 0xFFFFFF
        self.buffer(4, struct.pack("<4I", lod << 28, 0, mesh | (flags << 24), child))
        metadata = bytearray(80)
        struct.pack_into("<I", metadata, 12, 1)
        self.buffer(6, metadata)
        self.buffer(7, bytes(4))
        self.buffer(8, bytes(32))
        self.buffer(9, struct.pack("<I", (1 << 31) if dormant else 0))
        self.buffer(10, bytes(8))
        function(GL, "glUseProgram", None, U)(self.program)
        function(GL, "glUniform1ui", None, I, U)(5, 0)
        function(GL, "glDispatchCompute", None, U, U, U)(1, 1, 1)
        function(GL, "glMemoryBarrier", None, U)(0xFFFFFFFF)
        function(GL, "glFinish", None)()
        error = function(GL, "glGetError", U)()
        assert error == 0, hex(error)
        counts = struct.unpack("<32I", self.read(2, 128))
        score, bucket = struct.unpack("<fI", self.read(10, 8))
        return {"render": struct.unpack("<I", self.read(3, 4))[0],
                "children": struct.unpack_from("<I", self.read(6, 32), 28)[0],
                "actions": sum(counts), "score": score, "bucket": bucket}

    def close(self):
        for value in self.buffers.values():
            function(GL, "glDeleteBuffers", None, I, P)(1, C.byref(value))
        function(GL, "glDeleteTextures", None, I, P)(1, C.byref(self.texture))
        function(GL, "glDeleteProgram", None, U)(self.program)


def check_shader(shader):
    for score in [1, 12, 14, 16, 28, 32, 36, 64, 128, 160, 256]:
        results = [shader.run(score=score, target=t) for t in [28, 64, 256]]
        assert results[0]["actions"] >= results[1]["actions"] >= results[2]["actions"], results
        assert len({(r["score"], r["bucket"]) for r in results}) == 1, "quality changed priority"
    for target in [28, 64, 256]:
        threshold = target / 2
        assert shader.run(score=threshold - 0.01, target=target)["actions"] == 0
        assert shader.run(score=threshold + 0.01, target=target)["actions"] == 1
        for dormant in [False, True]:
            assert shader.run(score=threshold * 1.10 + .01, target=target, children=True, dormant=dormant)["children"] == 1
            assert shader.run(score=threshold * .90 - .01, target=target, children=True, dormant=dormant)["render"] == 1
            middle = shader.run(score=threshold, target=target, children=True, dormant=dormant)
            assert middle["render"] == int(dormant) and middle["children"] == int(not dormant), middle
        for mesh in [0xFFFFFF, 0xFFFFFE]:
            assert shader.run(score=1, target=target, mesh=mesh, children=True, dormant=True)["children"] == 1
        assert shader.run(score=256, target=target, lod=0)["actions"] == 0
        assert shader.run(score=256, target=target, terminal=True)["actions"] == 0
        assert shader.run(score=256, target=target, final=False)["actions"] == 0
        incomplete = shader.run(score=256, target=target, children="incomplete")
        assert incomplete["render"] == 1 and incomplete["children"] == 0
    normal = shader.run(projection=70, width=1920, height=1080)
    zoom = shader.run(projection=35, width=1920, height=1080)
    scaled = shader.run(projection=70, width=960, height=540)
    assert zoom["score"] > normal["score"] > scaled["score"], (normal, zoom, scaled)
    assert abs(normal["score"] / scaled["score"] - 2) < .01, "render scaling not measured in render pixels"
    square = shader.run(projection=70, width=1080, height=1080)
    assert abs(square["score"] / normal["score"] - 1) < .01, "aspect ratio distorted projected section size"
    assert shader.run(score=32, target=28, children=True)["children"] == 1
    assert shader.run(score=32, target=256, children=True)["render"] == 1
    assert shader.run(score=32, target=28, children=True, dormant=True)["children"] == 1


def main():
    display, ctx = context()
    print(function(GL, "glGetString", C.c_char_p, U)(0x1F02).decode())
    print(function(GL, "glGetString", C.c_char_p, U)(0x1F01).decode())
    try:
        for taa, reverse in [(False, False), (True, False), (False, True), (True, True)]:
            shader = Traversal(taa, reverse)
            try:
                check_shader(shader)
                print(f"PASS actual traversal: TAA={taa}, reverseZ={reverse}, uniform layout, thresholds, fallback, projection, priorities")
            finally:
                shader.close()
    finally:
        function(EGL, "eglMakeCurrent", U, P, P, P, P)(display, None, None, None)
        function(EGL, "eglDestroyContext", U, P, P)(display, ctx)
        function(EGL, "eglTerminate", U, P)(display)


if __name__ == "__main__":
    main()
