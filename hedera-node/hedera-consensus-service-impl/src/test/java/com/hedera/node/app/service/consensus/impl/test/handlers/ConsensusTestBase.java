// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.handlers;

import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_LABEL;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.ReadableTopicStoreImpl;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Base class for consensus service tests.
 */
@ExtendWith(MockitoExtension.class)
public class ConsensusTestBase {
    private static final String A_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C_NAME = "cccccccccccccccccccccccccccccccc";
    private static final String SCHEDULE_KEY = "scheduleKey";
    private static final Function<String, Key.Builder> KEY_BUILDER =
            value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));
    public static final Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    KEY_BUILDER.apply(C_NAME).build())
                            .build()))
            .build();

    public static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_THRESHOLD_KEY)))
            .build();
    public static final Key B_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_COMPLEX_KEY)))
            .build();
    public static final Key SHEDULE_KEY = Key.newBuilder()
            .keyList(KeyList.newBuilder().keys(KEY_BUILDER.apply(SCHEDULE_KEY).build()))
            .build();
    protected final Key key = A_COMPLEX_KEY;
    protected final Key anotherKey = B_COMPLEX_KEY;
    protected final Key feeScheduleKey = SHEDULE_KEY;
    protected final AccountID payerId = AccountID.newBuilder().accountNum(3).build();
    public static final AccountID anotherPayer =
            AccountID.newBuilder().accountNum(13257).build();
    protected final AccountID ownerId = AccountID.newBuilder().accountNum(555).build();
    protected final AccountID autoRenewId = AccountID.newBuilder().accountNum(1).build();
    protected final TokenID fungibleTokenId = TokenID.newBuilder().tokenNum(1).build();
    protected final byte[] runningHash = "runningHash".getBytes();

    protected final Key adminKey = key;
    protected final Key autoRenewKey = anotherKey;
    protected final long topicEntityNum = 1L;
    protected final TopicID topicId =
            TopicID.newBuilder().topicNum(topicEntityNum).build();
    protected final Duration WELL_KNOWN_AUTO_RENEW_PERIOD =
            Duration.newBuilder().seconds(100).build();
    protected final String memo = "test memo";
    protected final long expirationTime = 1_234_567L;
    protected final long sequenceNumber = 1L;
    protected final long autoRenewSecs = 100L;
    protected final Instant consensusTimestamp = Instant.ofEpochSecond(1_234_567L);
    protected final FixedCustomFee tokenCustomFee = FixedCustomFee.newBuilder()
            .fixedFee(FixedFee.newBuilder()
                    .denominatingTokenId(fungibleTokenId)
                    .amount(1)
                    .build())
            .feeCollectorAccountId(anotherPayer)
            .build();
    protected final FixedCustomFee hbarCustomFee = FixedCustomFee.newBuilder()
            .fixedFee(FixedFee.newBuilder().amount(1).build())
            .feeCollectorAccountId(anotherPayer)
            .build();
    protected final List<FixedCustomFee> customFees = List.of(tokenCustomFee, hbarCustomFee);

    protected Topic topic;

    protected Topic topicNoKeys;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected WritableStates writableStates;

    @Mock(strictness = LENIENT)
    protected HandleContext handleContext;

    @Mock(strictness = LENIENT)
    protected StoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    private ReadableAccountStore accountStore;

    @Mock(strictness = LENIENT)
    private ReadableTokenStore tokenStore;

    @Mock(strictness = LENIENT)
    private ReadableTokenRelationStore tokenRelStore;

    @Mock
    protected WritableEntityCounters entityCounters;

    @Mock
    protected ReadableEntityCounters readableEntityCounters;

    protected MapReadableKVState<TopicID, Topic> readableTopicState;
    protected MapWritableKVState<TopicID, Topic> writableTopicState;

    protected ReadableTopicStore readableStore;
    protected WritableTopicStore writableStore;

    @BeforeEach
    void commonSetUp() {
        givenValidTopic();
        refreshStoresWithCurrentTopicOnlyInReadable();
    }

    protected void refreshStoresWithCurrentTopicOnlyInReadable() {
        readableTopicState = readableTopicState();
        writableTopicState = emptyWritableTopicState();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        given(writableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(writableTopicState);
        readableStore = new ReadableTopicStoreImpl(readableStates, entityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableTopicStore(writableStates, entityCounters);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(writableStore);
    }

    protected void refreshStoresWithCurrentTopicInBothReadableAndWritable() {
        readableTopicState = readableTopicState();
        writableTopicState = writableTopicStateWithOneKey();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        given(writableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(writableTopicState);
        readableStore = new ReadableTopicStoreImpl(readableStates, entityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableTopicStore(writableStates, entityCounters);
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(writableStore);
    }

    @NonNull
    protected MapWritableKVState<TopicID, Topic> emptyWritableTopicState() {
        return MapWritableKVState.<TopicID, Topic>builder(TOPICS_STATE_ID, TOPICS_STATE_LABEL)
                .build();
    }

    @NonNull
    protected MapWritableKVState<TopicID, Topic> writableTopicStateWithOneKey() {
        return MapWritableKVState.<TopicID, Topic>builder(TOPICS_STATE_ID, TOPICS_STATE_LABEL)
                .value(topicId, topic)
                .build();
    }

    @NonNull
    protected MapReadableKVState<TopicID, Topic> readableTopicState() {
        return MapReadableKVState.<TopicID, Topic>builder(TOPICS_STATE_ID, TOPICS_STATE_LABEL)
                .value(topicId, topic)
                .build();
    }

    @NonNull
    protected MapReadableKVState<TopicID, Topic> emptyReadableTopicState() {
        return MapReadableKVState.<TopicID, Topic>builder(TOPICS_STATE_ID, TOPICS_STATE_LABEL)
                .build();
    }

    protected void setUpStores(final HandleContext context) {
        given(context.storeFactory()).willReturn(storeFactory);
        var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        when(handleContext.configuration()).thenReturn(config);
        // Set up account store
        var account = Account.newBuilder().accountId(ownerId).build();
        when(accountStore.getAccountById(ownerId)).thenReturn(account);
        when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        // Set up token store
        var token = Token.newBuilder().tokenId(fungibleTokenId).build();
        var tokenRel = TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(ownerId)
                .build();
        when(tokenStore.get(fungibleTokenId)).thenReturn(token);
        when(tokenRelStore.get(ownerId, fungibleTokenId)).thenReturn(tokenRel);
        when(storeFactory.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        when(storeFactory.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);
        // Set up topic store
        //        givenValidTopic();
        writableStore.put(topic);
        when(storeFactory.readableStore(ReadableTopicStore.class)).thenReturn(readableStore);
        when(storeFactory.writableStore(WritableTopicStore.class)).thenReturn(writableStore);
    }

    protected void givenValidTopic() {
        givenValidTopic(autoRenewId);
    }

    protected void givenValidTopic(AccountID autoRenewAccountId) {
        givenValidTopic(autoRenewAccountId, false);
    }

    protected void givenValidTopic(AccountID autoRenewAccountId, boolean deleted) {
        givenValidTopic(autoRenewAccountId, deleted, true, true);
    }

    protected void givenValidTopic(AccountID autoRenewAccountId, boolean deleted, boolean withAdminKey) {
        givenValidTopic(autoRenewAccountId, deleted, withAdminKey, true);
    }

    protected void givenValidTopic(
            AccountID autoRenewAccountId, boolean deleted, boolean withAdminKey, boolean withSubmitKey) {
        topic = Topic.newBuilder()
                .topicId(topicId)
                .sequenceNumber(sequenceNumber)
                .expirationSecond(expirationTime)
                .autoRenewPeriod(autoRenewSecs)
                .autoRenewAccountId(autoRenewAccountId)
                .deleted(deleted)
                .runningHash(Bytes.wrap(runningHash))
                .memo(memo)
                .adminKey(withAdminKey ? key : null)
                .submitKey(withSubmitKey ? key : null)
                .feeScheduleKey(feeScheduleKey)
                .feeExemptKeyList(List.of(key, anotherKey))
                .customFees(customFees)
                .build();
        topicNoKeys = Topic.newBuilder()
                .topicId(topicId)
                .sequenceNumber(sequenceNumber)
                .expirationSecond(expirationTime)
                .autoRenewPeriod(autoRenewSecs)
                .autoRenewAccountId(autoRenewAccountId)
                .deleted(deleted)
                .runningHash(Bytes.wrap(runningHash))
                .memo(memo)
                .build();
    }

    protected Topic createTopic() {
        return new Topic.Builder()
                .topicId(topicId)
                .adminKey(key)
                .submitKey(key)
                .autoRenewPeriod(autoRenewSecs)
                .autoRenewAccountId(autoRenewId)
                .expirationSecond(expirationTime)
                .sequenceNumber(sequenceNumber)
                .memo(memo)
                .deleted(true)
                .runningHash(Bytes.wrap(runningHash))
                .build();
    }
}
