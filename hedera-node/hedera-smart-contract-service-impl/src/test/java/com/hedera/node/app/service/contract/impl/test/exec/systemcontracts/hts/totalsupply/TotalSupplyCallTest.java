// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.totalsupply;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static org.junit.jupiter.api.Assertions.*;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.totalsupply.TotalSupplyCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.totalsupply.TotalSupplyTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class TotalSupplyCallTest extends CallTestBase {

    private TotalSupplyCall subject;

    @Test
    void revertsWithMissingToken() {
        subject = new TotalSupplyCall(gasCalculator, mockEnhancement(), null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }

    @Test
    void returnsSupplyForPresentToken() {
        subject = new TotalSupplyCall(gasCalculator, mockEnhancement(), FUNGIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TotalSupplyTranslator.TOTAL_SUPPLY
                        .getOutputs()
                        .encode(Tuple.singleton(BigInteger.valueOf(FUNGIBLE_TOKEN.totalSupply())))
                        .array()),
                result.getOutput());
    }
}
