// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see LeafBytesValidator
 */
public class TokenRelationsIntegrityValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(TokenRelationsIntegrityValidator.class);

    public static final String TOKEN_RELATIONS_GROUP = "tokenRelations";

    private VirtualMap virtualMap;
    private long numTokenRelations = 0L;

    private final AtomicLong objectsProcessed = new AtomicLong(0);
    private final AtomicLong accountFailCounter = new AtomicLong(0);
    private final AtomicLong tokenFailCounter = new AtomicLong(0);

    private final AtomicLong nullObjectsCounter = new AtomicLong(0);
    private final AtomicLong unequalObjectsCounter = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return TOKEN_RELATIONS_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getName() {
        // Intentionally same as group, as currently it is the only one
        return TOKEN_RELATIONS_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.virtualMap = state.getRoot();

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));

        this.numTokenRelations = entityCounters.numTokenRelations();
        log.debug("Number of token relations: {}", numTokenRelations);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        Objects.requireNonNull(virtualMap);

        final Bytes keyBytes = leafBytes.keyBytes();
        final Bytes valueBytes = leafBytes.valueBytes();
        final int readKeyStateId = StateKeyUtils.extractStateIdFromStateKeyOneOf(keyBytes);
        final int readValueStateId = StateValue.extractStateIdFromStateValueOneOf(valueBytes);
        if ((readKeyStateId == V0490TokenSchema.TOKEN_RELS_STATE_ID)
                && (readValueStateId == V0490TokenSchema.TOKEN_RELS_STATE_ID)) {
            try {
                final com.hedera.hapi.platform.state.StateKey stateKey =
                        com.hedera.hapi.platform.state.StateKey.PROTOBUF.parse(keyBytes);

                final EntityIDPair entityIDPair = stateKey.key().as();
                final AccountID accountId1 = entityIDPair.accountId();
                final TokenID tokenId1 = entityIDPair.tokenId();

                final com.hedera.hapi.platform.state.StateValue stateValue =
                        com.hedera.hapi.platform.state.StateValue.PROTOBUF.parse(valueBytes);
                final TokenRelation tokenRelation = stateValue.value().as();
                final AccountID accountId2 = tokenRelation.accountId();
                final TokenID tokenId2 = tokenRelation.tokenId();

                boolean nullObjectCheck = true;
                if (accountId1 == null) {
                    nullObjectCheck = false;
                    nullObjectsCounter.incrementAndGet();
                    log.error("Account ID is null for EntityIDPair {}", entityIDPair);
                }
                if (tokenId1 == null) {
                    nullObjectCheck = false;
                    nullObjectsCounter.incrementAndGet();
                    log.error("Token ID is null for EntityIDPair {}", entityIDPair);
                }
                if (accountId2 == null) {
                    nullObjectCheck = false;
                    nullObjectsCounter.incrementAndGet();
                    log.error("Account ID is null for TokenRelation {}", tokenRelation);
                }
                if (tokenId2 == null) {
                    nullObjectCheck = false;
                    nullObjectsCounter.incrementAndGet();
                    log.error("Token ID is null for TokenRelation {}", tokenRelation);
                }

                // Just continue processing when encountered some null object
                if (!nullObjectCheck) {
                    objectsProcessed.incrementAndGet();
                    return;
                }

                boolean equalityObjectCheck = true;
                if (!accountId1.equals(accountId2)) {
                    equalityObjectCheck = false;
                    unequalObjectsCounter.incrementAndGet();
                    log.error(
                            "Account IDs are not equal for EntityIDPair {} and TokenRelation {}",
                            entityIDPair,
                            tokenRelation);
                }
                if (!tokenId1.equals(tokenId2)) {
                    equalityObjectCheck = false;
                    unequalObjectsCounter.incrementAndGet();
                    log.error(
                            "Token IDs are not equal for EntityIDPair {} and TokenRelation {}",
                            entityIDPair,
                            tokenRelation);
                }

                // Just continue processing when encountered unequal objects
                if (!equalityObjectCheck) {
                    objectsProcessed.incrementAndGet();
                    return;
                }

                if (!virtualMap.containsKey(
                        getStateKeyForKv(V0490TokenSchema.ACCOUNTS_STATE_ID, accountId1, AccountID.PROTOBUF))) {
                    accountFailCounter.incrementAndGet();
                }

                if (!virtualMap.containsKey(
                        getStateKeyForKv(V0490TokenSchema.TOKENS_STATE_ID, tokenId1, TokenID.PROTOBUF))) {
                    tokenFailCounter.incrementAndGet();
                }

                objectsProcessed.incrementAndGet();
            } catch (final ParseException e) {
                throw new RuntimeException("Failed to parse a key", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.info("Checked {} token relation entries", objectsProcessed.get());

        final boolean ok = objectsProcessed.get() == numTokenRelations
                && accountFailCounter.get() == 0
                && tokenFailCounter.get() == 0
                && nullObjectsCounter.get() == 0
                && unequalObjectsCounter.get() == 0;

        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("""
                        %s validation failed.
                        objectsProcessed=%d vs expectedNumTokenRelations=%d
                        accountFailCount=%d tokenFailCount=%d
                        nullObjectsCount=%d unequalObjectsCount=%d""")
                        .formatted(
                                getName(),
                                objectsProcessed.get(),
                                numTokenRelations,
                                accountFailCounter.get(),
                                tokenFailCounter.get(),
                                nullObjectsCounter.get(),
                                unequalObjectsCounter.get()));
    }
}
