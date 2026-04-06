// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import java.util.List;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;

// spotless:off
public class PublicContractCreationProcessor extends ContractCreationProcessor implements PublicMessageProcessor {
    public PublicContractCreationProcessor( HEVM evm, boolean requireCodeDepositToSucceed, List<ContractValidationRule> contractValidationRules, long initialContractNonce) {
        super(evm, requireCodeDepositToSucceed, contractValidationRules, initialContractNonce);
    }

    public void start      (MessageFrame f, OperationTracer t) { super.start      (f, t); }
    public void codeSuccess(MessageFrame f, OperationTracer t) { super.codeSuccess(f, t); }
    public void revert     (MessageFrame f)                    { super.revert     (f   ); }
}
// spotless:on
