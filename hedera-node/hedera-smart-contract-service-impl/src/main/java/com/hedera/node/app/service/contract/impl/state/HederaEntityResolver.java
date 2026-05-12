// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;

public final class HederaEntityResolver {

    public sealed interface HederaEntity
            permits HederaEntity.AccountEntity, HederaEntity.TokenEntity, HederaEntity.ScheduleEntity {
        record AccountEntity(Account account) implements HederaEntity {}

        record TokenEntity(Token token) implements HederaEntity {}

        record ScheduleEntity(Schedule schedule) implements HederaEntity {}
    }

    private final HederaNativeOperations nativeOperations;
    private final EntityIdFactory entityIdFactory;

    public HederaEntityResolver(HederaNativeOperations nativeOperations) {
        this.nativeOperations = requireNonNull(nativeOperations);
        this.entityIdFactory = nativeOperations.entityIdFactory();
    }

    public @Nullable HederaEntity resolveEvmAddressToHederaEntity(@NonNull Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return null;
        }

        // #1: try resolve as account
        final var account = nativeOperations.getAccount(entityIdFactory.newAccountId(number));
        if (account != null) {
            if (account.deleted() || account.expiredAndPendingRemoval() || isNotPriority(address, account)) {
                return null;
            }
            return new HederaEntity.AccountEntity(account);
        }

        // #2: try resolve as token
        final var token = nativeOperations.getToken(entityIdFactory.newTokenId(number));
        if (token != null) {
            return new HederaEntity.TokenEntity(token);
        }

        // #3: try resolve as schedule
        final var schedule = nativeOperations.getSchedule(entityIdFactory.newScheduleId(number));
        if (schedule != null) {
            return new HederaEntity.ScheduleEntity(schedule);
        }

        return null;
    }

    private boolean isNotPriority(final Address address, final @NonNull Account account) {
        requireNonNull(account);
        final var maybeEvmAddress = extractEvmAddress(account.alias());
        return maybeEvmAddress != null && !address.equals(pbjToBesuAddress(maybeEvmAddress));
    }
}
