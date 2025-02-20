// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.encodedRc;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class HbarApproveCall extends AbstractCall {

    private final VerificationStrategy verificationStrategy;
    private final TransactionBody transactionBody;
    private final AccountID sender;

    public HbarApproveCall(@NonNull final HasCallAttempt attempt, @NonNull final TransactionBody transactionBody) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.transactionBody = requireNonNull(transactionBody);
        this.verificationStrategy = attempt.defaultVerificationStrategy();
        this.sender = attempt.senderId();
    }

    @NonNull
    @Override
    public PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        final var recordBuilder = systemContractOperations()
                .dispatch(transactionBody, verificationStrategy, sender, ContractCallStreamBuilder.class);

        final var gasRequirement = gasCalculator.gasRequirement(transactionBody, DispatchType.APPROVE, sender);

        final var status = recordBuilder.status();
        if (status != ResponseCodeEnum.SUCCESS) {
            return reversionWith(gasRequirement, recordBuilder);
        } else {
            return completionWith(gasRequirement, recordBuilder, encodedRc(standardized(status)));
        }
    }
}
