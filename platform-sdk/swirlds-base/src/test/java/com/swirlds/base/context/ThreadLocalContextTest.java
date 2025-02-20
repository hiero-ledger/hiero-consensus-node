// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.context;

import com.swirlds.base.context.internal.ThreadLocalContext;
import com.swirlds.base.test.fixtures.context.WithContext;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WithContext
public class ThreadLocalContextTest {

    @Test
    void testNullKeyOrValue() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, "value"));
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, 1));
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, 1L));
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, 1.0D));
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, 1.0F));
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, true));
        Assertions.assertThrows(NullPointerException.class, () -> context.add("foo", null));
        Assertions.assertThrows(NullPointerException.class, () -> context.add(null, null));
    }

    @Test
    void testAllPut() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();

        // when
        context.add("key-string", "value");
        context.add("key-int", 1);
        context.add("key-long", 1L);
        context.add("key-double", 1.0D);
        context.add("key-float", 1.0F);
        context.add("key-boolean", true);

        // then
        final Map<String, String> contextMap = context.getContextMap();
        Assertions.assertEquals(6, contextMap.size());
        Assertions.assertEquals("value", contextMap.get("key-string"));
        Assertions.assertEquals("1", contextMap.get("key-int"));
        Assertions.assertEquals("1", contextMap.get("key-long"));
        Assertions.assertEquals("1.0", contextMap.get("key-double"));
        Assertions.assertEquals("1.0", contextMap.get("key-float"));
        Assertions.assertEquals("true", contextMap.get("key-boolean"));
    }

    @Test
    void testOverwrite() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();

        // when
        context.add("key", "a");
        context.add("key", "b");
        context.add("key", "c");

        // then
        final Map<String, String> contextMap = context.getContextMap();
        Assertions.assertEquals(1, contextMap.size());
        Assertions.assertEquals("c", contextMap.get("key"));
    }

    @Test
    void testRemove() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();

        // when
        context.add("key", "a");
        context.remove("key");

        // then
        final Map<String, String> contextMap = context.getContextMap();
        Assertions.assertEquals(0, contextMap.size());
    }

    @Test
    void testRemoveNullKey() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> context.remove(null));
    }

    @Test
    void testClear() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();
        context.add("key", "a");
        context.add("key-2", "a");

        // when
        context.clear();

        // then
        final Map<String, String> contextMap = context.getContextMap();
        Assertions.assertEquals(0, contextMap.size());
    }

    @Test
    void testAutocloseable() {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();
        AutoCloseable closeable = context.add("key", "a");

        // when
        Assertions.assertDoesNotThrow(() -> closeable.close());

        // then
        final Map<String, String> contextMap = context.getContextMap();
        Assertions.assertEquals(0, contextMap.size());
    }

    @Test
    void testOnDifferentThreads() throws Exception {
        // given
        ThreadLocalContext context = ThreadLocalContext.getInstance();
        ExecutorService executor1 = Executors.newSingleThreadExecutor();
        ExecutorService executor2 = Executors.newSingleThreadExecutor();
        ExecutorService executor3 = Executors.newSingleThreadExecutor();

        // when
        final Map<String, String> mapFromExecutor1 = executor1
                .submit(() -> {
                    context.add("key", "a1");
                    return context.getContextMap();
                })
                .get();

        final Map<String, String> mapFromExecutor2 = executor2
                .submit(() -> {
                    context.add("key", "a2");
                    return context.getContextMap();
                })
                .get();
        final Map<String, String> mapFromExecutor3 = executor3
                .submit(() -> {
                    return context.getContextMap();
                })
                .get();

        // then
        Assertions.assertNotNull(mapFromExecutor1);
        Assertions.assertEquals(1, mapFromExecutor1.size());
        Assertions.assertEquals("a1", mapFromExecutor1.get("key"));

        Assertions.assertNotNull(mapFromExecutor2);
        Assertions.assertEquals(1, mapFromExecutor2.size());
        Assertions.assertEquals("a2", mapFromExecutor2.get("key"));

        Assertions.assertNotNull(mapFromExecutor3);
        Assertions.assertEquals(0, mapFromExecutor3.size());
    }
}
