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
