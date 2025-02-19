/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.exceptions;

import static com.swirlds.platform.exceptions.ExceptionAssertions.CAUSE;
import static com.swirlds.platform.exceptions.ExceptionAssertions.CAUSE_MESSAGE;
import static com.swirlds.platform.exceptions.ExceptionAssertions.MESSAGE;
import static com.swirlds.platform.exceptions.ExceptionAssertions.assertExceptionContains;
import static com.swirlds.platform.exceptions.ExceptionAssertions.assertExceptionSame;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.crypto.KeyCertPurpose;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeyLoadingException;
import com.swirlds.platform.gossip.shadowgraph.SyncTimeoutException;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.system.PlatformConstructionException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionTests {

    @Test
    void testKeyGeneratingException() {
        assertExceptionSame(new KeyGeneratingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
    }

    @Test
    void testKeyLoadingException() {
        assertExceptionSame(new KeyLoadingException(MESSAGE), MESSAGE, null);
        assertExceptionSame(new KeyLoadingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionSame(new KeyLoadingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionContains(
                new KeyLoadingException(MESSAGE, KeyCertPurpose.SIGNING, NodeId.FIRST_NODE_ID),
                List.of((NodeId.FIRST_NODE_ID.id() + 1) + "", MESSAGE),
                null);
    }

    @Test
    void testSyncTimeoutException() {
        assertExceptionContains(
                new SyncTimeoutException(Duration.ofSeconds(61), Duration.ofSeconds(60)),
                List.of("sync time exceeded", "60 sec", "61 sec"),
                null);
    }

    @Test
    void testPlatformConstructionException() {
        assertExceptionSame(new PlatformConstructionException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionContains(new PlatformConstructionException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }

    @Test
    void testNetworkProtocolException() {
        assertExceptionSame(new NetworkProtocolException(MESSAGE), MESSAGE, null);
        assertExceptionContains(new NetworkProtocolException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }
}
