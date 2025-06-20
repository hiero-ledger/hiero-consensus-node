// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_ID_KEY;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_COUNTS_KEY;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_ORDERS_KEY;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_USAGES_KEY;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULE_ID_BY_EQUALITY_KEY;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.Builder;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleIdList;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.UtilPrngTransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableKVStateBase;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.InvalidKeyException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

// TODO: Rename to ScheduleTestBase, harmonize with ScheduleHandlerTestBase
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.WARN)
public class ScheduleTestBase {
    // These two *should* be constants in token service, but are not, so we have constants here.
    private static final String ACCOUNT_STATE_KEY = "ACCOUNTS";
    private static final String ACCOUNT_ALIAS_STATE_KEY = "ALIASES";
    protected static final int SHARD = 5;
    protected static final long REALM = 10L;
    // spotless mangles this section randomly, due to incorrect wrapping rules
    protected static final ScheduleID.Builder ALL_SCHEDULES_ID =
            ScheduleID.newBuilder().shardNum(12).realmNum(6);
    // A few random values for fake ed25519 test keys
    protected static final Bytes PAYER_KEY_HEX =
            Bytes.fromHex("badcadfaddad2bedfedbeef959feedbeadcafecadecedebeed4acedecada5ada");
    protected static final Bytes SCHEDULER_KEY_HEX =
            Bytes.fromHex("feedbeadcafe8675309bafedfacecaeddeedcedebede4adaacecab2badcadfad");
    // This one is a perfect 10.
    protected static final Bytes ADMIN_KEY_HEX =
            Bytes.fromHex("0000000000191561942608236107294793378084303638130997321548169216");
    protected static final Bytes OPTION_KEY_HEX =
            Bytes.fromHex("9834701927540926570495640961948794713207439248567184729049081327");
    protected static final Bytes OTHER_KEY_HEX =
            Bytes.fromHex("983470192754092657adbdbeef61948794713207439248567184729049081327");

