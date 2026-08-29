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
 * of the same name lands there too. That is accurate enough to pick between two known mod versions, which is all
 * this is used for. Pick names that exist in one version and not the other.
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
