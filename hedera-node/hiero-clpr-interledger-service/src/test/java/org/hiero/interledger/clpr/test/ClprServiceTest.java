// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.test;

import com.swirlds.state.State;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.ClprServiceDefinition;
import org.junit.jupiter.api.Test;

class ClprServiceTest {
    private final ClprService subject = new ClprService() {

        private LedgerConfigurationDispatcher dispatcher;

        @Override
        public void setTransactionDispatcher(@NonNull ClprService.LedgerConfigurationDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public void dispatchLedgerConfigurationUpdate(@NonNull State state, @NonNull Instant consensusTime) {
            dispatcher.dispatch(state, consensusTime);
        }

        @Override
        public void registerSchemas(@NonNull SchemaRegistry registry) {}
    };

    @Test
    void verifyServiceName() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo("ClprService");
    }

    @Test
    void verifyRpcDefs() {
        Assertions.assertThat(subject.rpcDefinitions()).containsExactlyInAnyOrder(ClprServiceDefinition.INSTANCE);
    }
}
