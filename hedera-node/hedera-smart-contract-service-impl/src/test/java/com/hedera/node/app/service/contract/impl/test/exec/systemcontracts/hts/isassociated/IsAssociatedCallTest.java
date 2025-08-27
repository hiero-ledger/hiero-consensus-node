// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.isassociated;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class IsAssociatedCallTest extends CallTestBase {

    IsAssociatedCall subject;

    @Test
    void returnsIsAssociated() {
        mockEntityIdFactory();

        subject = new IsAssociatedCall(gasCalculator, mockEnhancement(), SENDER_ID, FUNGIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IsAssociatedTranslator.IS_ASSOCIATED
                        .getOutputs()
                        .encode(Tuple.singleton(false))
                        .array()),
                result.getOutput());
    }
}
