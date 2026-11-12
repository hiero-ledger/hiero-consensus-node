// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasPlus;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.failure.HandleExceptionHaltReason;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallPricing;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * Base class for every CLPR-related native system contract.
 *
 * <p>The master {@code clpr.enabled} check lives here. While CLPR is disabled, the CLPR router and every
 * peer-ledger verifier report themselves as {@link #isDisabled(MessageFrame) disabled}, so the EVM treats
 * their addresses exactly as it did before these contracts existed. The check in
 * {@link #computeFully(ContractID, Bytes, MessageFrame, Consumer)} is a defensive fallback for direct invocations.
 */
public abstract class AbstractClprSystemContract extends AbstractNativeSystemContract {

    protected AbstractClprSystemContract(
            @NonNull final String name,
            @NonNull final CallFactory callFactory,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractMetrics contractMetrics) {
        super(name, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    public boolean isDisabled(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        return !FrameUtils.configOf(frame).getConfigData(ClprConfig.class).enabled();
    }

    @Override
    public void computeFully(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final MessageFrame frame,
            @NonNull final Consumer<FullResult> completion) {
        requireNonNull(input);
        requireNonNull(frame);
        if (isDisabled(frame)) {
            completion.accept(haltResult(new HandleExceptionHaltReason(CLPR_NOT_ENABLED), frame.getRemainingGas()));
            return;
        }
        super.computeFully(contractID, input, frame, completion);
    }

    /** Whether the selector belongs to a CLPR operation that accepts an attached fee. */
    public boolean acceptsValue(@NonNull final Bytes input) {
        return ClprCallPricing.acceptsValue(input, isRouter());
    }

    protected boolean isRouter() {
        return false;
    }

    @Override
    protected @NonNull Call.PricedResult executeCall(
            @NonNull final AbstractCallAttempt<?> attempt,
            @NonNull final Call call,
            @NonNull final Bytes input,
            @NonNull final MessageFrame frame) {
        final var result = call.execute(frame);
        // Local queries already pay through their query header.
        if (result.responseCode() != SUCCESS || !FrameUtils.isTopLevelTransaction(frame)) {
            return result;
        }
        final long target = ClprCallPricing.readPriceInTinycents(input, isRouter());
        final long gas = result.fullResult().gasRequirement();
        if (target == 0 || frame.getRemainingGas() < gas) {
            return result;
        }

        final var operations = attempt.enhancement().operations();
        // Native verifier dispatches already cover verification through the native transaction.
        if (operations.isClprVerifierDispatch() && ClprCallPricing.verifierPriceInTinycents(input) != 0) {
            return result;
        }
        final long topUp = ClprCallPricing.valueTopUp(frame, operations, target, gas);
        if (topUp == 0) {
            return result;
        }
        if (frame.isStatic()
                || FrameUtils.isDelegateCall(frame)
                || frame.getValue().lessThan(Wei.of(topUp))
                || !FrameUtils.proxyUpdaterFor(frame).collectClprFee(attempt.senderId(), topUp)) {
            return gasOnly(ordinalRevertResult(INSUFFICIENT_TX_FEE, gas), INSUFFICIENT_TX_FEE, result.isViewCall());
        }
        return gasPlus(result.fullResult(), SUCCESS, result.isViewCall(), topUp);
    }
}
