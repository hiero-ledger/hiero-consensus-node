// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessage;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates node-internal packed calls that deliver inbound CLPR request payloads.
 */
@Singleton
public class ClprQueueDeliverInboundMessageTranslator extends AbstractCallTranslator<ClprQueueCallAttempt> {
    private static final String DELIVER_INBOUND_MESSAGE_PACKED_SIGNATURE = "deliverInboundMessagePacked(bytes)";

    public static final SystemContractMethod DELIVER_INBOUND_MESSAGE_PACKED =
            SystemContractMethod.declare(DELIVER_INBOUND_MESSAGE_PACKED_SIGNATURE, "(uint64)");

    @Inject
    public ClprQueueDeliverInboundMessageTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(DELIVER_INBOUND_MESSAGE_PACKED);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final ClprQueueCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(DELIVER_INBOUND_MESSAGE_PACKED);
    }

    @Override
    public Call callFrom(@NonNull final ClprQueueCallAttempt attempt) {
        requireNonNull(attempt);
        return new ClprQueueDeliverInboundMessageCall(attempt, attempt.inputBytes());
    }
}
