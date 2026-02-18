// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse;

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
 * Translates {@code enqueueMessageResponse(...)} calls to CLPR queue system contract.
 */
@Singleton
public class ClprQueueEnqueueMessageResponseTranslator extends AbstractCallTranslator<ClprQueueCallAttempt> {
    private static final String ENQUEUE_MESSAGE_RESPONSE_SIGNATURE =
            "enqueueMessageResponse((uint64,(bytes),(bytes),(uint8,(uint256,string),(uint256,string),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes))))";

    public static final SystemContractMethod ENQUEUE_MESSAGE_RESPONSE =
            SystemContractMethod.declare(ENQUEUE_MESSAGE_RESPONSE_SIGNATURE, "(uint64)");

    @Inject
    public ClprQueueEnqueueMessageResponseTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(ENQUEUE_MESSAGE_RESPONSE);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final ClprQueueCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(ENQUEUE_MESSAGE_RESPONSE);
    }

    @Override
    public Call callFrom(@NonNull final ClprQueueCallAttempt attempt) {
        requireNonNull(attempt);
        try {
            final var decodedCall = ENQUEUE_MESSAGE_RESPONSE.decodeCall(attempt.inputBytes());
            final var response = (Tuple) decodedCall.get(0);
            return new ClprQueueEnqueueMessageResponseCall(attempt, attempt.inputBytes(), response);
        } catch (final RuntimeException ignore) {
            return new ClprQueueRevertCall(
                    attempt.systemContractGasCalculator(), attempt.enhancement(), "CLPR_QUEUE_BAD_CALLDATA");
        }
    }
}
