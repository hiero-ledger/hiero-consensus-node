// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code sendMessage(bytes32,bytes32,bytes,bytes)} calls to the CLPR system contract.
 *
 * <p>EVM signature:
 * <pre>{@code
 * function sendMessage(
 *     bytes32 channelId,
 *     bytes32 connectorId,
 *     bytes targetApplication,
 *     bytes messageData
 * ) returns (uint64 messageId)
 * }</pre>
 */
@Singleton
public class SendMessageTranslator extends AbstractCallTranslator<ClprCallAttempt> {
    /** ABI indices for decoded call arguments */
    static final int CHANNEL_ID_INDEX = 0;

    static final int CONNECTOR_ID_INDEX = 1;
    static final int TARGET_APPLICATION_INDEX = 2;
    static final int MESSAGE_DATA_INDEX = 3;

    public static final SystemContractMethod SEND_MESSAGE = SystemContractMethod.declare(
                    "sendMessage(bytes32,bytes32,bytes,bytes)", "(uint64)")
            .withCategories(Category.CLPR);

    @Inject
    public SendMessageTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(SEND_MESSAGE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final ClprCallAttempt attempt) {
        return attempt.isMethod(SEND_MESSAGE);
    }

    @Override
    public Call callFrom(@NonNull final ClprCallAttempt attempt) {
        final var call = SEND_MESSAGE.decodeCall(attempt.inputBytes());
        final var channelId = (byte[]) call.get(CHANNEL_ID_INDEX);
        final var connectorId = (byte[]) call.get(CONNECTOR_ID_INDEX);
        final var targetApplication = (byte[]) call.get(TARGET_APPLICATION_INDEX);
        final var messageData = (byte[]) call.get(MESSAGE_DATA_INDEX);
        return new SendMessageCall(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.senderId(),
                attempt.senderAddress(),
                channelId,
                connectorId,
                targetApplication,
                messageData);
    }
}
