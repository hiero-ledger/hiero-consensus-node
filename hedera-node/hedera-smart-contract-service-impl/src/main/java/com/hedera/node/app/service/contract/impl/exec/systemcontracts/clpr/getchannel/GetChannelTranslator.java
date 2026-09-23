// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getchannel;

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
 * Translates {@code getChannelQueueState(bytes32)} calls to the CLPR system contract.
 */
@Singleton
public class GetChannelTranslator extends AbstractCallTranslator<ClprCallAttempt> {

    static final int CHANNEL_ID_INDEX = 0;

    public static final SystemContractMethod GET_CHANNEL_QUEUE_STATE = SystemContractMethod.declare(
                    "getChannelQueueState(bytes32)", "(uint64,uint64)")
            .withCategories(Category.CLPR);

    @Inject
    public GetChannelTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(GET_CHANNEL_QUEUE_STATE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final ClprCallAttempt attempt) {
        return attempt.isMethod(GET_CHANNEL_QUEUE_STATE);
    }

    @Override
    public Call callFrom(@NonNull final ClprCallAttempt attempt) {
        final var call = GET_CHANNEL_QUEUE_STATE.decodeCall(attempt.inputBytes());
        final var channelId = (byte[]) call.get(CHANNEL_ID_INDEX);
        return new GetChannelCall(attempt.enhancement(), attempt.systemContractGasCalculator(), channelId);
    }
}
