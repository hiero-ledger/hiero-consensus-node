// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KEY_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder.FAILURE_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * A token update in which at least one key asks to inherit the caller's account key. The inherited account key is
 * only resolved when creating a token, so the update cannot be applied as requested.
 *
 * <p>The update is dispatched without the inherited keys, so a call that fails for another reason (for example
 * {@code INVALID_TOKEN_ID} or {@code INVALID_SIGNATURE}) reports the same status as before. A dispatch that would
 * succeed is reverted with {@code INVALID_KEY_ENCODING}, which rolls back all of its changes, instead of reporting
 * {@code SUCCESS} for an update that dropped a key. Such an update is never scheduled.
 */
public class InheritedKeyUpdateCall extends DispatchForResponseCodeHtsCall {
    /**
     * @param attempt the update attempt
     * @param syntheticBody the update decoded without the inherited keys
     */
    public InheritedKeyUpdateCall(@NonNull final HtsCallAttempt attempt, @NonNull final TransactionBody syntheticBody) {
        super(attempt, requireNonNull(syntheticBody), UpdateCommons::gasRequirement, FAILURE_CUSTOMIZER);
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        final var result = super.execute(frame);
        if (result.responseCode() != SUCCESS) {
            return result;
        }
        // Reverting the frame rolls back the dispatched update, so no field or key change survives
        final var fullResult = result.fullResult();
        final var recordBuilder = requireNonNull(fullResult.recordBuilder());
        recordBuilder.status(INVALID_KEY_ENCODING);
        return reversionWith(fullResult.gasRequirement(), recordBuilder);
    }

    /**
     * Returns the result of a {@code scheduleNative()} call for this update, which reverts with
     * {@code INVALID_KEY_ENCODING} and the canonical gas of a token update without scheduling anything.
     *
     * @return the reverted result
     */
    public @NonNull PricedResult rejectScheduling() {
        return reversionWith(INVALID_KEY_ENCODING, gasCalculator.canonicalGasRequirement(DispatchType.UPDATE));
    }

    @NonNull
    @Override
    public SchedulableTransactionBody asSchedulableDispatchIn() {
        throw new UnsupportedOperationException("An update that inherits the account key cannot be scheduled");
    }
}
