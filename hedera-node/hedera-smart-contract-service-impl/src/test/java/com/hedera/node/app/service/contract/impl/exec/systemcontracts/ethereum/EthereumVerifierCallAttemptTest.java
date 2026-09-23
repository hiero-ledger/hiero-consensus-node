// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.EthereumVerifierSystemContract.ETHEREUM_VERIFIER_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class EthereumVerifierCallAttemptTest extends CallAttemptTestBase {
    @Test
    void reportsEthereumVerifierKindAndSelf() {
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);

        final var subject = new EthereumVerifierCallAttempt(
                Bytes.wrap(new byte[] {1, 2, 3, 4}),
                new CallAttemptOptions<>(
                        ContractID.newBuilder()
                                .contractNum(numberOfLongZero(Address.fromHexString(ETHEREUM_VERIFIER_EVM_ADDRESS)))
                                .build(),
                        OWNER_BESU_ADDRESS,
                        Address.fromHexString(ETHEREUM_VERIFIER_EVM_ADDRESS),
                        OWNER_BESU_ADDRESS,
                        false,
                        mockEnhancement(),
                        DEFAULT_CONFIG,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        List.of(),
                        systemContractMethodRegistry,
                        true));

        assertThat(subject.systemContractKind()).isEqualTo(SystemContractMethod.SystemContract.ETHEREUM_VERIFIER);
        assertThat(subject.self()).isSameAs(subject);
        assertThat(subject.isStaticCall()).isTrue();
    }
}
