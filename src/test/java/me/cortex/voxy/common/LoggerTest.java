package me.cortex.voxy.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggerTest {
    @Test
    void formatsPrimitiveAndNestedArrays() {
        boolean insertClass = Logger.INSERT_CLASS;
        try {
            Logger.INSERT_CLASS = false;
            assertEquals("[1, 2] [x, [3, 4]] NULL",
                    Logger.info(new int[]{1, 2}, new Object[]{"x", new long[]{3, 4}}, null));
        } finally {
            Logger.INSERT_CLASS = insertClass;
        }
    }
}
