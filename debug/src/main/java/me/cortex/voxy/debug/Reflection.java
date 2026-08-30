package me.cortex.voxy.debug;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class Reflection {
    private Reflection() {}

    static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException exception) {
                return null;
            }
        }
        return null;
    }

    static Object invoke(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException exception) {
                return null;
            }
        }
        return null;
    }

    static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof AtomicInteger number) return number.get();
        if (value instanceof AtomicLong number) return number.get();
        return 0;
    }
}
