// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.json.HasAnyExceptionFilter.hasAnyException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.logging.legacy.json.JsonLogEntry;
import com.swirlds.logging.legacy.json.JsonParser;
import com.swirlds.logging.legacy.payload.ReconnectLoadFailurePayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Log Simulator Test")
@Disabled
public class LoggingTests {

    /**
     * Sanity check on payload parsing.
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Payload Parsing Test")
    public void payloadParsingTest() throws IOException {

        final DummyLogBuilder logBuilder = new DummyLogBuilder();

        // A simple log message type
        final String simpleLogMsg = "This is a test of the emergency testing system";
        logBuilder.error(EXCEPTION.getMarker(), simpleLogMsg);

        // A non-trivial LogPayload type
        final String syncCompleteMessage = "synchronizationComplete";
        final double time = 10.0;
        final double hashTime = 1.0;
        final double initTime = 1.0;
        final int totalNodes = 200;
        final int leaves = 100;
        final int redundantLeaves = 10;
        final int internals = 100;
        final int redundantInternals = 10;

        logBuilder.error(
                EXCEPTION.getMarker(),
                new SynchronizationCompletePayload(syncCompleteMessage)
                        .setTimeInSeconds(time)
                        .setHashTimeInSeconds(hashTime)
                        .setInitializationTimeInSeconds(initTime)
                        .setTotalNodes(totalNodes)
                        .setLeafNodes(leaves)
                        .setRedundantLeafNodes(redundantLeaves)
                        .setInternalNodes(internals)
                        .setRedundantInternalNodes(redundantInternals)
                        .toString());

        // Read the logs back and verify the payloads
        SwirldsLogReader<JsonLogEntry> dummyLog = logBuilder.build();

        JsonLogEntry entry1 = dummyLog.nextEntry();
        assertNotNull(entry1);
        assertEquals("", entry1.getPayloadType());
        assertEquals(simpleLogMsg, entry1.getRawPayload());

        JsonLogEntry entry2 = dummyLog.nextEntry();
        assertNotNull(entry2);
        assertEquals(SynchronizationCompletePayload.class.getName(), entry2.getPayloadType());
        SynchronizationCompletePayload payload = entry2.getPayload(SynchronizationCompletePayload.class);
        assertEquals(syncCompleteMessage, payload.getMessage());
        assertEquals(time, payload.getTimeInSeconds());
        assertEquals(initTime, payload.getInitializationTimeInSeconds());
        assertEquals(totalNodes, payload.getTotalNodes());
        assertEquals(leaves, payload.getLeafNodes());
        assertEquals(redundantLeaves, payload.getRedundantLeafNodes());
        assertEquals(internals, payload.getInternalNodes());
        assertEquals(redundantInternals, payload.getRedundantInternalNodes());
    }

    /**
     * Make sure that two log entries are an exact match.
     */
    public void assertLogEntriesMatch(JsonLogEntry entry1, JsonLogEntry entry2) {
        if (entry1 == entry2) {
            return;
        }
        Supplier<String> msg = () -> entry1.toString() + " did not match " + entry2.toString();

        // Intentionally do not compare timestamps
        assertEquals(entry1.getThread(), entry2.getThread(), msg);
        assertEquals(entry1.getLevel(), entry2.getLevel(), msg);
        assertEquals(entry1.getLoggerName(), entry2.getLoggerName(), msg);
        assertEquals(entry1.getMarker(), entry2.getMarker(), msg);
        assertEquals(entry1.getRawPayload(), entry2.getRawPayload(), msg);
        assertEquals(entry1.getExceptionType(), entry2.getExceptionType(), msg);
        assertEquals(entry1.getExceptionMessage(), entry2.getExceptionMessage(), msg);
    }

    /**
     * Make sure that two logs are an exact match.
     */
    public void assertLogsMatch(SwirldsLogReader<JsonLogEntry> log1, SwirldsLogReader<JsonLogEntry> log2)
            throws IOException {

        while (true) {
            JsonLogEntry entry1 = log1.nextEntry();
            JsonLogEntry entry2 = log2.nextEntry();

            assertLogEntriesMatch(entry1, entry2);

            if (entry1 == null) {
                break;
            }
        }
    }

