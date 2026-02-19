// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.app.service.contract.impl.exec.processors.CustomContractCreationProcessor;
import com.hedera.node.app.service.contract.impl.exec.processors.PublicContractCreationProcessor;
import com.hedera.node.app.service.contract.impl.exec.processors.PublicMessageCallProcessor;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;

public class HEVM extends EVM {
    public PublicMessageCallProcessor _call;
    public PublicContractCreationProcessor _create;

    public HEVM(
            OperationRegistry operations,
            GasCalculator gasCalculator,
            EvmConfiguration evmConfiguration,
            EvmSpecVersion evmSpecVersion) {
        super(operations, gasCalculator, evmConfiguration, evmSpecVersion);
    }

    public void setProcessors( PublicMessageCallProcessor call, PublicContractCreationProcessor create ) {
        _call = call;
        _create = create;
    }
}
