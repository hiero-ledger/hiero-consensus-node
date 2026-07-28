// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import java.util.concurrent.ThreadFactory;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.framework.config.CompositeThreadNamingConfiguration;
import org.hiero.consensus.concurrent.framework.config.FullNameThreadNamingConfiguration;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Node Name Thread Tests")
class NodeNameThreadTests {

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Factory Test")
    void factoryTest() {

        final Thread.UncaughtExceptionHandler exceptionHandler = (a, b) -> {};

        final ThreadGroup group = new ThreadGroup("threadGroup1");
        final ClassLoader classLoader =
                Thread.currentThread().getContextClassLoader().getParent();

        final ThreadConfiguration tc = new ThreadConfiguration(getStaticThreadManager());
        tc.setThreadNamingConfiguration(new NodeThreadNamingConfiguration()
                        .setNodeId(NodeId.of(1234L))
                        .setComponent("pool1")
                        .setThreadName("thread1"))
                .setDaemon(false)
                .setExceptionHandler(exceptionHandler)
                .setThreadGroup(group)
                .setContextClassLoader(classLoader);
        final ThreadFactory factory = tc.buildFactory();

        final Thread thread1 = factory.newThread(() -> {});

        final Thread thread2 = factory.newThread(() -> {});

        assertNotEquals(thread1.getName(), thread2.getName(), "thread names should be unique");
        assertEquals(thread1.isDaemon(), thread2.isDaemon(), "daemon settings should match");
        assertSame(
                thread1.getUncaughtExceptionHandler(),
                thread2.getUncaughtExceptionHandler(),
                "should have same exception handler");
        assertSame(thread1.getThreadGroup(), thread2.getThreadGroup(), "should have same thread group");
        assertSame(thread1.getContextClassLoader(), thread2.getContextClassLoader(), "should have same class loader");
    }

    @Test
    @DisplayName("Naming Tests")
    void namingTests() {

        final Thread thread0 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .build();
        assertEquals("<unnamed>", thread0.getName(), "unexpected thread name");

        final Thread thread1 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new CompositeThreadNamingConfiguration().setComponent("foo"))
                .build();
        assertEquals("<foo: unnamed>", thread1.getName(), "unexpected thread name");

