// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The base test class for all unit tests in Smart Contract Service.
 */
@ExtendWith(MockitoExtension.class)
public class CallTestBase {

    @Mock
    protected HederaOperations operations;

    @Mock
    protected HederaNativeOperations nativeOperations;

    @Mock
    protected SystemContractOperations systemContractOperations;

    @Mock
    protected SystemContractGasCalculator gasCalculator;

    @Mock
    protected MessageFrame frame;

    protected void mockEntityIdFactory() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
    }

    protected HederaWorldUpdater.Enhancement mockEnhancement() {
        return new HederaWorldUpdater.Enhancement(operations, nativeOperations, systemContractOperations);
    }
}