    /**
     * Sanity check for the log simulator. Make sure that a log written and then parsed is the same as a log
     * that is is directly simulated.
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Simulation Should Match File")
    public void simulationShouldMatchFile() throws URISyntaxException, IOException {
        File file = new File("./swirlds.json");

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.setConfigLocation(ResourceLoader.loadURL("log4j2-test.xml").toURI());
        Logger logger = LogManager.getLogger(LoggingTests.class);

        // When this test is run in circleci, other tests pollute the log file. Write a message that we can skip to
        final String beginningMessage = "BEGINNING_OF_LOG_SIMULATION_TEST";
        logger.info(EXCEPTION.getMarker(), beginningMessage);

        // Build the same log using dummy logs.
        DummyLogBuilder logBuilder = new DummyLogBuilder();

        logBuilder.setThread(Thread.currentThread().getName()).setLoggerName(logger.getName());

        logger.info(EXCEPTION.getMarker(), "info message");
        logBuilder.info(EXCEPTION.getMarker(), "info message");

        logger.warn(EXCEPTION.getMarker(), "warning message");
        logBuilder.warn(EXCEPTION.getMarker(), "warning message");

        logger.error(EXCEPTION.getMarker(), "error message");
        logBuilder.error(EXCEPTION.getMarker(), "error message");

        logger.error(
                EXCEPTION.getMarker(), () -> new SynchronizationCompletePayload("this message contains auxiliary data")
                        .setTimeInSeconds(10.0)
                        .setHashTimeInSeconds(1.0)
                        .setInitializationTimeInSeconds(1.0)
                        .setTotalNodes(200)
                        .setLeafNodes(100)
                        .setRedundantLeafNodes(10)
                        .setInternalNodes(100)
                        .setRedundantInternalNodes(10)
                        .toString());
        logBuilder.error(
                EXCEPTION.getMarker(),
                new SynchronizationCompletePayload("this message contains auxiliary data")
                        .setTimeInSeconds(10.0)
                        .setHashTimeInSeconds(1.0)
                        .setInitializationTimeInSeconds(1.0)
                        .setTotalNodes(200)
                        .setLeafNodes(100)
                        .setRedundantLeafNodes(10)
                        .setInternalNodes(100)
                        .setRedundantInternalNodes(10)
                        .toString());

        logger.error(EXCEPTION.getMarker(), "this log contains an exception", new RuntimeException("err1"));
        logBuilder.error(EXCEPTION.getMarker(), "this log contains an exception", new RuntimeException("err1"));

        logger.error(
                EXCEPTION.getMarker(),
                () -> new SynchronizationCompletePayload("this message contains auxiliary data and an exception")
                        .setTimeInSeconds(10.0)
                        .setHashTimeInSeconds(1.0)
                        .setInitializationTimeInSeconds(1.0)
                        .setTotalNodes(200)
                        .setLeafNodes(100)
                        .setRedundantLeafNodes(10)
                        .setInternalNodes(100)
                        .setRedundantInternalNodes(10)
                        .toString(),
                new RuntimeException("err2"));
        logBuilder.error(
                EXCEPTION.getMarker(),
                new SynchronizationCompletePayload("this message contains auxiliary data and an exception")
                        .setTimeInSeconds(10.0)
                        .setHashTimeInSeconds(1.0)
                        .setInitializationTimeInSeconds(1.0)
                        .setTotalNodes(200)
                        .setLeafNodes(100)
                        .setRedundantLeafNodes(10)
                        .setInternalNodes(100)
                        .setRedundantInternalNodes(10)
                        .toString(),
                new RuntimeException("err2"));

        SwirldsLogReader<JsonLogEntry> jsonLog = new SwirldsLogFileReader<>(file, new JsonParser());
        SwirldsLogReader<JsonLogEntry> dummyLog = logBuilder.build();

        // Skip all logs until we find the one at the beginning of the test
        while (true) {
            JsonLogEntry entry = jsonLog.nextEntry();
            assertNotNull(entry);
            if (entry.getRawPayload().equals(beginningMessage)) {
                break;
            }
        }

        assertLogsMatch(jsonLog, dummyLog);

        if (!file.delete()) {
            fail("unable to delete log file");
        }
    }

    /**
     * Sanity checks on the SwirldsLogReader.collect method.
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Collect Test")
    public void collectTest() throws IOException {

        DummyLogBuilder logBuilder = new DummyLogBuilder();

        int exceptions = 0;
        for (int i = 0; i < 1_000; i++) {
            if (i % 3 == 0) {
                exceptions++;
                logBuilder.error(EXCEPTION.getMarker(), "error " + i, new RuntimeException());
            } else {
                logBuilder.info(LogMarker.STARTUP.getMarker(), "startup" + i);
            }
        }

        SwirldsLogReader<JsonLogEntry> reader = logBuilder.build();

        List<JsonLogEntry> entriesWithExceptions = reader.collect(hasAnyException());
        List<JsonLogEntry> entriesWithoutExceptions =
                reader.collect(hasAnyException().negate());

        reader.readFully();

        assertEquals(exceptions, entriesWithExceptions.size());
        assertEquals(1_000 - exceptions, entriesWithoutExceptions.size());
        for (JsonLogEntry entry : entriesWithExceptions) {
            assertTrue(entry.hasException());
        }
        for (JsonLogEntry entry : entriesWithoutExceptions) {
            assertFalse(entry.hasException());
        }
    }

    /**
     * Sanity checks on the SwirldsLogReader.collect method with a limit in place.
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Limited Collect Test")
    public void limitedCollectTest() throws IOException {

        DummyLogBuilder logBuilder = new DummyLogBuilder();

        for (int i = 0; i < 1_000; i++) {
            if (i % 3 == 0) {
                logBuilder.error(EXCEPTION.getMarker(), "error " + i, new RuntimeException());
            } else {
                logBuilder.info(LogMarker.STARTUP.getMarker(), "startup" + i);
            }
        }

        SwirldsLogReader<JsonLogEntry> reader = logBuilder.build();

        List<JsonLogEntry> entriesWithExceptions = reader.collect(hasAnyException(), 100);
        List<JsonLogEntry> entriesWithoutExceptions =
                reader.collect(hasAnyException().negate(), 100);

        reader.readFully();

        assertEquals(100, entriesWithExceptions.size());
        assertEquals(100, entriesWithoutExceptions.size());
        for (JsonLogEntry entry : entriesWithExceptions) {
            assertTrue(entry.hasException());
        }
        for (JsonLogEntry entry : entriesWithoutExceptions) {
            assertFalse(entry.hasException());
        }
    }

    /**
     * Sanity checks on the SwirldsLogReader.count method.
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Count Test")
    public void countTest() throws IOException {

        DummyLogBuilder logBuilder = new DummyLogBuilder();

        int exceptions = 0;
        for (int i = 0; i < 1_000; i++) {
            if (i % 3 == 0) {
                exceptions++;
                logBuilder.error(EXCEPTION.getMarker(), "error " + i, new RuntimeException());
            } else {
                logBuilder.info(LogMarker.STARTUP.getMarker(), "startup" + i);
            }
        }

        SwirldsLogReader<JsonLogEntry> reader = logBuilder.build();

        AtomicInteger entriesWithExceptions = reader.count(hasAnyException());
        AtomicInteger entriesWithoutExceptions = reader.count(hasAnyException().negate());

        reader.readFully();

        assertEquals(exceptions, entriesWithExceptions.get());
        assertEquals(1_000 - exceptions, entriesWithoutExceptions.get());
    }

    /**
     * Sanity checks on the SwirldsLogReader.addAction method.
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Action Test")
    public void actionTest() throws IOException {

        DummyLogBuilder logBuilder = new DummyLogBuilder();

        int exceptions = 0;
        for (int i = 0; i < 1_000; i++) {
            if (i % 3 == 0) {
                exceptions++;
                logBuilder.error(EXCEPTION.getMarker(), "error " + i, new RuntimeException());
            } else {
                logBuilder.info(LogMarker.STARTUP.getMarker(), "startup" + i);
            }
        }

        SwirldsLogReader<JsonLogEntry> reader = logBuilder.build();

        AtomicInteger exceptionCount = new AtomicInteger(0);
        AtomicInteger noExceptionCount = new AtomicInteger(0);

        reader.addAction(hasAnyException(), (JsonLogEntry entry) -> exceptionCount.getAndIncrement());
        reader.addAction(hasAnyException().negate(), (JsonLogEntry entry) -> noExceptionCount.getAndIncrement());

        reader.readFully();

        assertEquals(exceptions, exceptionCount.get());
        assertEquals(1_000 - exceptions, noExceptionCount.get());
    }

    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Payload With No Javabean Properties")
    public void payloadWithNoJavabeanProperties() {
        final ReconnectLoadFailurePayload rlfp = new ReconnectLoadFailurePayload("Error in reconnect");
        System.out.println(rlfp.toString());
    }
}
