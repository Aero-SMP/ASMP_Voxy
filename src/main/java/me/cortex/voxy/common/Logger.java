package me.cortex.voxy.common;

import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Consumer;

public class Logger {
    public static boolean INSERT_CLASS = true;
    public static boolean SHUTUP = false;
    public static boolean SHUTUP_INFO = false;
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Voxy");
    private static volatile Consumer<String> errorSink = ignored -> {};

    public static void setErrorSink(Consumer<String> sink) {
        errorSink = Objects.requireNonNull(sink);
    }

    private static String callClsName() {
        String className = "";
        if (INSERT_CLASS) {
            var stackEntry = new Throwable().getStackTrace()[2];
            className = stackEntry.getClassName();
            var builder = new StringBuilder();
            var parts = className.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                var part = parts[i];
                if (i < parts.length-1) {//-2
                    builder.append(part.charAt(0)).append(part.charAt(part.length()-1));
                } else {
                    builder.append(part);
                }
                if (i!=parts.length-1) {
                    builder.append(".");
                }
            }
            className = builder.toString();
        }
        return className;
    }

    public static void error(Object... args) {
        if (!SHUTUP) {
            String message = format(args);
            LOGGER.error(message, throwable(args));
            errorSink.accept(message);
        }
    }

    public static void warn(Object... args) {
        if (!SHUTUP) {
            LOGGER.warn(format(args), throwable(args));
        }
    }

    public static String info(Object... args) {
        if (SHUTUP||SHUTUP_INFO) {
            return "";
        }
        String message = format(args);
        LOGGER.info(message, throwable(args));
        return message;
    }

    private static Throwable throwable(Object[] args) {
        for (Object arg : args) if (arg instanceof Throwable throwable) return throwable;
        return null;
    }

    private static String format(Object[] args) {
        StringJoiner message = new StringJoiner(" ", INSERT_CLASS ? "["+callClsName()+"]: " : "", "");
        for (Object arg : args) message.add(objToString(arg));
        return message.toString();
    }

    private static String objToString(Object obj) {
        if (obj == null) return "NULL";
        if (obj.getClass().isArray()) {
            String value = Arrays.deepToString(new Object[]{obj});
            return value.substring(1, value.length()-1);
        }
        return obj.toString();
    }
}
