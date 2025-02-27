// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.decimals;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNREASONABLY_DIVISIBLE_TOKEN;
import static org.junit.jupiter.api.Assertions.*;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.HederaExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.decimals.DecimalsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.decimals.DecimalsTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class DecimalsCallTest extends CallTestBase {

    private DecimalsCall subject;

    @Test
    void haltWithNonfungibleToken() {
        subject = new DecimalsCall(mockEnhancement(), gasCalculator, NON_FUNGIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.EXCEPTIONAL_HALT, result.getState());
        assertEquals(
                HederaExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT,
                result.getHaltReason().get());
    }

    @Test
    void returnsInRangeDecimalsForPresentToken() {
        subject = new DecimalsCall(mockEnhancement(), gasCalculator, FUNGIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(DecimalsTranslator.DECIMALS
                        .getOutputs()
                        .encode(Tuple.singleton(FUNGIBLE_TOKEN.decimals()))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsMaxDecimalsForUnreasonableToken() {
        subject = new DecimalsCall(mockEnhancement(), gasCalculator, UNREASONABLY_DIVISIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(DecimalsTranslator.DECIMALS
                        .getOutputs()
                        .encode(Tuple.singleton(0xFF))
                        .array()),
                result.getOutput());
    }
}