    protected static final String SCHEDULED_TRANSACTION_MEMO = "Les ħ2ᛏᚺᛂ🌕 goo";
    protected static final String ODD_MEMO = "she had marvelous judgement, Don... if not particularly good taste.";
    // a few typed null values to avoid casting null
    protected static final AccountID nullAccount = null;
    protected static final Key nullKey = null;
    protected static final Timestamp nullTime = null;
    protected static final TransactionID nullTransactionId = null;
    protected static final ScheduleID nullScheduleId = null;
    // spotless mangles this section randomly, due to unstable wrapping rules
    // spotless:off
    protected final Key adminKey = Key.newBuilder().ed25519(ADMIN_KEY_HEX).build();
    protected final Key schedulerKey = Key.newBuilder().ed25519(SCHEDULER_KEY_HEX).build();
    protected final Key payerKey = Key.newBuilder().ed25519(PAYER_KEY_HEX).build();
    protected final Key optionKey = Key.newBuilder().ed25519(OPTION_KEY_HEX).build();
    protected final Key otherKey = Key.newBuilder().ed25519(OTHER_KEY_HEX).build();
    protected final ScheduleID testScheduleID = ScheduleID.newBuilder().scheduleNum(1100L).build();
    protected final ScheduleID alternateScheduleID = ScheduleID.newBuilder().scheduleNum(1200L).build();
    protected final ScheduleID badId = ScheduleID.newBuilder().realmNum(-2).shardNum(-1).scheduleNum(-1).build();
    protected final AccountID admin = AccountID.newBuilder().accountNum(626068L).build();
    protected final AccountID scheduler = AccountID.newBuilder().accountNum(1001L).build();
    protected final AccountID payer = AccountID.newBuilder().accountNum(2001L).build();
    protected final Account schedulerAccount = Account.newBuilder().accountId(scheduler).key(schedulerKey).build();
    protected final Account payerAccount = Account.newBuilder().accountId(payer).key(payerKey).build();
    protected final Account adminAccount = Account.newBuilder().accountId(admin).key(adminKey).build();
    protected final Instant testConsensusTime = Instant.ofEpochSecond(1656087862L, 1221973L);
    protected final List<Key> alternateSignatories = List.of(payerKey, adminKey, schedulerKey);
    protected final Timestamp testValidStart = Timestamp.newBuilder().seconds(2281580449L).nanos(0).build();
    protected final String memo = "Test";
    // Note, many tests assume that these are the same as validStart, which is unfortunate.
    protected final Timestamp expirationTime = Timestamp.newBuilder().seconds(2281580449L).nanos(0).build();
    protected final Timestamp calculatedExpirationTime = Timestamp.newBuilder().seconds(2281580449L).nanos(0).build();
    protected final Timestamp modifiedResolutionTime = new Timestamp(18601220L, 18030109);
    protected final Timestamp modifiedStartTime = new Timestamp(18601220L, 18030109);
    protected final EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);
    // spotless:on

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected ReadableStoreFactory mockStoreFactory;

    @Mock
    protected ReadableEntityIdStoreImpl readableEntityCounters;

    @Mock
    protected WritableEntityIdStore writableEntityCounters;

    // This schedule is not in whitelist, but exercises most code paths
    // 6d850a46e97dd8a4eafd3d7c114d0d151349e4f8531301b22c5fe508f712b6e4
    protected Schedule scheduleInState;
    // This schedule *is* in whitelist, and helps with "success" code paths
    // e18e28c20f1e76cf55164b04cd89468070f55007b419698a078d674767bfa2a0
    protected Schedule otherScheduleInState;

    // Non-Mock objects, but may contain or reference mock objects.
    // It takes a lot of objects to create fake states using MapZzzzState
    // These are protected to allow for redefinition and other adjustments for specific tests
    protected ReadableAccountStore accountStore;
    protected ReadableScheduleStore scheduleStore;
    protected WritableScheduleStore writableSchedules;
    protected WritableKVState<AccountID, Account> accountById;
    protected WritableKVState<ProtoBytes, AccountID> accountAliases;
    protected Map<AccountID, Account> accountsMapById;
    protected Map<ScheduleID, Schedule> scheduleMapById;
    protected Map<ProtoBytes, ScheduleID> scheduleMapByEquality;
    protected Map<ProtoLong, ScheduleIdList> scheduleMapByExpiration;
    protected Map<TimestampSeconds, ScheduledCounts> scheduledCounts;
    protected Map<ScheduledOrder, ScheduleID> scheduledOrders;
    protected Map<TimestampSeconds, ThrottleUsageSnapshots> scheduledUsages;
    protected WritableKVState<ScheduleID, Schedule> writableById;
    protected WritableKVState<ProtoBytes, ScheduleID> writableByEquality;
    protected WritableKVState<TimestampSeconds, ScheduledCounts> writableScheduledCounts;
    protected WritableKVState<TimestampSeconds, ThrottleUsageSnapshots> writableScheduledUsages;
    protected WritableKVState<ScheduledOrder, ScheduleID> writableScheduledOrders;
    protected Map<String, WritableKVState<?, ?>> writableStatesMap;
    protected ReadableStates states;
    protected WritableStates scheduleStates;

    protected Configuration testConfig;
    protected SchedulingConfig scheduleConfig;
    protected SchedulableTransactionBody scheduled;
    protected TransactionBody originalCreateTransaction;
    protected TransactionBody alternateCreateTransaction;
    protected List<Schedule> listOfScheduledOptions;

    protected void setUpBase() throws PreCheckException, InvalidKeyException {
        testConfig = HederaTestConfigBuilder.createConfig();
        scheduleConfig = testConfig.getConfigData(SchedulingConfig.class);
        scheduled = createSampleScheduled();
        originalCreateTransaction = originalCreateTransaction(scheduled, scheduler, adminKey);
        listOfScheduledOptions =
                createAllScheduled(originalCreateTransaction, payer, testConsensusTime, scheduleConfig);
        alternateCreateTransaction = alternateCreateTransaction(originalCreateTransaction);

        final Schedule.Builder builder = Schedule.newBuilder();
        builder.scheduleId(testScheduleID);
        builder.payerAccountId(payer).schedulerAccountId(scheduler).adminKey(adminKey);
        builder.scheduledTransaction(scheduled);
        builder.originalCreateTransaction(originalCreateTransaction);
        builder.providedExpirationSecond(18651206L);
        builder.calculatedExpirationSecond(calculatedExpirationTime.seconds());
        builder.scheduleValidStart(testValidStart).waitForExpiry(true);
        builder.deleted(false).executed(false).memo(memo);
        // Note: Do not spy this.
        scheduleInState = builder.build();
        otherScheduleInState = createAlternate(
                alternateCreateTransaction, testConsensusTime, scheduleConfig.maxExpirationFutureSeconds());

        setUpStates();
        given(mockStoreFactory.getStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        given(mockStoreFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(mockStoreFactory.getStore(ReadableEntityIdStore.class)).willReturn(readableEntityCounters);
        given(mockStoreFactory.getStore(WritableEntityIdStore.class)).willReturn(writableEntityCounters);
    }

    protected void commitScheduleStores() {
        commit(writableByEquality);
        commit(writableScheduledOrders);
        commit(writableScheduledCounts);
        commit(writableScheduledUsages);
        commit(writableById);
    }

    // ConsensusSubmitMessage,CryptoTransfer,TokenMint,TokenBurn,CryptoApproveAllowance
    protected SchedulableTransactionBody createAlternateScheduled() {
        return SchedulableTransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder())
                .build();
    }

    /**
     * Reset the aggressive cache in a Writable (or Readable) KV State.
     * <p>
     * This is necessary because we need the test states to read from the underlying map, and never
     * cache the value.  We don't care about tracking reads, we need the test to return the correct
     * value.  Ideally MapReadableKVState would remove the cache, but unfortunately it does not do so.
     * <p>
     * We could, in theory, put values via the WritableKVState interface, but that blocks putting a lot
     * of possible values we need to test, so we cannot do that either.  Remove on that interface is
     * even worse as it caches the removal and ignores anything, except itself, that adds a replacement.
     *
     * @param readableState a KVState to reset.
     */
    protected void reset(final ReadableKVState<?, ?> readableState) {
        if (readableState instanceof ReadableKVStateBase<?, ?> base) {
            base.reset(); // This should be on the interface, dagnabit, not just the implementation!
        }
    }

    /**
     * Commit changes from the aggressive cache in a Writable (or Readable) KV State to the underlying store.
     * <p>
     * This is necessary because we need the test states to actually write the underlying map.
     * If the underlying map isn't written, we have no way to inspect whether the state manipulations
     * are actually correct.
     * <p>
     * We could, in theory, query the KVStates or ReadableStore, but that is a very limited interface, and
     * often does not return expected values (due to caching and off-kilter interactions with mocks).
     *
     * @param writableState a KVState to commit.
     */
    protected void commit(final WritableKVState<?, ?> writableState) {
        if (writableState instanceof WritableKVStateBase<?, ?> base) {
            base.commit();
        }
    }

    /**
     * Create a large array of potential scheduled transactions with every possible "child" transaction type.
     * <p>
     * This method has some initial complexity because each {@link Schedule} produced must contain an original
     * ScheduleCreate transaction that matches, in every respect, the schedule and the child transaction.
     * Partly this is to support testing schedule creation, but also it supports the need to match the existing
     * mono service which stores the original create transaction and fills in many schedule values from that
     * transaction on every deserialization from state.
     * <p>
     * The remainder of the method is creating empty child transactions, adding each to the base schedule builder,
     * building the resultant schedule (all identical except for child transaction and create transaction), and adding
     * that schedule to the output list.
     *
     * @param createTransaction the base original create transaction to use.  Several values are read from this
     *     entry, including transaction ID values, and the transaction body is added to the Schedule as well.
     * @param childPayer The payer to use for the child transaction
     * @param consensusTime The consensus time used to create the various schedules
     * @param scheduleConfig The current {@link SchedulingConfig}, typically this is default values returned from the
     *     test configuration builder.
     * @return a {@link List<Schedule>} filled with one Schedule for each possible child transaction type.
     */
    private List<Schedule> createAllScheduled(
            final TransactionBody createTransaction,
            final AccountID childPayer,
            final Instant consensusTime,
            final SchedulingConfig scheduleConfig) {
        int num = 18649;
        final List<Schedule> listOfOptions = new LinkedList<>();
        final TransactionBody.Builder originBuilder = createTransaction.copyBuilder();
        ScheduleCreateTransactionBody.Builder modifiedCreate =
                createTransaction.scheduleCreateOrThrow().copyBuilder();
        final ScheduleID.Builder idBuilder = ALL_SCHEDULES_ID;
        final Pair<Schedule.Builder, Builder> builders =
                createScheduleBuilders(createTransaction, childPayer, consensusTime, scheduleConfig);
        final Schedule.Builder builder = builders.left();
        final Builder childBuilder = builders.right();

        childBuilder.consensusCreateTopic(ConsensusCreateTopicTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.consensusUpdateTopic(ConsensusUpdateTopicTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.consensusDeleteTopic(ConsensusDeleteTopicTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.cryptoTransfer(CryptoTransferTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.cryptoDelete(CryptoDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.fileCreate(FileCreateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.fileAppend(FileAppendTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.fileUpdate(FileUpdateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.fileDelete(FileDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.contractCreateInstance(ContractCreateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.contractUpdateInstance(ContractUpdateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.contractCall(ContractCallTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.contractDeleteInstance(ContractDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.systemDelete(SystemDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.systemUndelete(SystemUndeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.freeze(FreezeTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenCreation(TokenCreateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenUnfreeze(TokenUnfreezeAccountTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenGrantKyc(TokenGrantKycTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenRevokeKyc(TokenRevokeKycTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenDeletion(TokenDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenUpdate(TokenUpdateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenMint(TokenMintTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenBurn(TokenBurnTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenWipe(TokenWipeAccountTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenAssociate(TokenAssociateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenDissociate(TokenDissociateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.scheduleDelete(ScheduleDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenPause(TokenPauseTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenUnpause(TokenUnpauseTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.cryptoApproveAllowance(CryptoApproveAllowanceTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.cryptoDeleteAllowance(CryptoDeleteAllowanceTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.tokenFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.utilPrng(UtilPrngTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.nodeCreate(NodeCreateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.nodeUpdate(NodeUpdateTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        childBuilder.nodeDelete(NodeDeleteTransactionBody.newBuilder());
        addNextItem(listOfOptions, builder, originBuilder, modifiedCreate, childBuilder, idBuilder, ++num);
        return listOfOptions;
    }

    private static Pair<Schedule.Builder, Builder> createScheduleBuilders(
            final TransactionBody createTransaction,
            final AccountID childPayer,
            final Instant consensusTime,
            final SchedulingConfig scheduleConfig) {
        final long expirationSecond = consensusTime.getEpochSecond() + scheduleConfig.maxExpirationFutureSeconds();
        final Schedule.Builder builder = Schedule.newBuilder();
        builder.originalCreateTransaction(createTransaction);
        builder.memo(createTransaction.memo());
        builder.calculatedExpirationSecond(expirationSecond);
        builder.scheduleValidStart(createTransaction.transactionID().transactionValidStart());
        final ScheduleCreateTransactionBody originalCreate = createTransaction.scheduleCreateOrThrow();
        builder.adminKey(originalCreate.adminKey());
        builder.payerAccountId(originalCreate.payerAccountIDOrElse(childPayer));
        builder.schedulerAccountId(originalCreate.payerAccountID());
        final Builder childBuilder = SchedulableTransactionBody.newBuilder();
        childBuilder.memo("%s. Scheduled with %s.".formatted(SCHEDULED_TRANSACTION_MEMO, createTransaction.memo()));
        childBuilder.transactionFee(createTransaction.transactionFee());
        return new Pair<>(builder, childBuilder);
    }

    private static void addNextItem(
            final List<Schedule> listOfOptions,
            final Schedule.Builder builder,
            final TransactionBody.Builder originBuilder,
            final ScheduleCreateTransactionBody.Builder modifiedCreate,
            final Builder childBuilder,
            final ScheduleID.Builder idBuilder,
            final int scheduleNumber) {
        idBuilder.scheduleNum(scheduleNumber);
        builder.scheduleId(idBuilder);
        modifiedCreate.scheduledTransactionBody(childBuilder);
        builder.originalCreateTransaction(originBuilder.scheduleCreate(modifiedCreate));
        listOfOptions.add(builder.scheduledTransaction(childBuilder).build());
    }

    private Schedule createAlternate(
            final TransactionBody currentTransaction, final Instant currentConsensusTime, final long maxLifeSeconds) {
        final var builder = createProvisionalBuilder(currentTransaction, currentConsensusTime, maxLifeSeconds);
        builder.scheduleId(alternateScheduleID);
        return builder.build();
    }

    private void setUpStates() {
        scheduleMapById = new HashMap<>(0);
        scheduleMapByEquality = new HashMap<>(0);
        scheduleMapByExpiration = new HashMap<>(0);
        scheduledCounts = new HashMap<>(0);
        scheduledOrders = new HashMap<>(0);
        scheduledUsages = new HashMap<>(0);
        accountsMapById = new HashMap<>(0);
        writableById = new MapWritableKVState<>(SCHEDULES_BY_ID_KEY, scheduleMapById);
        writableByEquality = new MapWritableKVState<>(SCHEDULE_ID_BY_EQUALITY_KEY, scheduleMapByEquality);
        writableScheduledCounts = new MapWritableKVState<>(SCHEDULED_COUNTS_KEY, scheduledCounts);
        writableScheduledOrders = new MapWritableKVState<>(SCHEDULED_ORDERS_KEY, scheduledOrders);
        writableScheduledUsages = new MapWritableKVState<>(SCHEDULED_USAGES_KEY, scheduledUsages);
        accountById = new MapWritableKVState<>(ACCOUNT_STATE_KEY, accountsMapById);
        accountAliases = new MapWritableKVState<>(ACCOUNT_ALIAS_STATE_KEY, new HashMap<>(0));
        writableStatesMap = new TreeMap<>();
        writableStatesMap.put(SCHEDULES_BY_ID_KEY, writableById);
        writableStatesMap.put(SCHEDULE_ID_BY_EQUALITY_KEY, writableByEquality);
        writableStatesMap.put(SCHEDULED_COUNTS_KEY, writableScheduledCounts);
        writableStatesMap.put(SCHEDULED_ORDERS_KEY, writableScheduledOrders);
        writableStatesMap.put(SCHEDULED_USAGES_KEY, writableScheduledUsages);
        writableStatesMap.put(ACCOUNT_STATE_KEY, accountById);
        writableStatesMap.put(ACCOUNT_ALIAS_STATE_KEY, accountAliases);
        scheduleStates = new MapWritableStates(writableStatesMap);
        states = new MapReadableStates(writableStatesMap);
        accountStore = new ReadableAccountStoreImpl(states, readableEntityCounters);
        scheduleStore = new ReadableScheduleStoreImpl(states, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableSchedules = new WritableScheduleStoreImpl(scheduleStates, writableEntityCounters);
        accountsMapById.put(scheduler, schedulerAccount);
        accountsMapById.put(payer, payerAccount);
        accountsMapById.put(admin, adminAccount);
        writableSchedules.put(scheduleInState);
        writableSchedules.put(otherScheduleInState);
        given(readableEntityCounters.getCounterFor(EntityType.SCHEDULE)).willReturn(2L);
        commitScheduleStores();
    }

    private SchedulableTransactionBody createSampleScheduled() {
        return SchedulableTransactionBody.newBuilder()
                .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder())
                .build();
    }

    private TransactionBody alternateCreateTransaction(final TransactionBody originalTransaction) {
        return alternateCreateTransaction(originalTransaction, createAlternateScheduled());
    }

    private TransactionBody alternateCreateTransaction(
            @NonNull final TransactionBody baseTransaction,
            @NonNull final SchedulableTransactionBody childTransaction) {
        final TransactionBody.Builder scheduleCreator = baseTransaction.copyBuilder();
        final ScheduleCreateTransactionBody.Builder builder =
                baseTransaction.scheduleCreate().copyBuilder();
        final TransactionID baseId = baseTransaction.transactionID();
        final TransactionID.Builder idBuilder = baseId.copyBuilder();
        idBuilder.nonce(baseId.nonce() + 1);
        builder.scheduledTransactionBody(childTransaction);
        scheduleCreator.memo(SCHEDULED_TRANSACTION_MEMO);
        return scheduleCreator.transactionID(idBuilder).scheduleCreate(builder).build();
    }

    private TransactionBody originalCreateTransaction(
            @NonNull final SchedulableTransactionBody childTransaction,
            @Nullable final AccountID explicitPayer,
            @Nullable final Key adminKey) {
        final TransactionID createdTransactionId = TransactionID.newBuilder()
                .accountID(scheduler)
                .transactionValidStart(testValidStart)
                .nonce(4444)
                .scheduled(false)
                .build();
        final ScheduleCreateTransactionBody.Builder builder = ScheduleCreateTransactionBody.newBuilder()
                .scheduledTransactionBody(childTransaction)
                .memo(memo)
                .waitForExpiry(false)
                .payerAccountID(scheduler);
        if (explicitPayer != null) builder.payerAccountID(explicitPayer);
        if (adminKey != null) builder.adminKey(adminKey);
        final ScheduleCreateTransactionBody scheduleCreate = builder.build();
        return TransactionBody.newBuilder()
                .transactionID(createdTransactionId)
                .transactionFee(12231913L)
                .scheduleCreate(scheduleCreate)
                .build();
    }

    /**
     * Given an original create transaction, create a {@link Schedule.Builder} for that creation.
     * This populates the builder with everything that can be populated from the ScheduleCreate transaction, with the
     * assumption that the (test ONLY) caller will fill in any additional values needed for the specific test.
     * <p>
     * This is mostly used to ensure we have Schedule objects that closely match their included create transactions
     * so that tests that exercise code that depend on that match (e.g. equality checks, create handler, or anything
     * that compares with a {@link Schedule}).
     *
     * @param originalCreate an original ScheduleCreate transaction to translate to a Schedule
     * @param testTime The consensus time to assume for this test object
     * @param maxExpiry The maximum schedule lifetime in the test configuration.
     * @return a new builder set up with the data from the provided transaction.
     */
    // This is mostly copied from HandlerUtility as that class is not, and should not be, accessible here.
    // This doesn't need to match that code, it just needs to accomplish the goal described.
    private Schedule.Builder createProvisionalBuilder(
            final TransactionBody originalCreate, final Instant testTime, final long maxExpiry) {
        final TransactionID parentTransactionId = originalCreate.transactionID();
        final ScheduleCreateTransactionBody createTransaction = originalCreate.scheduleCreate();
        final AccountID schedulerAccount = parentTransactionId.accountID();
        final Timestamp providedExpirationTime = createTransaction.expirationTime();
        final long calculatedExpirationTime = calculateExpiration(providedExpirationTime, testTime, maxExpiry);
        final ScheduleID nullId = null;

        Schedule.Builder builder = Schedule.newBuilder();
        builder.scheduleId(nullId).deleted(false).executed(false);
        builder.waitForExpiry(createTransaction.waitForExpiry());
        builder.adminKey(createTransaction.adminKey()).schedulerAccountId(parentTransactionId.accountID());
        builder.payerAccountId(createTransaction.payerAccountIDOrElse(schedulerAccount));
        builder.schedulerAccountId(schedulerAccount);
        builder.scheduleValidStart(parentTransactionId.transactionValidStart());
        builder.calculatedExpirationSecond(calculatedExpirationTime);
        builder.originalCreateTransaction(originalCreate);
        builder.memo(createTransaction.memo());
        builder.scheduledTransaction(createTransaction.scheduledTransactionBody());
        return builder;
    }

    private long calculateExpiration(final Timestamp expirationGiven, final Instant testTime, final long maxExpiry) {
        if (expirationGiven != null) {
            return expirationGiven.seconds();
        } else {
            final Instant currentPlusMaxLife = testTime.plusSeconds(maxExpiry);
            return currentPlusMaxLife.getEpochSecond();
        }
    }
}
