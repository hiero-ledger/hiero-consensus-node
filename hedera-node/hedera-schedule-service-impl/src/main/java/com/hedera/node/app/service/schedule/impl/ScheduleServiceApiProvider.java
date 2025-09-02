// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.ScheduleServiceApi;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleCreateHandler;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ScheduleServiceApiProvider implements ServiceApiProvider<ScheduleServiceApi> {
    private final ScheduleCreateHandler scheduleCreateHandler;

    @Inject
    public ScheduleServiceApiProvider(@NonNull final ScheduleCreateHandler scheduleCreateHandler) {
        this.scheduleCreateHandler = requireNonNull(scheduleCreateHandler);
    }

    @Override
    public String serviceName() {
        return ScheduleService.NAME;
    }

    @Override
    public ScheduleServiceApi newInstance(
            @NonNull final Configuration configuration,
            @NonNull final WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters) {
        requireNonNull(configuration);
        requireNonNull(writableStates);
        requireNonNull(entityCounters);
        return new ScheduleServiceApiImpl(
                new WritableScheduleStoreImpl(writableStates, entityCounters),
                configuration.getConfigData(LedgerConfig.class),
                configuration.getConfigData(SchedulingConfig.class));
    }

    /**
     * Default implementation of the {@link ScheduleServiceApi} interface.
     */
    public class ScheduleServiceApiImpl implements ScheduleServiceApi {
        private final WritableScheduleStore scheduleStore;
        private final LedgerConfig ledgerConfig;
        private final SchedulingConfig schedulingConfig;

        public ScheduleServiceApiImpl(
                @NonNull final WritableScheduleStore scheduleStore,
                @NonNull final LedgerConfig ledgerConfig,
                @NonNull final SchedulingConfig schedulingConfig) {
            this.scheduleStore = requireNonNull(scheduleStore);
            this.ledgerConfig = requireNonNull(ledgerConfig);
            this.schedulingConfig = requireNonNull(schedulingConfig);
        }

        @Override
        public boolean hasContractCallCapacity(
                final long expiry,
                @NonNull final Instant consensusNow,
                final long gasLimit,
                @NonNull final AccountID payerId) {
            requireNonNull(payerId);
            requireNonNull(consensusNow);
            final var expiryStatus =
                    scheduleCreateHandler.checkExpiry(consensusNow, expiry, ledgerConfig, schedulingConfig);
            if (expiryStatus != OK) {
                return false;
            }
            final var synthBody = TransactionBody.newBuilder()
                    .contractCall(ContractCallTransactionBody.newBuilder().gas(gasLimit))
                    .build();
            // Empty if no throttle could be checked the number of allowed scheduled transactions in state is reached
            final var maybeThrottle = scheduleCreateHandler.loadThrottle(scheduleStore, schedulingConfig, expiry);
            return maybeThrottle
                    .map(throttle -> throttle.allow(payerId, synthBody, CONTRACT_CALL, Instant.ofEpochSecond(expiry)))
                    .orElse(false);
        }
    }
}
