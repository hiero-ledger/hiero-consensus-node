// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddressInTopic;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForBalances;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForEmptyBalance;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareAccountAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareContractAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokenAddresses;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_HAS_PENDING_AIRDROPS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_NFT_AIRDROP_ALREADY_EXISTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.contract.precompile.token.TransferTokenTest;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@Tag(SMART_CONTRACT)
public class AirdropFromContractTest {

    private static final String TXN_NAME = "AirdropTxn";
    private static final String NFT_TXN_NAME = "NftAirdropTxn";

    @Contract(contract = "Airdrop", creationGas = 5_000_000)
    static SpecContract airdropContract;

    private static void checkBasicERC20Event(
            final HapiSpec spec, final SpecFungibleToken token, final SpecContract sender, final SpecAccount receiver) {
        final var tokenId = spec.registry().getTokenID(token.name());
        final var senderId = spec.registry().getContractId(sender.name());
        final var receiverId = spec.registry().getAccountID(receiver.name());
        allRunFor(
                spec,
                // check ERC20 event
                TransferTokenTest.validateErcEvent(
                                getTxnRecord(TXN_NAME),
                                TransferTokenTest.ErcEventRecord.of(tokenId, false, senderId, receiverId, 10L))
                        .andAllChildRecords());
    }

