/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.state.listeners;

import static org.mockito.Mockito.verify;

import com.hedera.node.app.version.ServicesSoftwareVersion;
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

    private WriteStateToDiskListener subject;

    @BeforeEach
    void setUp() {
        subject = new WriteStateToDiskListener(
                stateAccessor, executor, configProvider, startupNetworks, ServicesSoftwareVersion::new);
    }

    @Test
    void archivesStartupNetworkFilesOnceFileWritten() {
        subject.notify(notification);

        verify(startupNetworks).archiveStartupNetworks();
    }
}
