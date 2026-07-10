// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractLogAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.bdd.suites.contract.openzeppelin.ERC20ContractInteractions;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Tag(SMART_CONTRACT)
@DisplayName("transferToken")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
@TestMethodOrder(OrderAnnotation.class)
public class TransferTokenTest {

    private static final String TXN_NAME = "transferTxn";

    private static final String TRANSFER_TOKEN = "transferTokenPublic";
    private static final String TRANSFER_TOKENS = "transferTokensPublic";
    private static final String TRANSFER_FROM = "transferFromPublic";
    private static final String TRANSFER_NFT = "transferNFTPublic";
    private static final String TRANSFER_NFTS = "transferNFTsPublic";
    private static final String TRANSFER_FROM_NFT = "transferFromNFTPublic";

    @Contract(contract = "TokenTransferContract", creationGas = 4_000_000L)
    static SpecContract tokenTransferContract;

    @Contract(contract = "NestedHTSTransferrer", creationGas = 4_000_000L)
    static SpecContract tokenReceiverContract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount tokenReceiverAccount;

    @FungibleToken(name = "fungibleToken", initialSupply = 100)
    static SpecFungibleToken fungibleToken;

    @NonFungibleToken(name = "nonFungibleToken", numPreMints = 4)
    static SpecNonFungibleToken nonFungibleToken;

    public record ErcEventRecord(
            Supplier<Long> tokenNum, boolean isNft, Supplier<ByteString> from, Supplier<ByteString> to, Long amount) {

        // from account to contract
        public static ErcEventRecord of(
                final TokenID token, boolean isNft, final AccountID from, final ContractID to, final Long amount) {
            return new ErcEventRecord(
                    token::getTokenNum, isNft, () -> parsedToByteString(from), () -> parsedToByteString(to), amount);
        }

        // from account to account
        public static ErcEventRecord of(
                final TokenID token, boolean isNft, final AccountID from, final AccountID to, final Long amount) {
            return new ErcEventRecord(
                    token::getTokenNum, isNft, () -> parsedToByteString(from), () -> parsedToByteString(to), amount);
        }

        // from contract to account
        public static ErcEventRecord of(
                final TokenID token, boolean isNft, final ContractID from, final AccountID to, final Long amount) {
            return new ErcEventRecord(
                    token::getTokenNum, isNft, () -> parsedToByteString(from), () -> parsedToByteString(to), amount);
        }

        // from contract to contract
        public static ErcEventRecord of(
                final TokenID token, boolean isNft, final ContractID from, final ContractID to, final Long amount) {
            return new ErcEventRecord(
                    token::getTokenNum, isNft, () -> parsedToByteString(from), () -> parsedToByteString(to), amount);
        }
    }

    private static HapiGetTxnRecord validateErcEvent(final ErcEventRecord... receivers) {
        final var withLastEvent = new ArrayList<>(List.of(receivers));
        withLastEvent.add(new ErcEventRecord(
                null, false, null, null, null)); // last log event is event from 'tokenTransferContract'
        return validateErcEvent(
                getTxnRecord(TXN_NAME), withLastEvent.toArray(new ErcEventRecord[receivers.length + 1]));
    }

    public static HapiGetTxnRecord validateErcEvent(final HapiGetTxnRecord request, final ErcEventRecord... receivers) {
        ContractLogAsserts[] logsChecker = new ContractLogAsserts[receivers.length];
        for (int i = 0; i < receivers.length; i++) {
            ErcEventRecord receiver = receivers[i];
            if (receiver.tokenNum() == null || receiver.from() == null || receiver.to() == null) {
                logsChecker[i] = logWith(); // skip this log event
            } else if (receiver.isNft()) {
                logsChecker[i] = logWith()
                        .contract(() -> String.valueOf(receiver.tokenNum().get()))
                        .withTopicsInOrder(() -> List.of(
                                eventSignatureOf(ERC20ContractInteractions.TRANSFER_EVENT_SIGNATURE),
                                receiver.from().get(),
                                receiver.to().get(),
                                parsedToByteString(receiver.amount())));
            } else {
                logsChecker[i] = logWith()
                        .contract(() -> String.valueOf(receiver.tokenNum().get()))
                        .withTopicsInOrder(() -> List.of(
                                eventSignatureOf(ERC20ContractInteractions.TRANSFER_EVENT_SIGNATURE),
                                receiver.from().get(),
                                receiver.to().get()))
                        .longValue(receiver.amount());
            }
        }
        return request.hasPriority(
                recordWith().status(SUCCESS).contractCallResult(resultWith().logs(inOrder(logsChecker))));
    }

