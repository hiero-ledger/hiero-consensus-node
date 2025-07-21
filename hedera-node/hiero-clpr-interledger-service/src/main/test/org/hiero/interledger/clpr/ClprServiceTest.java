// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.store.StoreFactory;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ClprServiceTest {
    private final ClprService subject = new ClprService() {
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
