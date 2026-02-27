// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import java.util.List;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;

public class PublicContractCreationProcessor extends ContractCreationProcessor implements PublicMessageProcessor {
    public PublicContractCreationProcessor( HEVM evm, boolean requireCodeDepositToSucceed, List<ContractValidationRule> contractValidationRules, long initialContractNonce) {
        super(evm,requireCodeDepositToSucceed, contractValidationRules, initialContractNonce);
    }

    public void start(MessageFrame frame, OperationTracer operationTracer) { super.start(frame,operationTracer); }
    public void codeSuccess(MessageFrame frame, OperationTracer operationTracer) { super.codeSuccess(frame,operationTracer); }
    public void revert(MessageFrame frame) { super.revert(frame); }
}
