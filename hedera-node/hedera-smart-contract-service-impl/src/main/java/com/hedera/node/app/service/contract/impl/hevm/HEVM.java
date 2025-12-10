// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.app.service.contract.impl.exec.processors.MessageProcessorImpl;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.processor.AbstractMessageProcessor;

public class HEVM extends EVM {
    public MessageProcessorImpl _call, _create;

    public HEVM(
            OperationRegistry operations,
            GasCalculator gasCalculator,
            EvmConfiguration evmConfiguration,
            EvmSpecVersion evmSpecVersion) {
        super(operations, gasCalculator, evmConfiguration, evmSpecVersion);
    }

    public void setProcessors( MessageProcessorImpl call, MessageProcessorImpl create ) {
        _call = call;
        _create = create;
    }
}
