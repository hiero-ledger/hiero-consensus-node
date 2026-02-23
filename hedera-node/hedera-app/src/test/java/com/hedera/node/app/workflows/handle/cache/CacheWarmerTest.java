// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CacheWarmerTest {

    @Mock
    TransactionChecker checker;

    @Mock
    TransactionDispatcher dispatcher;

    @Test
    @DisplayName("Instantiation test")
    void testInstantiation() {
        final var cacheWarmer = new CacheWarmer(checker, dispatcher, Runnable::run);
        assertThat(cacheWarmer).isInstanceOf(CacheWarmer.class);
    }
}
