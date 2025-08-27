// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StartupNetworks;
import java.util.concurrent.Executor;
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

    private WriteStateToDiskListener subject;

    @BeforeEach
    void setUp() {
        subject = new WriteStateToDiskListener(
                stateAccessor, executor, configProvider, startupNetworks, SemanticVersion.DEFAULT, entityIdFactory);
    }

    @Test
    void archivesStartupNetworkFilesOnceFileWrittenIfRoundNotZero() {
        given(notification.getRoundNumber()).willReturn(0L, 1L);

        subject.notify(notification);
        subject.notify(notification);

        verify(startupNetworks, times(1)).archiveStartupNetworks();
    }
}
