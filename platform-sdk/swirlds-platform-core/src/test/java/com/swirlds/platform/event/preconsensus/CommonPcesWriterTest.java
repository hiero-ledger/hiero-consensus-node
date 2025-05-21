// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.IOException;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// TODO #18810 do not use mock for testing
class CommonPcesWriterTest {

    private PcesFileManager fileManager;
    private CommonPcesWriter commonPcesWriter;
    private PcesMutableFile pcesMutableFile;
    private PlatformEvent event;

    @BeforeEach
    void setUp() throws Exception {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final PlatformContext platformContext = mock(PlatformContext.class);
        when(platformContext.getConfiguration()).thenReturn(configuration);
        final Random random = getRandomPrintSeed();
        event = new TestingEventBuilder(random)
                .setAppTransactionCount(3)
                .setSystemTransactionCount(1)
                .setSelfParent(
                        new TestingEventBuilder(random).setBirthRound(150).build())
                .setOtherParent(
                        new TestingEventBuilder(random).setBirthRound(150).build())
                .overrideOtherParentGeneration(150)
                .setBirthRound(150)
                .build();
        fileManager = mock(PcesFileManager.class);
        final PcesFile pcesFile = mock(PcesFile.class);
        when(fileManager.getNextFileDescriptor(anyLong(), anyLong())).thenReturn(pcesFile);
        pcesMutableFile = mock(PcesMutableFile.class);
        when(pcesFile.getMutableFile(any())).thenReturn(pcesMutableFile);

        // Initialize CommonPcesWriter with mocks
        commonPcesWriter = new CommonPcesWriter(platformContext, fileManager);
    }

    @Test
    void testBeginStreamingNewEvents() {
        commonPcesWriter.beginStreamingNewEvents();
        assertTrue(commonPcesWriter.isStreamingNewEvents(), "New event streaming should start.");
    }

    @Test
    void testBeginStreamingNewEventsAlreadyStreaming() {
        commonPcesWriter.beginStreamingNewEvents();
        // Expect a log error but no exception thrown
        commonPcesWriter.beginStreamingNewEvents();
    }

    @Test
    void testRegisterDiscontinuity() throws IOException {
        commonPcesWriter.beginStreamingNewEvents();
        commonPcesWriter.prepareOutputStream(event);
        commonPcesWriter.registerDiscontinuity(10L);

        // Verify file closing and file manager interactions
        verify(fileManager, times(1)).registerDiscontinuity(10L);
        verify(pcesMutableFile, times(1)).close();
        verify(fileManager, times(1)).registerDiscontinuity(10L);
    }

    @Test
    void testUpdateNonAncientEventBoundary() {
        EventWindow mockWindow = mock(EventWindow.class);
        when(mockWindow.ancientThreshold()).thenReturn(100L);

        commonPcesWriter.updateNonAncientEventBoundary(mockWindow);

        assertEquals(100L, commonPcesWriter.getNonAncientBoundary(), "Non-ancient boundary should be updated.");
    }

    @Test
    void testSetMinimumAncientIdentifierToStore() throws IOException {
        commonPcesWriter.beginStreamingNewEvents();
        commonPcesWriter.setMinimumAncientIdentifierToStore(50L);

        verify(fileManager, times(1)).pruneOldFiles(50L);
    }

    @Test
    void testPrepareOutputStreamCreatesNewFile() throws IOException {
        boolean fileClosed = commonPcesWriter.prepareOutputStream(event);
        assertFalse(fileClosed, "A new file should have been created but not closed.");
    }

    @Test
    void testCloseCurrentMutableFile() throws IOException {
        commonPcesWriter.beginStreamingNewEvents();
        commonPcesWriter.prepareOutputStream(event);
        commonPcesWriter.closeCurrentMutableFile();
        verify(pcesMutableFile, times(1)).close();
    }
}
