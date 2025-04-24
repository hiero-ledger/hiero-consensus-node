// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.RC_AND_ADDRESS_ENCODER;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallFactory;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Handle a call to schedule a native function call.
 */
public class ScheduleNativeCall extends AbstractCall {

    private final ContractID contractID;
    private final VerificationStrategy verificationStrategy;
    private final AccountID payerID;
    private final DispatchGasCalculator dispatchGasCalculator;
    private final Set<Key> authorizingKeys;
    private final Bytes innerCallData;
    private final boolean waitForExpiry;
    private final HtsCallFactory htsCallFactory;

    public ScheduleNativeCall(
            @NonNull final ContractID contractID,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID payerID,
            @NonNull final DispatchGasCalculator dispatchGasCalculator,
            @NonNull final Set<Key> authorizingKeys,
            @NonNull final Bytes innerCallData,
            @NonNull final HtsCallFactory htsCallFactory,
            final boolean waitForExpiry) {
        super(gasCalculator, enhancement, false);
        this.contractID = requireNonNull(contractID);
        this.verificationStrategy = requireNonNull(verificationStrategy);
        this.payerID = requireNonNull(payerID);
        this.dispatchGasCalculator = requireNonNull(dispatchGasCalculator);
        this.authorizingKeys = authorizingKeys;
        this.innerCallData = requireNonNull(innerCallData);
        this.htsCallFactory = requireNonNull(htsCallFactory);
        this.waitForExpiry = waitForExpiry;
    }

    @Override
    @NonNull
    public PricedResult execute(@NonNull final MessageFrame frame) {
        // Create the native call implied by the call data passed to scheduleNative()
        final var nativeAttempt =
                htsCallFactory.createCallAttemptFrom(contractID, innerCallData, DIRECT_OR_PROXY_REDIRECT, frame);
        final var call = requireNonNull(nativeAttempt.asExecutableCall());
        final var scheduleTransactionBody = call.asSchedulableDispatchIn();
        final var scheduleCreateTransactionBody = bodyForScheduleCreate(scheduleTransactionBody);
        final var gasRequirement = dispatchGasCalculator.gasRequirement(
                scheduleCreateTransactionBody, gasCalculator, enhancement, payerID);
        final var recordBuilder = systemContractOperations()
                .dispatch(
                        scheduleCreateTransactionBody,
                        verificationStrategy,
                        payerID,
                        ContractCallStreamBuilder.class,
                        authorizingKeys,
                        UsePresetTxnId.YES);

        final var status = recordBuilder.status();
        if (status != SUCCESS) {
            return gasOnly(revertResult(status, gasRequirement), status, false);
        } else {
            final var encodedRes = RC_AND_ADDRESS_ENCODER.encode(
                    Tuple.of((long) SUCCESS.protoOrdinal(), headlongAddressOf(recordBuilder.scheduleID())));
            return gasOnly(successResult(encodedRes, gasRequirement, recordBuilder), status, false);
        }
    }

    /**
     * Create a {@link TransactionBody} for a {@link ScheduleCreateTransactionBody} with the given
     * the result is used to dispatch a {@code SCHEDULE_CREATE} transaction to the schedule service.
     * @param scheduleTransactionBody
     * @return
     */
    private @NonNull TransactionBody bodyForScheduleCreate(final SchedulableTransactionBody scheduleTransactionBody) {
        return TransactionBody.newBuilder()
                .transactionID(nativeOperations().getTransactionID())
                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                        .scheduledTransactionBody(scheduleTransactionBody)
                        .payerAccountID(payerID)
                        .waitForExpiry(waitForExpiry))
                .build();
    }
}
