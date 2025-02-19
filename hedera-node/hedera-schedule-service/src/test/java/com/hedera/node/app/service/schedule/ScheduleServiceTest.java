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

package com.hedera.node.app.service.schedule;

import com.hedera.node.app.spi.fees.FeeCharging;
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
        public FeeCharging baseFeeCharging() {
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