    /**
     * The behavior of the transferToken function and transferFrom function differs for contracts that are token owners.
     * The tests below highlight the differences and shows that an allowance approval is required for the transferFrom function
     * in order to be consistent with the ERC20 standard.
     */
    @Nested
    @DisplayName("successful when")
    @Order(1)
    class SuccessfulTransferTokenTest {

        private static final AtomicReference<ContractID> tokenTransferContractId = new AtomicReference<>();
        private static final AtomicReference<ContractID> tokenReceiverContractId = new AtomicReference<>();
        private static final AtomicReference<AccountID> tokenReceiverAccountId = new AtomicReference<>();
        private static final AtomicReference<TokenID> fungibleTokenId = new AtomicReference<>();
        private static final AtomicReference<TokenID> nonFungibleTokenId = new AtomicReference<>();
        private static final AtomicReference<AccountID> nonFungibleTokenTreasuryId = new AtomicReference<>();

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(
                    // Accounts
                    tokenTransferContract.getInfo().andAssert(e -> e.exposingContractId(tokenTransferContractId::set)),
                    tokenReceiverContract.getInfo().andAssert(e -> e.exposingContractId(tokenReceiverContractId::set)),
                    tokenReceiverAccount.getInfo().andAssert(e -> e.exposingIdTo(tokenReceiverAccountId::set)),
                    // FT
                    tokenTransferContract.associateTokens(fungibleToken),
                    tokenReceiverContract.associateTokens(fungibleToken),
                    tokenReceiverAccount.associateTokens(fungibleToken),
                    fungibleToken.treasury().transferUnitsTo(tokenTransferContract, 20, fungibleToken),
                    fungibleToken
                            .getInfo()
                            .andAssert(e -> e.getTokenInfo(info -> fungibleTokenId.set(info.getTokenId()))),
                    // NFT
                    tokenTransferContract.associateTokens(nonFungibleToken),
                    tokenReceiverContract.associateTokens(nonFungibleToken),
                    tokenReceiverAccount.associateTokens(nonFungibleToken),
                    nonFungibleToken.treasury().transferNFTsTo(tokenTransferContract, nonFungibleToken, 1L, 2L, 3L),
                    nonFungibleToken
                            .getInfo()
                            .andAssert(e -> e.getTokenInfo(info -> nonFungibleTokenId.set(info.getTokenId()))),
                    nonFungibleToken
                            .treasury()
                            .getInfo()
                            .andAssert(e -> e.exposingIdTo(nonFungibleTokenTreasuryId::set)));
        }

        // ------------------------------ FT ------------------------------
        @HapiTest
        @DisplayName("'transferToken' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferToken() {
            return hapiTest(
                    // Transfer using transferToken function
                    tokenTransferContract
                            .call(TRANSFER_TOKEN, fungibleToken, tokenTransferContract, tokenReceiverContract, 2L)
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErcEvent(new ErcEventRecord(
                            () -> fungibleTokenId.get().getTokenNum(),
                            false,
                            () -> parsedToByteString(tokenTransferContractId.get()),
                            () -> parsedToByteString(tokenReceiverContractId.get()),
                            2L)));
        }

