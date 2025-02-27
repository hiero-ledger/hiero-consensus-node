// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.encodedRc;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class SetUnlimitedAutoAssociationsCall extends AbstractCall {

    private final AccountID sender;
    private final TransactionBody transactionBody;
    private final VerificationStrategy verificationStrategy;

    public SetUnlimitedAutoAssociationsCall(
            @NonNull final HasCallAttempt attempt, @NonNull final TransactionBody transactionBody) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.sender = attempt.senderId();
        this.transactionBody = requireNonNull(transactionBody);
        this.verificationStrategy = attempt.defaultVerificationStrategy();
    }

    @NonNull
    @Override
    public PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        final var recordBuilder = systemContractOperations()
                .dispatch(transactionBody, verificationStrategy, sender, ContractCallStreamBuilder.class);

        final var gasRequirement = gasCalculator.gasRequirement(transactionBody, DispatchType.CRYPTO_UPDATE, sender);
        return completionWith(gasRequirement, recordBuilder, encodedRc(standardized(recordBuilder.status())));
    }
}
