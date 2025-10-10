// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.api;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.transfer.CustomFeeAssessmentStep;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides {@link TokenServiceApi} instances.
 */
public enum TokenServiceApiProvider implements ServiceApiProvider<TokenServiceApi> {
    /** The singleton instance. */
    TOKEN_SERVICE_API_PROVIDER;

    @Override
    public String serviceName() {
        return TokenService.NAME;
    }

    @Override
    public TokenServiceApi newInstance(
            @NonNull final Configuration configuration,
            @NonNull final WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters) {
        return new TokenServiceApiImpl(
                configuration,
                writableStates,
                op -> {
                    final var assessor = new CustomFeeAssessmentStep(op);
                    try {
                        final var result = assessor.assessFees(
                                new ReadableTokenStoreImpl(writableStates, entityCounters),
                                new ReadableTokenRelationStoreImpl(writableStates, entityCounters),
                                configuration,
                                new ReadableAccountStoreImpl(writableStates, entityCounters),
                                AccountID::hasAlias);
                        return !result.assessedCustomFees().isEmpty();
                    } catch (Exception ignore) {
                        return false;
                    }
                },
                entityCounters);
    }
}