        final Thread thread2 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new CompositeThreadNamingConfiguration()
                        .setComponent("foo")
                        .setThreadName("bar"))
                .build();
        assertEquals("<foo: bar>", thread2.getName(), "unexpected thread name");

        final Thread thread3 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration()
                        .setComponent("foo")
                        .setThreadName("bar")
                        .setNodeId(NodeId.of(1234L)))
                .build();
        assertEquals("<foo: bar 1234>", thread3.getName(), "unexpected thread name");

        final Thread thread4 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration()
                        .setComponent("foo")
                        .setThreadName("bar")
                        .setNodeId(NodeId.of(1234L))
                        .setOtherNodeId(NodeId.of(4321L)))
                .build();
        assertEquals("<foo: bar 1234 to 4321>", thread4.getName(), "unexpected thread name");

        final ThreadFactory factory = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration()
                        .setComponent("foo")
                        .setThreadName("bar")
                        .setNodeId(NodeId.of(1234L))
                        .setOtherNodeId(NodeId.of(4321L)))
                .buildFactory();

        assertEquals("<foo: bar 1234 to 4321 #0>", factory.newThread(null).getName(), "unexpected thread name");
        assertEquals("<foo: bar 1234 to 4321 #1>", factory.newThread(null).getName(), "unexpected thread name");
        assertEquals("<foo: bar 1234 to 4321 #2>", factory.newThread(null).getName(), "unexpected thread name");
        assertEquals("<foo: bar 1234 to 4321 #3>", factory.newThread(null).getName(), "unexpected thread name");
        assertEquals("<foo: bar 1234 to 4321 #4>", factory.newThread(null).getName(), "unexpected thread name");
        assertEquals("<foo: bar 1234 to 4321 #5>", factory.newThread(null).getName(), "unexpected thread name");
    }

    @Test
    @DisplayName("Configuration Mutability Test")
    void configurationMutabilityTest() {
        // Build should make the configuration immutable
        final ThreadConfiguration configuration0 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration());

        assertTrue(configuration0.isMutable(), "configuration should be mutable");

        configuration0.build();
        assertTrue(configuration0.isImmutable(), "configuration should be immutable");

        assertThrows(
                MutabilityException.class,
                () -> configuration0.setThreadNamingConfiguration(new FullNameThreadNamingConfiguration("Abc")),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration0.setThreadGroup(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration0.setDaemon(false), "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration0.setPriority(Thread.MAX_PRIORITY),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration0.setContextClassLoader(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration0.setExceptionHandler(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration0.setRunnable(null), "configuration should be immutable");

        // Build seed should make the configuration immutable
        final ThreadConfiguration configuration1 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration());

        assertTrue(configuration1.isMutable(), "configuration should be mutable");

        configuration1.buildSeed();
        assertTrue(configuration1.isImmutable(), "configuration should be immutable");

        assertThrows(
                MutabilityException.class,
                () -> configuration1.setThreadNamingConfiguration(new FullNameThreadNamingConfiguration("Abc")),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration1.setThreadGroup(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration1.setDaemon(false), "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration1.setPriority(Thread.MAX_PRIORITY),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration1.setContextClassLoader(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration1.setExceptionHandler(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration1.setRunnable(null), "configuration should be immutable");

        // Build factory should make the configuration immutable
        final ThreadConfiguration configuration2 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration());

        assertTrue(configuration2.isMutable(), "configuration should be mutable");

        configuration2.buildFactory();
        assertTrue(configuration2.isImmutable(), "configuration should be immutable");

        assertThrows(
                MutabilityException.class,
                () -> configuration2.setThreadNamingConfiguration(new FullNameThreadNamingConfiguration("Abc")),
                "configuration should be immutable");

        assertThrows(
                MutabilityException.class,
                () -> configuration2.setThreadGroup(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration2.setDaemon(false), "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration2.setPriority(Thread.MAX_PRIORITY),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration2.setContextClassLoader(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration2.setExceptionHandler(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration2.setRunnable(null), "configuration should be immutable");
    }

    @Test
    @DisplayName("Copy Test")
    void copyTest() {

        final ThreadGroup group = new ThreadGroup("myGroup");
        final ClassLoader loader =
                Thread.currentThread().getContextClassLoader().getParent();

        final Thread.UncaughtExceptionHandler exceptionHandler = (t, e) -> {};

        final Runnable runnable = () -> {};

        final ThreadConfiguration configuration = new ThreadConfiguration(getStaticThreadManager())
                .setThreadNamingConfiguration(new NodeThreadNamingConfiguration()
                        .setNodeId(NodeId.of(1234L))
                        .setComponent("component")
                        .setThreadName("name"))
                .setThreadGroup(group)
                .setDaemon(false)
                .setPriority(Thread.MAX_PRIORITY)
                .setContextClassLoader(loader)
                .setExceptionHandler(exceptionHandler)
                .setRunnable(runnable);

        final ThreadConfiguration copy1 = configuration.copy();

        assertSame(configuration.getThreadGroup(), copy1.getThreadGroup(), "copy configuration should match");
        assertEquals(configuration.isDaemon(), copy1.isDaemon(), "copy configuration should match");
        assertEquals(configuration.getPriority(), copy1.getPriority(), "copy configuration should match");
        assertSame(
                configuration.getContextClassLoader(),
                copy1.getContextClassLoader(),
                "copy configuration should match");
        assertSame(configuration.getExceptionHandler(), copy1.getExceptionHandler(), "copy configuration should match");
        assertSame(configuration.getRunnable(), copy1.getRunnable(), "copy configuration should match");

        // It should matter if the original is immutable.
        configuration.build();

        final ThreadConfiguration copy2 = configuration.copy();
        assertTrue(copy2.isMutable(), "copy should be mutable");

        assertSame(configuration.getThreadGroup(), copy2.getThreadGroup(), "copy configuration should match");
        assertEquals(configuration.isDaemon(), copy2.isDaemon(), "copy configuration should match");
        assertEquals(configuration.getPriority(), copy2.getPriority(), "copy configuration should match");
        assertSame(
                configuration.getContextClassLoader(),
                copy2.getContextClassLoader(),
                "copy configuration should match");
        assertSame(configuration.getExceptionHandler(), copy2.getExceptionHandler(), "copy configuration should match");
        assertSame(configuration.getRunnable(), copy2.getRunnable(), "copy configuration should match");
    }
}
