// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link ClprService}.
 */
public final class ClprServiceImpl implements ClprService {

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0770ClprSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        return V0770ClprSchema.initializeSingletons(writableStates, configuration);
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DataOneOfType.CLPR_UPDATE_LEDGER_CONFIGURATION),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_REGISTER_CHANNEL, TransactionBody.DataOneOfType.CLPR_REGISTER_CHANNEL),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_CLOSE_CHANNEL, TransactionBody.DataOneOfType.CLPR_CLOSE_CHANNEL),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_REGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_REGISTER_CONNECTOR),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_COMPLETE_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_COMPLETE_CONNECTOR),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_DEREGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_DEREGISTER_CONNECTOR),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_REDACT_MESSAGE, TransactionBody.DataOneOfType.CLPR_REDACT_MESSAGE),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_ENDPOINT_PUBLICATION,
                        TransactionBody.DataOneOfType.CLPR_ENDPOINT_PUBLICATION));
    }
}
