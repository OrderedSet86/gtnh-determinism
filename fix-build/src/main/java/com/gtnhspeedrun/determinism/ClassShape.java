package com.gtnhspeedrun.determinism;

import java.nio.charset.StandardCharsets;

import net.minecraft.launchwrapper.Launch;

/**
 * Reads a class's raw bytes to tell which version of a mod is installed, without defining the class.
 *
 * <p>
 * Defining it instead — with {@code Class.forName} — would load the class before its mixins apply, and those mixins
 * would then silently fail to apply. So this asks {@link Launch#classLoader} for the bytes and searches the constant
 * pool, where every field and method name appears as a UTF-8 entry.
 *
 * <p>
 * A constant-pool hit does not prove the member is declared on this class; a reference to some other class's member
 * of the same name lands there too. That is accurate enough to pick between known mod versions, which is all this
 * is used for. Pick names that exist in one version and not the other, and avoid a needle that is a substring of
 * an unrelated name — {@code "VeinPlacement"} also matches {@code resolveVeinPlacement}.
 *
 * <p>
 * <b>Do not "upgrade" this to an ASM {@code ClassReader} walk.</b> An exact method-table scan looks strictly
 * better than a substring search, and is strictly worse here. GregTech ships as a multi-release jar (its
 * buildscript sets {@code jvmDowngraderMultiReleaseVersions = 17,21,25}), so on a modern JVM the entry resolved
 * below is {@code META-INF/versions/25/...} at a class-file major version newer than the ASM bundled with Mixin
 * accepts — {@code ClassReader} would throw {@code IllegalArgumentException: Unsupported class file major
 * version} at init, on exactly the modern-Java setups lwjgl3ify exists to serve. A raw byte scan is version
 * agnostic. Member names also survive downgrading: JVMDowngrader de-records and de-lambdas but does not rename.
 *
 * <p>
 * The multi-release layout is otherwise harmless because the probe and the binder read the same entry by
 * construction: {@code LaunchClassLoader.getClassBytes} resolves through {@code URLClassPath}, which since JDK 9
 * opens jars at {@code JarFile.runtimeVersion()}, and Mixin's LaunchWrapper service sources its bytes through the
 * same {@link Launch#classLoader}. Whatever variant this sees is the variant the mixin will bind against.
 *
 * <p>
 * One honest limit: these are PRE-transformer bytes. If another coremod injected or removed one of these members,
 * the probe and the binder would disagree. Negligible for the GregTech members probed here, but it is the edge of
 * what the technique can promise.
 */
public final class ClassShape {

    private ClassShape() {}

    /** Bytes of {@code className}, or null if it is not on the classpath or cannot be read. */
    public static byte[] bytes(String className) {
        try {
            return Launch.classLoader.getClassBytes(className);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when {@code className} is present on the classpath. */
    public static boolean hasClass(String className) {
        return bytes(className) != null;
    }

    /** True when {@code className} is present and its constant pool names {@code member}. */
    public static boolean hasMember(String className, String member) {
        final byte[] b = bytes(className);
        return b != null && contains(b, member.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Where {@code className} was loaded from, or null if it cannot be located.
     *
     * <p>
     * The URL carries the jar filename — {@code .../mods/gregtech-5.09.52.594.jar!/gregtech/common/...} — which is
     * the one thing worth having in a log line when an unrecognised mod version is refused: it is what a user
     * pastes into a bug report, and it identifies the build without trusting a version string we cannot parse.
     */
    public static String sourceOf(String className) {
        try {
            final java.net.URL url = Launch.classLoader.findResource(className.replace('.', '/') + ".class");
            return url == null ? null : url.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
