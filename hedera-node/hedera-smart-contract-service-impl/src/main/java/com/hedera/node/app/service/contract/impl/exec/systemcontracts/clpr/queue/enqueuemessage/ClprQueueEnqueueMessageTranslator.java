// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessage;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueRevertCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code enqueueMessage(...)} calls to CLPR queue system contract.
 */
@Singleton
public class ClprQueueEnqueueMessageTranslator extends AbstractCallTranslator<ClprQueueCallAttempt> {
    private static final String ENQUEUE_MESSAGE_SIGNATURE =
            "enqueueMessage((address,(address,bytes32,(uint256,string),bytes),bytes32,(bool,(uint256,string),bytes),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes)))";

    public static final SystemContractMethod ENQUEUE_MESSAGE =
            SystemContractMethod.declare(ENQUEUE_MESSAGE_SIGNATURE, "(uint64)");

    @Inject
    public ClprQueueEnqueueMessageTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(ENQUEUE_MESSAGE);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final ClprQueueCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(ENQUEUE_MESSAGE);
    }

    @Override
    public Call callFrom(@NonNull final ClprQueueCallAttempt attempt) {
        requireNonNull(attempt);
        try {
            final var decodedCall = ENQUEUE_MESSAGE.decodeCall(attempt.inputBytes());
            final var message = (Tuple) decodedCall.get(0);
            return new ClprQueueEnqueueMessageCall(attempt, attempt.inputBytes(), message);
        } catch (final RuntimeException ignore) {
            return new ClprQueueRevertCall(
                    attempt.systemContractGasCalculator(), attempt.enhancement(), "CLPR_QUEUE_BAD_CALLDATA");
        }
    }
}
