// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractRequired;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.CallOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;

/**
 * A Hedera customization of {@link CallOperation} that, if lazy creation is enabled and
 * applies to a call, does no additional address checks. Otherwise, only allows calls to an
 * address that is either a Hedera precompile, a system address, or not missing.
 *
 * <p><b>IMPORTANT:</b> This operation no longer enforces for receiver signature requirements
 * when value is being transferred; that will now happen in the call the {@link MessageCallProcessor}
 * makes to {@link HandleHederaNativeOperations#transferWithReceiverSigCheck(long, AccountID, AccountID, VerificationStrategy)}.
 */
public class CustomCallOperation extends CallOperation {
    private static final Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

    private final FeatureFlags featureFlags;
    private final AddressChecks addressChecks;

    /**
     * Constructor for custom call operations.
     * @param gasCalculator the gas calculator to use
     * @param addressChecks checks against addresses reserved for Hedera
     * @param featureFlags current evm module feature flags
     */
    public CustomCallOperation(
            @NonNull final FeatureFlags featureFlags,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks) {
        super(gasCalculator);
        this.featureFlags = Objects.requireNonNull(featureFlags);
        this.addressChecks = Objects.requireNonNull(addressChecks);
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        try {
            final long cost = cost(frame, false);
            if (frame.getRemainingGas() < cost) {
                return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
            }

            final var toAddress = to(frame);
            final var isMissing = mustBePresent(frame, toAddress) && !addressChecks.isPresent(toAddress, frame);
            if (isMissing) {
                FrameUtils.invalidAddressContext(frame)
                        .set(toAddress, InvalidAddressContext.InvalidAddressType.InvalidCallTarget);
                return new OperationResult(cost, INVALID_SOLIDITY_ADDRESS);
            }
            return super.execute(frame, evm);
        } catch (final UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }

    private boolean mustBePresent(@NonNull final MessageFrame frame, @NonNull final Address toAddress) {
        // This call will create the "to" address, so it doesn't need to be present
        if (impliesLazyCreation(frame, toAddress) && featureFlags.isImplicitCreationEnabled()) {
            return false;
        }
        // Let system accounts calls or if configured to allow calls to non-existing contract address calls
        // go through so the message call processor can fail in a more legible way
        return !addressChecks.isSystemAccount(toAddress) && contractRequired(frame, toAddress, featureFlags);
    }

    private boolean impliesLazyCreation(@NonNull final MessageFrame frame, @NonNull final Address toAddress) {
        return !isLongZero(toAddress)
                && value(frame).greaterThan(Wei.ZERO)
                && !addressChecks.isPresent(toAddress, frame);
    }
}