    private static void checkBasicERC721Event(
            final HapiSpec spec,
            final SpecNonFungibleToken token,
            final SpecContract sender,
            final SpecAccount receiver) {
        final var tokenId = spec.registry().getTokenID(token.name());
        final var senderId = spec.registry().getContractId(sender.name());
        final var receiverId = spec.registry().getAccountID(receiver.name());
        allRunFor(
                spec,
                // check ERC721 event
                TransferTokenTest.validateErcEvent(
                                getTxnRecord(NFT_TXN_NAME),
                                TransferTokenTest.ErcEventRecord.of(tokenId, true, senderId, receiverId, 1L))
                        .andAllChildRecords());
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract Airdrops a token to a receiver who is associated to the token")
    public Stream<DynamicTest> airdropTokenToAccount(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(TXN_NAME),
                    getTxnRecord(TXN_NAME).hasPriority(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance()
                            .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                            .andAssert(balance -> balance.hasTinyBars(100L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
            // check ERC20 event
            checkBasicERC20Event(spec, token, sender, receiver);
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop token from contact with custom fee")
    public Stream<DynamicTest> airdropTokenWithCustomFee(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull
                    @FungibleToken(
                            initialSupply = 1_000_000L,
                            keys = {FEE_SCHEDULE_KEY})
                    final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    sender.authorizeContract(airdropContract),
                    token.treasury().transferUnitsTo(sender, 500_000L, token),
                    receiver.getBalance().andAssert(balance -> balance.hasTinyBars(100L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    tokenFeeScheduleUpdate(token.name())
                            .withCustom(fractionalFeeNetOfTransfers(
                                    1L, 10L, 1L, OptionalLong.of(100L), airdropContract.name())));
            final var tokenId = spec.registry().getTokenID(token.name());
            final var senderId = spec.registry().getContractId(sender.name());
            final var receiverId = spec.registry().getAccountID(receiver.name());
            final var contractId = spec.registry().getContractId(airdropContract.name());
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(TXN_NAME),
                    getTxnRecord(TXN_NAME).hasPriority(recordWith().pendingAirdropsCount(0)),
                    // check ERC20 event with fee
                    // Events are sorted by amount DESC, so the 10-unit transfer comes before the 1-unit fee
                    TransferTokenTest.validateErcEvent(
                            getTxnRecord(TXN_NAME),
                            TransferTokenTest.ErcEventRecord.of(tokenId, false, senderId, receiverId, 10L),
                            TransferTokenTest.ErcEventRecord.of(tokenId, false, senderId, contractId, 1L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 500989)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10)),
                    airdropContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop multiple tokens from contract that is already associated with them")
    public Stream<DynamicTest> airdropMultipleTokens(
            @NonNull @Account(maxAutoAssociations = 5, tinybarBalance = 100L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3, nft1, nft2, nft3),
                    sender.authorizeContract(airdropContract),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                    token3.treasury().transferUnitsTo(sender, 1_000L, token3));
            allRunFor(
                    spec,
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L),
                    receiver.associateTokens(token1, token2, token3, nft1, nft2, nft3));
            allRunFor(spec, checkForEmptyBalance(receiver, List.of(token1, token2, token3), List.of()));
            final var serials = new long[] {1L, 1L, 1L};
            final var token1Id = spec.registry().getTokenID(token1.name());
            final var token2Id = spec.registry().getTokenID(token2.name());
            final var token3Id = spec.registry().getTokenID(token3.name());
            final var nft1Id = spec.registry().getTokenID(nft1.name());
            final var nft2Id = spec.registry().getTokenID(nft2.name());
            final var nft3Id = spec.registry().getTokenID(nft3.name());
            final var senderId = spec.registry().getContractId(sender.name());
            final var receiverId = spec.registry().getAccountID(receiver.name());
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "mixedAirdrop",
                                    prepareTokenAddresses(spec, token1, token2, token3),
                                    prepareTokenAddresses(spec, nft1, nft2, nft3),
                                    prepareContractAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver, receiver, receiver),
                                    prepareContractAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver, receiver, receiver),
                                    10L,
                                    serials)
                            .gas(1750000)
                            .via(TXN_NAME),
                    checkForBalances(receiver, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)),
                    // check ERC20/ERC721 event
                    TransferTokenTest.validateErcEvent(
                            getTxnRecord(TXN_NAME),
                            TransferTokenTest.ErcEventRecord.of(token1Id, false, senderId, receiverId, 10L),
                            TransferTokenTest.ErcEventRecord.of(token2Id, false, senderId, receiverId, 10L),
                            TransferTokenTest.ErcEventRecord.of(token3Id, false, senderId, receiverId, 10L),
                            TransferTokenTest.ErcEventRecord.of(nft1Id, true, senderId, receiverId, 1L),
                            TransferTokenTest.ErcEventRecord.of(nft2Id, true, senderId, receiverId, 1L),
                            TransferTokenTest.ErcEventRecord.of(nft3Id, true, senderId, receiverId, 1L)));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop token from contact")
    public Stream<DynamicTest> airdropTokenToAccountWithFreeSlots(
            @NonNull @Account(tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    receiver.associateTokens(token),
                    sender.associateTokens(token),
                    sender.authorizeContract(airdropContract),
                    token.treasury().transferUnitsTo(sender, 500_000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .gas(10000000)
                            .sending(5L)
                            .via(TXN_NAME),
                    getTxnRecord(TXN_NAME).logged().andAllChildRecords(),
                    receiver.getBalance()
                            .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                            .andAssert(balance -> balance.hasTinyBars(100L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(0)));
            // check ERC20 event
            checkBasicERC20Event(spec, token, sender, receiver);
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract account airdrops a single token to an ECDSA account")
    @Tag(MATS)
    public Stream<DynamicTest> airdropTokenToECDSAAccount(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 15_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    receiver.getBalance(),
                    newKeyNamed("key").shape(KeyShape.SECP256K1),
                    cryptoUpdate(receiver.name()).key("key"),
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(TXN_NAME),
                    getTxnRecord(TXN_NAME).hasPriority(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance()
                            .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                            .andAssert(balance -> balance.hasTinyBars(100L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
            // check ERC20 event
            checkBasicERC20Event(spec, token, sender, receiver);
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract account airdrops a single token to an ED25519 account")
    @Tag(MATS)
    public Stream<DynamicTest> airdropTokenToED25519Account(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    receiver.getBalance(),
                    newKeyNamed("key").shape(KeyShape.ED25519),
                    cryptoUpdate(receiver.name()).key("key"),
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(TXN_NAME),
                    getTxnRecord(TXN_NAME).hasPriority(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance()
                            .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                            .andAssert(balance -> balance.hasTinyBars(100L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
            // check ERC20 event
            checkBasicERC20Event(spec, token, sender, receiver);
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract account airdrops a single token to an account alias with free association slots")
    public Stream<DynamicTest> airdropToAccountWithFreeAutoAssocSlots(
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @Account(maxAutoAssociations = -1, tinybarBalance = 100L) final SpecAccount receiver,
            @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token,
            @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token, nft),
                    airdropContract.associateTokens(token, nft),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    nft.treasury().transferNFTsTo(sender, nft, 1L));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(TXN_NAME),
                    airdropContract
                            .call("nftAirdrop", nft, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(NFT_TXN_NAME),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(2)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)));
            // check ERC20 event
            checkBasicERC20Event(spec, token, sender, receiver);
            // check ERC721 event
            checkBasicERC721Event(spec, nft, sender, receiver);
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract account airdrops multiple tokens to contract alias with unlimited association slots")
    public Stream<DynamicTest> airdropToAccountWithUnlimitedAutoAssocSlots(
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L, maxAutoAssociations = -1)
                    final SpecContract receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token1, token2),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2));
            allRunFor(spec, checkForEmptyBalance(receiver, List.of(token1, token2), List.of()));
            final var token1Id = spec.registry().getTokenID(token1.name());
            final var token2Id = spec.registry().getTokenID(token2.name());
            // com.hedera.hapi.node.base.AccountId
            final var tempSenderId =
                    sender.contractOrThrow(spec.targetNetworkOrThrow()).accountIdOrThrow();
            // getting 'senderId' from 'sender.contract' because it is replaced by alias receiver in registry
            final var senderId = ContractID.newBuilder()
                    .setContractNum(tempSenderId.accountNumOrThrow())
                    .build();
            final var receiverId = spec.registry().getAccountID(receiver.name());
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, List.of(token1, token2)),
                                    prepareContractAddresses(spec, List.of(sender, sender)),
                                    prepareContractAddresses(spec, List.of(receiver, receiver)),
                                    10L)
                            .sending(450_000_000L)
                            .gas(1_750_000L)
                            .via(TXN_NAME),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 10L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 10L)),
                    // check ERC20 event
                    TransferTokenTest.validateErcEvent(
                                    getTxnRecord(TXN_NAME),
                                    TransferTokenTest.ErcEventRecord.of(token1Id, false, senderId, receiverId, 10L),
                                    TransferTokenTest.ErcEventRecord.of(token2Id, false, senderId, receiverId, 10L))
                            .hasChildRecords(recordWith().pendingAirdropsCount(0)));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract account airdrops a multiple tokens to an account alias without free association slots")
    public Stream<DynamicTest> airdropToAccountWithNoFreeAutoAssocSlots(
            @NonNull @Account(tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token2) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token1, token2),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2));
            allRunFor(spec, checkForEmptyBalance(receiver, List.of(token1, token2), List.of()));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, List.of(token1, token2)),
                                    prepareContractAddresses(spec, List.of(sender, sender)),
                                    prepareAccountAddresses(spec, List.of(receiver, receiver)),
                                    10L)
                            .sending(450_000_000L)
                            .gas(1_750_000L)
                            .via(TXN_NAME),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    // check there is no ERC20 event for 'PendingAirdrop'
                    TransferTokenTest.validateErcEvent(getTxnRecord(TXN_NAME))
                            .hasChildRecords(recordWith()
                                    .pendingAirdropsCount(2)
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(10L, token1.name()).between(sender.name(), receiver.name()),
                                            moving(10L, token2.name()).between(sender.name(), receiver.name())))));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract account airdrops token to an address with no account on it")
    public Stream<DynamicTest> airdropToAddressWithNoAccount(
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        final var hollowAccountKey = "hollowAccountKey";
        return hapiTest(
                newKeyNamed(hollowAccountKey).shape(KeyShape.SECP256K1),
                sender.authorizeContract(airdropContract),
                sender.associateTokens(token),
                airdropContract.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 1_000L, token),
                withOpContext((spec, opLog) -> {
                    final var tokenId = spec.registry().getTokenID(token.name());
                    final var senderId = spec.registry().getContractId(sender.name());
                    final var ecdsaKey = spec.registry()
                            .getKey(hollowAccountKey)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddressBytes = recoverAddressFromPubKey(ecdsaKey);
                    allRunFor(
                            spec,
                            airdropContract
                                    .call(
                                            "tokenAirdrop",
                                            token,
                                            sender,
                                            HapiParserUtil.asHeadlongAddress(evmAddressBytes),
                                            10L)
                                    .sending(85_000_000L)
                                    .gas(1_500_000L)
                                    .via(TXN_NAME),
                            getAutoCreatedAccountBalance(hollowAccountKey).hasTokenBalance(token.name(), 10L),
                            // check ERC20 event
                            TransferTokenTest.validateErcEvent(
                                    getTxnRecord(TXN_NAME),
                                    new TransferTokenTest.ErcEventRecord(
                                            tokenId::getTokenNum,
                                            false,
                                            () -> parsedToByteString(senderId),
                                            () -> ByteString.copyFrom(asAddressInTopic(evmAddressBytes)),
                                            10L)));
                }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName(
            "Contract airdrops a token to an account, then the receiver claims the airdrop, then the sender airdrops the same token again")
    public Stream<DynamicTest> airdropToAccountAgainAfterReceiverClaims(
            @NonNull @Account(tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 10L, token));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 5L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via(TXN_NAME),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    // check there is no ERC20 event for 'pendingAirdrop'
                    TransferTokenTest.validateErcEvent(getTxnRecord(TXN_NAME))
                            .hasChildRecords(recordWith().pendingAirdropsCount(1))
                            .hasChildRecords(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(5L, token.name()).between(sender.name(), receiver.name())))),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
            allRunFor(
                    spec,
                    // this is HAPI 'claim', so no ERC20 events expected
                    tokenClaimAirdrop(pendingAirdrop(sender.name(), receiver.name(), token.name()))
                            .payingWith(receiver.name()),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 5L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 5L)));
            final var tokenId = spec.registry().getTokenID(token.name());
            final var senderId = spec.registry().getContractId(sender.name());
            final var receiverId = spec.registry().getAccountID(receiver.name());
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 3L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn2"),
                    // check there is ERC20 event
                    TransferTokenTest.validateErcEvent(
                            getTxnRecord("AirdropTxn2"),
                            TransferTokenTest.ErcEventRecord.of(tokenId, false, senderId, receiverId, 3L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 8L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 2L)));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Contract airdrops multiple times that FT to an account, then only one pending transaction is created")
    public Stream<DynamicTest> airdropFTToAccountMultipleTimes(
            @NonNull @Account(tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 10L, token));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn1"),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn2"),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn3"),
                    // check there is no ERC20 event for 'pendingAirdrop'
                    TransferTokenTest.validateErcEvent(getTxnRecord("AirdropTxn1"))
                            .hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    TransferTokenTest.validateErcEvent(getTxnRecord("AirdropTxn2"))
                            .hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    TransferTokenTest.validateErcEvent(getTxnRecord("AirdropTxn3"))
                            .hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)));
            allRunFor(
                    spec,
                    tokenClaimAirdrop(pendingAirdrop(sender.name(), receiver.name(), token.name()))
                            .payingWith(receiver.name()),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 3L)));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName(
            "Contract airdrops a FT to an account, then associate the receiver, then airdrops the same token again")
    public Stream<DynamicTest> airdropFTToAccountThenAssociateTheReceiverAndAirdropAgain(
            @NonNull @Account(tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 10L, token));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn1"),
                    // check there is no ERC20 event for 'pendingAirdrop'
                    TransferTokenTest.validateErcEvent(getTxnRecord("AirdropTxn1"))
                            .hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    receiver.associateTokens(token));
            final var tokenId = spec.registry().getTokenID(token.name());
            final var senderId = spec.registry().getContractId(sender.name());
            final var receiverId = spec.registry().getAccountID(receiver.name());
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn2"),
                    TransferTokenTest.validateErcEvent(
                                    getTxnRecord("AirdropTxn2"),
                                    TransferTokenTest.ErcEventRecord.of(tokenId, false, senderId, receiverId, 1L))
                            .hasChildRecords(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)));
        }));
    }

    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Multiple contracts airdrop tokens to multiple accounts.")
    public Stream<DynamicTest> multipleContractsAirdropTokensToMultipleAccounts(
            @NonNull @Account(maxAutoAssociations = -1, tinybarBalance = 100_000_000L) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1, tinybarBalance = 100_000_000L) final SpecAccount receiver2,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender1,
            @NonNull @Contract(contract = "EmptyConstructor", creationGas = 100_000_000L) final SpecContract sender2,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token2) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender1.authorizeContract(airdropContract),
                    sender2.authorizeContract(airdropContract),
                    sender1.associateTokens(token1),
                    sender2.associateTokens(token2),
                    token1.treasury().transferUnitsTo(sender1, 100L, token1),
                    token2.treasury().transferUnitsTo(sender2, 100L, token2));
            allRunFor(spec, checkForEmptyBalance(receiver1, List.of(token1), List.of()));
            allRunFor(spec, checkForEmptyBalance(receiver2, List.of(token2), List.of()));
            final var token1Id = spec.registry().getTokenID(token1.name());
            final var token2Id = spec.registry().getTokenID(token2.name());
            final var sender1Id = spec.registry().getContractId(sender1.name());
            final var sender2Id = spec.registry().getContractId(sender2.name());
            final var receiver1Id = spec.registry().getAccountID(receiver1.name());
            final var receiver2Id = spec.registry().getAccountID(receiver2.name());
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, List.of(token1, token2)),
                                    prepareContractAddresses(spec, List.of(sender1, sender2)),
                                    prepareAccountAddresses(spec, List.of(receiver1, receiver2)),
                                    1L)
                            .sending(450_000_000L)
                            .gas(2_750_000L)
                            .via("pendingAirdropsMulti"),
                    TransferTokenTest.validateErcEvent(
                                    getTxnRecord("pendingAirdropsMulti"),
                                    TransferTokenTest.ErcEventRecord.of(token1Id, false, sender1Id, receiver1Id, 1L),
                                    TransferTokenTest.ErcEventRecord.of(token2Id, false, sender2Id, receiver2Id, 1L))
                            .hasChildRecords(recordWith().pendingAirdropsCount(0)),
                    receiver1.getBalance().andAssert(balance -> balance.hasTinyBars(100_000_000L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTinyBars(100_000_000L)),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 1L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 1L)));
        }));
    }

    @Nested
    class AirdropFromContractNegativeCases {

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Airdrop token with custom fees while the sender cannot pay the fees")
        public Stream<DynamicTest> airdropFromContractWhileTheSenderCannotPayTheCustomFees(
                @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
                @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
                @NonNull
                        @FungibleToken(
                                initialSupply = 1_000_000L,
                                name = "airdropToken",
                                keys = {ADMIN_KEY, FEE_SCHEDULE_KEY})
                        final SpecFungibleToken token,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken tokenForFee) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token, tokenForFee),
                        tokenAssociate(GENESIS, tokenForFee.name()),
                        sender.authorizeContract(airdropContract),
                        token.treasury().transferUnitsTo(sender, 500_000L, token),
                        tokenFeeScheduleUpdate(token.name()).withCustom(fixedHtsFee(5L, tokenForFee.name(), GENESIS)));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiver, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via(TXN_NAME)
                                .andAssert(txn -> txn.hasKnownStatuses(
                                        CONTRACT_REVERT_EXECUTED, INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE)));
            }));
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdrop nft to the same account twice should fail with TOKEN_ID_REPEATED_IN_TOKEN_LIST for single airdrop or PENDING_NFT_AIRDROP_ALREADY_EXISTS for multiple airdrops")
        public Stream<DynamicTest> airdropNftToTheSameAccountTwice(
                @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount associatedReceiver,
                @NonNull @Account(tinybarBalance = 100L) final SpecAccount notAssociatedReceiver,
                @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.authorizeContract(airdropContract),
                        sender.associateTokens(nft),
                        nft.treasury().transferNFTsTo(sender, nft, 1L),
                        associatedReceiver.associateTokens(nft),
                        notAssociatedReceiver
                                .getBalance()
                                .andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)));
                allRunFor(
                        spec,
                        // Airdrop the same nft serial to the same account
                        // when the account is already associated with the nft we don't get a pending airdrop
                        // so when we try to do so with a single airdrop it will fail with
                        // TOKEN_ID_REPEATED_IN_TOKEN_LIST
                        airdropContract
                                .call(
                                        "nftNAmountAirdrops",
                                        prepareTokenAddresses(spec, nft, nft, nft),
                                        prepareContractAddresses(spec, sender, sender, sender),
                                        prepareAccountAddresses(
                                                spec, associatedReceiver, associatedReceiver, associatedReceiver),
                                        new long[] {1L, 1L, 1L})
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .andAssert(txn -> txn.hasKnownStatuses(
                                        CONTRACT_REVERT_EXECUTED, TOKEN_ID_REPEATED_IN_TOKEN_LIST)));
                allRunFor(
                        spec,
                        // We validate the same case but with an account that is not associated with the nft
                        airdropContract
                                .call(
                                        "nftNAmountAirdrops",
                                        prepareTokenAddresses(spec, nft, nft, nft),
                                        prepareContractAddresses(spec, sender, sender, sender),
                                        prepareAccountAddresses(
                                                spec,
                                                notAssociatedReceiver,
                                                notAssociatedReceiver,
                                                notAssociatedReceiver),
                                        new long[] {1L, 1L, 1L})
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .andAssert(txn -> txn.hasKnownStatuses(
                                        CONTRACT_REVERT_EXECUTED, TOKEN_ID_REPEATED_IN_TOKEN_LIST)));
                allRunFor(
                        spec,
                        // Now we airdrop a single to the same account to go to the pending airdrop list
                        airdropContract
                                .call("nftAirdrop", nft, sender, notAssociatedReceiver, 1L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via("AirdropToPendingState"),
                        // check there is no ERC721 event for 'pendingAirdrop'
                        TransferTokenTest.validateErcEvent(getTxnRecord("AirdropToPendingState"))
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(nft.name(), 1L)
                                                .between(sender.name(), notAssociatedReceiver.name())))));
                allRunFor(
                        spec,
                        // Now we try to airdrop the same nft serial to the same account that should fail with
                        // PENDING_NFT_AIRDROP_ALREADY_EXISTS
                        airdropContract
                                .call("nftAirdrop", nft, sender, notAssociatedReceiver, 1L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .andAssert(txn -> txn.hasKnownStatuses(
                                        CONTRACT_REVERT_EXECUTED, PENDING_NFT_AIRDROP_ALREADY_EXISTS)));
            }));
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdrop token amount of Long.MAX_VALUE then try to airdrop 1 more token to the same receiver should fail")
        public Stream<DynamicTest> airdropMaxLongPlusOneShouldFail(
                @NonNull @FungibleToken(initialSupply = Long.MAX_VALUE) final SpecFungibleToken token,
                @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
                @NonNull @Account(maxAutoAssociations = 1, tinybarBalance = 100L) final SpecAccount receiver) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.authorizeContract(airdropContract),
                        sender.associateTokens(token),
                        token.treasury().transferUnitsTo(sender, Long.MAX_VALUE, token));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiver, Long.MAX_VALUE)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via(TXN_NAME));
                final var tokenId = spec.registry().getTokenID(token.name());
                final var senderId = spec.registry().getContractId(sender.name());
                final var receiverId = spec.registry().getAccountID(receiver.name());
                allRunFor(
                        spec,
                        // check ERC20 event
                        TransferTokenTest.validateErcEvent(
                                getTxnRecord(TXN_NAME),
                                TransferTokenTest.ErcEventRecord.of(
                                        tokenId, false, senderId, receiverId, Long.MAX_VALUE)),
                        receiver.getBalance()
                                .andAssert(balance -> balance.hasTokenBalance(token.name(), Long.MAX_VALUE)));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiver, 1L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .andAssert(txn ->
                                        txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INSUFFICIENT_TOKEN_BALANCE)));
            }));
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Contract tries to airdrop a token to itself")
        public Stream<DynamicTest> airdropTokenToItself(
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token,
                @NonNull @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.authorizeContract(airdropContract),
                        sender.associateTokens(token),
                        token.treasury().transferUnitsTo(sender, 1_000L, token));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, sender, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .andAssert(txn -> txn.hasKnownStatuses(
                                        CONTRACT_REVERT_EXECUTED, ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)));
            }));
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Contract airdrops to a pending state then tries to SELFDESTRUCT")
        public Stream<DynamicTest> contractAirdropsThenSelfdestructs(
                @NonNull @FungibleToken() final SpecFungibleToken token,
                @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract contract,
                @NonNull @Account(tinybarBalance = 100L) final SpecAccount receiver) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        contract.authorizeContract(airdropContract),
                        contract.associateTokens(token),
                        token.treasury().transferUnitsTo(contract, 100, token));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, contract, receiver, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via("pendingAirdropTxn"),
                        // check there is no ERC721 event for 'pendingAirdrop'
                        TransferTokenTest.validateErcEvent(getTxnRecord("pendingAirdropTxn"))
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(
                                                includingFungiblePendingAirdrop(TokenMovement.moving(10L, token.name())
                                                        .between(contract.name(), receiver.name())))));
                allRunFor(
                        spec,
                        contractDelete(contract.name())
                                .hasKnownStatusFrom(CONTRACT_REVERT_EXECUTED, ACCOUNT_HAS_PENDING_AIRDROPS));
            }));
        }
    }
}
