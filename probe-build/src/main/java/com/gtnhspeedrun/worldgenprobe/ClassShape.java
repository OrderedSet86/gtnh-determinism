package com.gtnhspeedrun.worldgenprobe;

import java.nio.charset.StandardCharsets;

import net.minecraft.launchwrapper.Launch;

/**
 * Reads a class's raw bytes to test which version of a mod is installed, without defining the class.
 *
 * <p>
 * Defining the class instead — with {@code Class.forName} — would load it before its mixins apply, and the mixins
 * would then silently fail to apply. So this asks {@link Launch#classLoader} for the bytes and searches the constant
 * pool, where every field and method name appears as a UTF-8 entry.
 *
 * <p>
 * A constant-pool hit is not proof that the member is declared on this class: a reference to another class's member
 * of the same name also lands there. That is accurate enough for version discrimination, because the names used here
 * are chosen to exist in one mod version and not the other.
 */
public final class ClassShape {

    private ClassShape() {}

    /** Bytes of {@code className}, or null if the class is not on the classpath or cannot be read. */
    public static byte[] bytes(String className) {
        try {
            return Launch.classLoader.getClassBytes(className);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when {@code className} is present and its constant pool names {@code member}. */
    public static boolean hasMember(String className, String member) {
        final byte[] b = bytes(className);
        return b != null && contains(b, member.getBytes(StandardCharsets.UTF_8));
    }

    /** True when {@code className} is present on the classpath at all. */
    public static boolean hasClass(String className) {
        return bytes(className) != null;
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
