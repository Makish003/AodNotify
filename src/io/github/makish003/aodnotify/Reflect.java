package io.github.makish003.aodnotify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal cached reflection for SystemUI internals. */
final class Reflect {
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();

    private Reflect() {
    }

    static Class<?> find(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    static Class<?> findOrNull(String name, ClassLoader cl) {
        try {
            return find(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        String key = c.getName() + '#' + name;
        Field f = FIELDS.get(key);
        if (f != null) {
            return f;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                f = k.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(key);
    }

    static Object get(Object obj, String name) throws ReflectiveOperationException {
        return field(obj.getClass(), name).get(obj);
    }

    static Object getStatic(Class<?> c, String name) throws ReflectiveOperationException {
        return field(c, name).get(null);
    }

    /** Finds a method by name and argument count, walking up the class hierarchy. */
    static Method method(Class<?> c, String name, int argc) throws NoSuchMethodException {
        String key = c.getName() + '#' + name + '/' + argc;
        Method m = METHODS.get(key);
        if (m != null) {
            return m;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method candidate : k.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == argc) {
                    candidate.setAccessible(true);
                    METHODS.put(key, candidate);
                    return candidate;
                }
            }
        }
        for (Method candidate : c.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == argc) {
                METHODS.put(key, candidate);
                return candidate;
            }
        }
        throw new NoSuchMethodException(key);
    }

    static Object call(Object obj, String name, Object... args) throws ReflectiveOperationException {
        return method(obj.getClass(), name, args.length).invoke(obj, args);
    }

    static Object callStatic(Class<?> c, String name, Object... args) throws ReflectiveOperationException {
        Method m = method(c, name, args.length);
        if (!Modifier.isStatic(m.getModifiers())) {
            throw new NoSuchMethodException(c.getName() + '#' + name + " is not static");
        }
        return m.invoke(null, args);
    }
}
