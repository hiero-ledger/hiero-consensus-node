// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

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
import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.LeafBytesValidator;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see LeafBytesValidator
 */
public class TokenRelationsIntegrityValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(TokenRelationsIntegrityValidator.class);

    public static final String TOKEN_RELATIONS_TAG = "tokenRelations";

    private VirtualMap virtualMap;
    private long numTokenRelations = 0L;

    private final AtomicInteger objectsProcessed = new AtomicInteger(0);
    private final AtomicInteger accountFailCounter = new AtomicInteger(0);
    private final AtomicInteger tokenFailCounter = new AtomicInteger(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getTag() {
        return TOKEN_RELATIONS_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final MerkleNodeState state) {
        this.virtualMap = (VirtualMap) state.getRoot();

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

                ValidationAssertions.requireNonNull(accountId1, getTag());
                ValidationAssertions.requireNonNull(tokenId1, getTag());
                ValidationAssertions.requireNonNull(accountId2, getTag());
                ValidationAssertions.requireNonNull(tokenId2, getTag());

                ValidationAssertions.requireEqual(accountId1, accountId2, getTag());
                ValidationAssertions.requireEqual(tokenId1, tokenId2, getTag());

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
        ValidationAssertions.requireEqual(objectsProcessed.get(), numTokenRelations, getTag());
        ValidationAssertions.requireEqual(0, accountFailCounter.get(), getTag());
        ValidationAssertions.requireEqual(0, tokenFailCounter.get(), getTag());
    }
}
