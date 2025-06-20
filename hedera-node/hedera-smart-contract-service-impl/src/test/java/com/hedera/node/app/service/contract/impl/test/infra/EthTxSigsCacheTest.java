// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.infra.EthTxSigsCache;
import org.junit.jupiter.api.Test;

class EthTxSigsCacheTest {
    private final EthTxSigsCache subject = new EthTxSigsCache();

    @Test
    void reportsExpectedSignatures() {
        final var expectedSigs = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);

        assertEquals(expectedSigs, subject.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS));
    }
}
