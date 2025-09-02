// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.store.StoreFactory;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ScheduleServiceTest {
    private final ScheduleService subject = new ScheduleService() {
        @Override
        public ExecutableTxnIterator executableTxns(
                @NonNull Instant start, @NonNull Instant end, @NonNull StoreFactory storeFactory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerSchemas(@NonNull SchemaRegistry registry) {
            // No-op
        }

        @Override
        public ServiceApiProvider<ScheduleServiceApi> apiProvider() {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void verifyServiceName() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo("ScheduleService");
    }

    @Test
    void verifyRpcDefs() {
        Assertions.assertThat(subject.rpcDefinitions()).containsExactlyInAnyOrder(ScheduleServiceDefinition.INSTANCE);
    }
}
