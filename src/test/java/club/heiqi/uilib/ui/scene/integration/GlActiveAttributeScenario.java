package club.heiqi.uilib.ui.scene.integration;

import static org.lwjgl.opengl.GL20C.*;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.GlProgramAttribute;
import club.heiqi.uilib.host.client.gl.Lwjgl3GlApi;
import club.heiqi.uilib.testing.AngelicaAttributeQuery;
import club.heiqi.uilib.testing.GlfwTestEnvironment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** 真实GL与实际Angelica方法体共同验证属性元数据，保护Iris保存/恢复顶点属性的入口。 */
public final class GlActiveAttributeScenario {
    private static final String[] NAMES = {"a", "mc_Entity", "uilibAttributeWithLongName"};
    private static final int[] TYPES = {GL_FLOAT, GL_FLOAT_VEC4, GL_FLOAT_VEC2};

    public static void main(String[] jars) throws Exception {
        try (GlfwTestEnvironment host = new GlfwTestEnvironment();
                GlfwTestEnvironment.Window window = host.window(32, 32, "属性查询回归", GlfwTestEnvironment.Profile.CORE, 3)) {
            int program = program();
            try {
                verify(new Lwjgl3GlApi(), program, "native");
                AssertionError failures = null;
                for (String jar : jars) {
                    AngelicaAttributeQuery actual = new AngelicaAttributeQuery(Paths.get(jar));
                    probe(actual, program);
                    try {
                        verify(actual.api, program, jar);
                    } catch (RuntimeException | AssertionError failure) {
                        System.err.println("ATTRIBUTE_METADATA_FAIL host=" + jar);
                        failure.printStackTrace();
                        if (failures == null) failures = new AssertionError("实际宿主属性查询失败");
                        failures.addSuppressed(failure);
                    }
                }
                if (failures != null) throw failures;
                require(glGetError() == GL_NO_ERROR, "属性查询产生GL错误");
                System.out.println("GL_ACTIVE_ATTRIBUTE_PASS hosts=" + (jars.length + 1));
            } finally {
                glDeleteProgram(program);
            }
        }
    }

    private static IntBuffer integer() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static void probe(AngelicaAttributeQuery actual, int program) throws Exception {
        int maximum = glGetProgrami(program, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
        for (int index = 0; index < NAMES.length; index++) {
            ByteBuffer name = ByteBuffer.allocateDirect(maximum);
            IntBuffer length = integer(), size = integer(), type = integer();
            actual.query.getMethod("glGetActiveAttrib", int.class, int.class, IntBuffer.class,
                    IntBuffer.class, IntBuffer.class, ByteBuffer.class).invoke(null, program, index, length, size, type, name);
            // 两版真实后端都使用相对put，limit保持容量；原生完整缓冲调用则保持position为0。
            require(name.position() == length.get(0), "实际宿主未命中相对写入路径");
            require(name.limit() == maximum, "实际宿主改变了输出limit");
            System.out.println("ACTUAL_BUFFER index=" + index + " position=" + name.position()
                    + " limit=" + name.limit() + " length=" + length.get(0));
        }
    }

    private static void verify(GlApi api, int program, String host) {
        require(glGetProgrami(program, GL_ACTIVE_ATTRIBUTES) == NAMES.length, "真实program未保留全部属性");
        Set<String> names = new HashSet<String>();
        // 连续两次枚举覆盖复用的size/type缓冲区，而非只验证第一次查询。
        for (int pass = 0; pass < 2; pass++) {
            names.clear();
            for (int index = NAMES.length - 1; index >= 0; index--) {
                GlProgramAttribute attribute = api.glGetActiveAttribute(program, index);
                int expected = -1;
                for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(attribute.name)) expected = i;
                require(expected >= 0, "属性名错误：" + attribute.name);
                require(names.add(attribute.name), "属性名重复：" + attribute.name);
                require(attribute.location == expected + 2, "属性location错误：" + attribute.name);
                require(attribute.type == TYPES[expected], "属性type错误：" + attribute.name);
                require(attribute.size == 1, "属性size错误：" + attribute.name);
            }
        }
        System.out.println("ATTRIBUTE_METADATA_PASS host=" + host);
    }

    private static int program() {
        int vertex = shader(GL_VERTEX_SHADER, "#version 330 core\n"
                + "in float a; in vec4 mc_Entity; in vec2 uilibAttributeWithLongName;\n"
                + "void main(){gl_Position=mc_Entity+vec4(uilibAttributeWithLongName,a,0.0);}");
        int fragment = 0, program = 0;
        try {
            fragment = shader(GL_FRAGMENT_SHADER, "#version 330 core\nout vec4 color; void main(){color=vec4(1.0);}");
            program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            for (int i = 0; i < NAMES.length; i++) glBindAttribLocation(program, i + 2, NAMES[i]);
            glLinkProgram(program);
            require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
            return program;
        } catch (Throwable failure) {
            if (program != 0) glDeleteProgram(program);
            throw failure;
        } finally {
            glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
        }
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String message = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new AssertionError(message);
        }
        return shader;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