        @HapiTest
        @DisplayName("'transferTokens' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferTokens() {
            return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                    spec,
                    // Transfer using transferTokens function
                    tokenTransferContract
                            .call(
                                    TRANSFER_TOKENS,
                                    fungibleToken,
                                    new Address[] {
                                        tokenTransferContract.addressOn(spec.targetNetworkOrThrow()),
                                        tokenReceiverContract.addressOn(spec.targetNetworkOrThrow()),
                                        tokenReceiverAccount.addressOn(spec.targetNetworkOrThrow())
                                    },
                                    new long[] {-5L, 2L, 3L})
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErcEvent(
                            new ErcEventRecord(
                                    () -> fungibleTokenId.get().getTokenNum(),
                                    false,
                                    () -> parsedToByteString(tokenTransferContractId.get()),
                                    () -> parsedToByteString(tokenReceiverAccountId.get()),
                                    3L),
                            new ErcEventRecord(
                                    () -> fungibleTokenId.get().getTokenNum(),
                                    false,
                                    () -> parsedToByteString(tokenTransferContractId.get()),
                                    () -> parsedToByteString(tokenReceiverContractId.get()),
                                    2L)))));
        }

        @HapiTest
        @DisplayName("'transferTokens' function without explicit allowance and multiple senders")
        public Stream<DynamicTest> transferUsingTransferTokensMultipleSenders(
                @NonNull @Account(name = "sender1") final SpecAccount sender1,
                @NonNull @Account(name = "sender2") final SpecAccount sender2,
                @NonNull @Account(name = "receiver1") final SpecAccount receiver1,
                @NonNull @Account(name = "receiver2") final SpecAccount receiver2) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        // we are using exactly this 'association order'
                        // to exclude possibility of accountNum sorting for transfers
                        receiver2.associateTokens(fungibleToken),
                        receiver1.associateTokens(fungibleToken),
                        sender1.associateTokens(fungibleToken),
                        sender2.associateTokens(fungibleToken),
                        fungibleToken.treasury().transferUnitsTo(sender1, 10, fungibleToken),
                        fungibleToken.treasury().transferUnitsTo(sender2, 10, fungibleToken),
                        sender1.authorizeContract(tokenTransferContract),
                        sender2.authorizeContract(tokenTransferContract));
                final var tokenId = spec.registry().getTokenID(fungibleToken.name());
                final var sender1Id = spec.registry().getAccountID(sender1.name());
                final var sender2Id = spec.registry().getAccountID(sender2.name());
                final var receiver1Id = spec.registry().getAccountID(receiver1.name());
                final var receiver2Id = spec.registry().getAccountID(receiver2.name());
                // assert accountNum order to exclude possibility of accountNum sorting for transfers
                Assertions.assertTrue(receiver2Id.getAccountNum() < receiver1Id.getAccountNum());
                Assertions.assertTrue(receiver1Id.getAccountNum() < sender1Id.getAccountNum());
                Assertions.assertTrue(sender1Id.getAccountNum() < sender2Id.getAccountNum());
                allRunFor(
                        spec,
                        // Transfer using transferTokens function
                        tokenTransferContract
                                .call(
                                        TRANSFER_TOKENS,
                                        fungibleToken,
                                        new Address[] {
                                            HapiParserUtil.asHeadlongAddress(Utils.asAddress(receiver1Id)),
                                            HapiParserUtil.asHeadlongAddress(Utils.asAddress(sender2Id)),
                                            HapiParserUtil.asHeadlongAddress(Utils.asAddress(receiver2Id)),
                                            HapiParserUtil.asHeadlongAddress(Utils.asAddress(sender1Id)),
                                        },
                                        new long[] {4L, -3L, 3L, -4L})
                                .gas(1_000_000L)
                                .via(TXN_NAME),
                        // order of 'input transfers' are sorted by HTS, and we got correct ERC20 events in result
                        validateErcEvent(
                                ErcEventRecord.of(tokenId, false, sender1Id, receiver1Id, 4L),
                                ErcEventRecord.of(tokenId, false, sender2Id, receiver2Id, 3L)));
            }));
        }

        @HapiTest
        @DisplayName("'transferFrom' function given allowance")
        public Stream<DynamicTest> transferUsingTransferFromWithAllowance() {
            return hapiTest(
                    // Approve the transfer contract to spend 2 tokens
                    tokenTransferContract
                            .call("approvePublic", fungibleToken, tokenTransferContract, BigInteger.valueOf(2L))
                            .gas(1_000_000L),
                    // Transfer using transferFrom function
                    tokenTransferContract
                            .call(
                                    TRANSFER_FROM,
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    BigInteger.valueOf(2L))
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErcEvent(new ErcEventRecord(
                            () -> fungibleTokenId.get().getTokenNum(),
                            false,
                            () -> parsedToByteString(tokenTransferContractId.get()),
                            () -> parsedToByteString(tokenReceiverContractId.get()),
                            2L)));
        }

        // ------------------------------ NFT ------------------------------
        @HapiTest
        @DisplayName("'transferNFT' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferNft() {
            return hapiTest(
                    // Transfer using transferToken function
                    tokenTransferContract
                            .call(TRANSFER_NFT, nonFungibleToken, tokenTransferContract, tokenReceiverContract, 1L)
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErcEvent(new ErcEventRecord(
                            () -> nonFungibleTokenId.get().getTokenNum(),
                            true,
                            () -> parsedToByteString(tokenTransferContractId.get()),
                            () -> parsedToByteString(tokenReceiverContractId.get()),
                            1L)));
        }

        @HapiTest
        @DisplayName("'transferNFTs' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferNfts() {
            return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                    spec,
                    // Transfer using transferTokens function
                    tokenTransferContract
                            .call(
                                    TRANSFER_NFTS,
                                    nonFungibleToken,
                                    new Address[] {
                                        tokenTransferContract.addressOn(spec.targetNetworkOrThrow()),
                                        tokenTransferContract.addressOn(spec.targetNetworkOrThrow())
                                    },
                                    new Address[] {
                                        tokenReceiverContract.addressOn(spec.targetNetworkOrThrow()),
                                        tokenReceiverAccount.addressOn(spec.targetNetworkOrThrow())
                                    },
                                    new long[] {2L, 3L})
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErcEvent(
                            new ErcEventRecord(
                                    () -> nonFungibleTokenId.get().getTokenNum(),
                                    true,
                                    () -> parsedToByteString(tokenTransferContractId.get()),
                                    () -> parsedToByteString(tokenReceiverContractId.get()),
                                    2L),
                            new ErcEventRecord(
                                    () -> nonFungibleTokenId.get().getTokenNum(),
                                    true,
                                    () -> parsedToByteString(tokenTransferContractId.get()),
                                    () -> parsedToByteString(tokenReceiverAccountId.get()),
                                    3L)))));
        }

        @HapiTest
        @DisplayName("'transferFromNFT' function given allowance")
        public Stream<DynamicTest> transferUsingTransferFromNftWithAllowance() {
            return hapiTest(
                    // Approve the 'TokenTransferContract' to spend 4th NFT token
                    // We cant approve from 'TokenTransferContract' because it is not an owner of the token.
                    // "DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL"
                    // Event if 'TokenTransferContract' is an owner of the token, there is
                    // "SPENDER_ACCOUNT_SAME_AS_OWNER"
                    cryptoApproveAllowance()
                            .payingWith(DEFAULT_PAYER)
                            .addNftAllowance(
                                    "nonFungibleToken" + SpecFungibleToken.DEFAULT_TREASURY_NAME_SUFFIX,
                                    "nonFungibleToken",
                                    "TokenTransferContract",
                                    false,
                                    List.of(4L))
                            .signedBy(
                                    DEFAULT_PAYER, "nonFungibleToken" + SpecFungibleToken.DEFAULT_TREASURY_NAME_SUFFIX)
                            .fee(ONE_HBAR),
                    // Transfer using transferFrom function
                    tokenTransferContract
                            .call(
                                    TRANSFER_FROM_NFT,
                                    nonFungibleToken,
                                    nonFungibleToken.treasury(),
                                    tokenReceiverContract,
                                    BigInteger.valueOf(4L))
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErcEvent(new ErcEventRecord(
                            () -> nonFungibleTokenId.get().getTokenNum(),
                            true,
                            () -> parsedToByteString(nonFungibleTokenTreasuryId.get()),
                            () -> parsedToByteString(tokenReceiverContractId.get()),
                            4L)));
        }
    }

    @Nested
    @DisplayName("fails when")
    @Order(2)
    class FailedTransferTokenTest {
        @HapiTest
        @DisplayName("'transferFrom' function without allowance")
        public Stream<DynamicTest> transferUsingTransferFromWithoutAllowance() {
            return hapiTest(
                    // Transfer using transferFrom function without allowance should fail
                    tokenTransferContract
                            .call(
                                    TRANSFER_FROM,
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    BigInteger.valueOf(2L))
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("'transferToken' function from receiver contract")
        public Stream<DynamicTest> transferUsingTransferFromReceiver() {
            return hapiTest(
                    // Transfer using receiver contract transfer function should fail
                    tokenReceiverContract
                            .call("transfer", fungibleToken, tokenTransferContract, tokenReceiverContract, 2L)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)));
        }
    }
}
