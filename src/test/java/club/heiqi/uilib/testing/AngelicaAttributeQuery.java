package club.heiqi.uilib.testing;

import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.Lwjgl3GlApi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/** 只隔离本次查询所需的真实宿主方法，保留其GL调用及缓冲区操作，不加载MC初始化。 */
public final class AngelicaAttributeQuery extends ClassLoader {
    public static final String DESCRIPTOR =
            "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/ByteBuffer;)V";
    private static final String GLSM = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private static final String MANAGER = "com/gtnewhorizons/angelica/glsm/backend/BackendManager";
    private static final String BACKEND = "com/gtnewhorizons/angelica/glsm/backend/RenderBackend";
    private static final String IMPLEMENTATION = "com/gtnewhorizons/angelica/lwjgl3/Lwjgl3GLRenderBackend";
    private static final String QUERY = "club/heiqi/uilib/testing/ActualAngelicaAttributeQuery";
    private static final String BINDING = "club/heiqi/uilib/host/client/gl/Lwjgl3GlApi";
    private static final String ISOLATED_BINDING = "club/heiqi/uilib/testing/ActualHostGlApi";
    public final Class<?> query;
    public final GlApi api;

    public AngelicaAttributeQuery(Path jar) throws Exception {
        super(AngelicaAttributeQuery.class.getClassLoader());
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, QUERY, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "RENDER_BACKEND", "L" + QUERY + ";", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        java.util.Map<String, String> owners = new java.util.HashMap<String, String>();
        for (String owner : new String[] {GLSM, MANAGER, BACKEND, IMPLEMENTATION}) owners.put(owner, QUERY);
        SimpleRemapper names = new SimpleRemapper(owners);
        try (JarFile input = new JarFile(jar.toFile())) {
            copyMethod(input, GLSM, "glGetActiveAttrib", writer, names);
            copyMethod(input, IMPLEMENTATION, "getActiveAttrib", writer, names);
        }
        writer.visitEnd();
        byte[] queryBytes = writer.toByteArray();
        query = defineClass(QUERY.replace('/', '.'), queryBytes, 0, queryBytes.length);
        query.getField("RENDER_BACKEND").set(null, query.getConstructor().newInstance());

        ClassWriter binding = new ClassWriter(0);
        try (InputStream input = Lwjgl3GlApi.class.getResourceAsStream("Lwjgl3GlApi.class")) {
            new ClassReader(input).accept(new ClassRemapper(binding, new SimpleRemapper(BINDING, ISOLATED_BINDING)) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            // 只复现生产转换的完整缓冲重载；其余绑定继续使用真实GL。
                            if (owner.equals("org/lwjgl/opengl/GL20C") && name.equals("glGetActiveAttrib") && descriptor.equals(DESCRIPTOR)) owner = QUERY;
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            }, 0);
        }
        byte[] bytes = binding.toByteArray();
        api = (GlApi) defineClass(ISOLATED_BINDING.replace('/', '.'), bytes, 0, bytes.length).getConstructor().newInstance();
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))) hash.append(String.format("%02x", value & 255));
        System.out.println("ACTUAL_ANGELICA jar=" + jar + " sha256=" + hash);
    }

    private static void copyMethod(JarFile jar, String owner, final String method, final ClassWriter output, final SimpleRemapper names) throws IOException {
        ZipEntry entry = null;
        for (String prefix : new String[] {"META-INF/versions/21/", "META-INF/versions/17/", ""}) {
            entry = jar.getEntry(prefix + owner + ".class");
            if (entry != null) break;
        }
        if (entry == null) throw new IOException("实际发行包缺少 " + owner);
        final boolean[] copied = {false};
        try (InputStream input = jar.getInputStream(entry)) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals(method) || !descriptor.equals(DESCRIPTOR)) return null;
                    copied[0] = true;
                    return new MethodRemapper(output.visitMethod(access, name, descriptor, signature, exceptions), names);
                }
            }, 0);
        }
        if (!copied[0]) throw new IOException("实际发行包缺少查询方法 " + owner + "." + method);
        System.out.println("ACTUAL_METHOD " + entry.getName() + "#" + method);
    }
}
