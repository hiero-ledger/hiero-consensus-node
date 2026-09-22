// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.state.State;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WriteStateToDiskListenerTest {
    @Mock
    private Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock
    private Executor executor;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private StateWriteToDiskCompleteNotification notification;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private BlockBufferService blockBufferService;

    private WriteStateToDiskListener subject;
    private AtomicInteger externalizeFreezeIfUpgradePendingCalls;

    @BeforeEach
    void setUp() {
        externalizeFreezeIfUpgradePendingCalls = new AtomicInteger();
        subject =
                new WriteStateToDiskListener(
                        stateAccessor, executor, configProvider, startupNetworks, entityIdFactory, blockBufferService) {
                    @Override
                    void externalizeFreezeIfUpgradePending() {
                        externalizeFreezeIfUpgradePendingCalls.incrementAndGet();
                    }
                };
    }

    @Test
    void archivesStartupNetworkFilesOnceFileWrittenIfRoundNotZero() {
        given(notification.getRoundNumber()).willReturn(0L, 1L);

        subject.notify(notification);
        subject.notify(notification);

        verify(startupNetworks, times(1)).archiveStartupNetworks();
        verify(blockBufferService, times(2)).persistBuffer();
    }

    @Test
    void respondsImmediatelyToFreezeStateNotification() {
        given(notification.isFreezeState()).willReturn(true);
        given(notification.getRoundNumber()).willReturn(1L);

        subject.notify(notification);

        assertFreezeExternalizedOnce();
        verify(startupNetworks).archiveStartupNetworks();
        verify(blockBufferService).persistBuffer();
    }

    @Test
    void freezeExternalizationDoesNotWaitForAsyncWork() {
        given(notification.isFreezeState()).willReturn(true);
        given(notification.getRoundNumber()).willReturn(1L);

        subject.notify(notification);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (externalizeFreezeIfUpgradePendingCalls.get() == 0) {
                Thread.sleep(1L);
            }
        });
        assertFreezeExternalizedOnce();
        verify(startupNetworks).archiveStartupNetworks();
        verify(blockBufferService).persistBuffer();
    }

    private void assertFreezeExternalizedOnce() {
        org.assertj.core.api.Assertions.assertThat(externalizeFreezeIfUpgradePendingCalls)
                .hasValue(1);
    }
}
