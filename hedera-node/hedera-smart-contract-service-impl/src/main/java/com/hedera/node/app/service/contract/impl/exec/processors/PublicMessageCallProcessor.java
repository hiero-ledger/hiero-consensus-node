// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;

// spotless:off
public class PublicMessageCallProcessor extends MessageCallProcessor implements PublicMessageProcessor {
    public PublicMessageCallProcessor(HEVM evm, PrecompileContractRegistry precompiles) {
        super(evm, precompiles);
    }

    public void start      (MessageFrame f, OperationTracer t) { super.start      (f, t); }
    public void codeSuccess(MessageFrame f, OperationTracer t) { super.codeSuccess(f, t); }
    public void revert     (MessageFrame f                   ) { super.revert     (f   ); }
}
// spotless:on
