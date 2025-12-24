// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
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
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.contract.openzeppelin.ERC20ContractInteractions;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Tag(SMART_CONTRACT)
@Tag(MATS)
@DisplayName("transferToken")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
@TestMethodOrder(OrderAnnotation.class)
public class TransferTokenTest {

    @Contract(contract = "TokenTransferContract", creationGas = 4_000_000L)
    static SpecContract tokenTransferContract;

    @Contract(contract = "NestedHTSTransferrer", creationGas = 4_000_000L)
    static SpecContract tokenReceiverContract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount tokenReceiverAccount;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken fungibleToken;

    @Account(name = "account", tinybarBalance = 100 * ONE_HUNDRED_HBARS)
    static SpecAccount account;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        fungibleToken.builder().totalSupply(20L);
        fungibleToken.setTreasury(account);
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

        public static final String TRANSFER_TOKEN = "transferTokenPublic";
        public static final String TRANSFER_TOKENS = "transferTokensPublic";
        // TODO Glib: add tests
        public static final String TRANSFER_NFT = "transferNFTPublic";
        public static final String TRANSFER_NFTS = "transferNFTsPublic";

        private static final String TXN_NAME = "transferTrx";
        private static final AtomicReference<ContractID> tokenTransferContractId = new AtomicReference<>();
        private static final AtomicReference<ContractID> tokenReceiverContractId = new AtomicReference<>();
        private static final AtomicReference<AccountID> tokenReceiverAccountId = new AtomicReference<>();
        private static final AtomicReference<TokenID> fungibleTokenId = new AtomicReference<>();

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(
                    tokenTransferContract.associateTokens(fungibleToken),
                    tokenReceiverContract.associateTokens(fungibleToken),
                    tokenReceiverAccount.associateTokens(fungibleToken),
                    tokenTransferContract.receiveUnitsFrom(account, fungibleToken, 20L),
                    tokenTransferContract.getInfo().andAssert(e -> e.exposingContractId(tokenTransferContractId::set)),
                    tokenReceiverContract.getInfo().andAssert(e -> e.exposingContractId(tokenReceiverContractId::set)),
                    tokenReceiverAccount.getInfo().andAssert(e -> e.exposingIdTo(tokenReceiverAccountId::set)),
                    fungibleToken
                            .getInfo()
                            .andAssert(e -> e.getTokenInfo(info -> fungibleTokenId.set(info.getTokenId()))));
        }

        private record ReceiverAmount(Supplier<ByteString> receiverTopic, Long receiverData) {
        }

        private HapiGetTxnRecord validateErc20Event(final ReceiverAmount... receivers) {
            ContractLogAsserts[] logsChecker = new ContractLogAsserts[receivers.length + 1];
            for (int i = 0; i < receivers.length; i++) {
                ReceiverAmount receiver = receivers[i];
                logsChecker[i] = logWith() // first log event is ERC event
                        .contract(() -> String.valueOf(fungibleTokenId
                                .get()
                                .getTokenNum()))
                        .withTopicsInOrder(() -> List.of(
                                eventSignatureOf(
                                        ERC20ContractInteractions.TRANSFER_EVENT_SIGNATURE),
                                parsedToByteString(tokenTransferContractId.get()),
                                receiver.receiverTopic().get()))
                        .longValue(receiver.receiverData());
            }
            logsChecker[receivers.length] = logWith(); // last log event is event from 'tokenTransferContract'
            return getTxnRecord(TXN_NAME)
                    .logged()
                    .hasPriority(recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .logs(inOrder(logsChecker))));
        }

        @HapiTest
        @DisplayName("transferring owner's tokens using 'transferToken' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferToken() {
            return hapiTest(
                    // Transfer using transferToken function
                    tokenTransferContract
                            .call(
                                    TRANSFER_TOKEN,
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    2L)
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErc20Event(new ReceiverAmount(() -> parsedToByteString(tokenReceiverContractId.get()), 2L))
            );
        }

        @HapiTest
        @DisplayName("transferring owner's tokens using 'transferTokens' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferTokens() {
            return hapiTest(
                    withOpContext((spec, opLog) ->
                                    allRunFor(spec,
//                                             Transfer using transferTokens function
                                            tokenTransferContract
                                                    .call(
                                                            TRANSFER_TOKENS,
                                                            fungibleToken,
                                                            new Address[]{
                                                                    tokenTransferContract.addressOn(spec.targetNetworkOrThrow()),
                                                                    tokenReceiverContract.addressOn(spec.targetNetworkOrThrow()),
                                                                    tokenReceiverAccount.addressOn(spec.targetNetworkOrThrow())},
                                                            new long[]{-5L, 2L, 3L})
                                                    .gas(1_000_000L)
                                                    .via(TXN_NAME),
                                            validateErc20Event(
                                                    new ReceiverAmount(() -> parsedToByteString(tokenReceiverContractId.get()), 2L),
                                                    new ReceiverAmount(() -> parsedToByteString(tokenReceiverAccountId.get()), 3L)
                                            )

                                            //TODO Glib: test Transfer using transferTokens function with multiple debit with allowance
//                                            tokenTransferContract
//                                                    .call(
//                                                            TRANSFER_TOKENS,
//                                                            fungibleToken,
//                                                            new Address[]{
//                                                                    tokenTransferContract.addressOn(spec.targetNetworkOrThrow()),
//                                                                    tokenReceiverContract.addressOn(spec.targetNetworkOrThrow()),
//                                                                    tokenReceiverAccount.addressOn(spec.targetNetworkOrThrow())},
//                                                            new long[]{-3L, -2L, 5L})
//                                                    .gas(1_000_000L)
//                                                    .via(TXN_NAME)
                                    )
                    ));
        }

        // TODO Glib: ERC20,ERC721
        @HapiTest
        @DisplayName("transferring owner's tokens using transferFrom function given allowance")
        public Stream<DynamicTest> transferUsingTransferFromWithAllowance() {
            return hapiTest(
                    // Approve the transfer contract to spend 2 tokens
                    tokenTransferContract
                            .call("approvePublic", fungibleToken, tokenTransferContract, BigInteger.valueOf(2L))
                            .gas(1_000_000L),
                    // Transfer using transferFrom function
                    tokenTransferContract
                            .call(
                                    "transferFromPublic",
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    BigInteger.valueOf(2L))
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateErc20Event(new ReceiverAmount(() -> parsedToByteString(tokenReceiverContractId.get()), 2L))
            );
        }

        // TODO Glib: add "transferFromNFT" with event check

        // TODO Glib: add proxy transfer(address dst, uint wad)
        // TODO Glib: add proxy transferFrom(address src, address dst, uint wad)
        // TODO Glib: add proxy transferFrom(address src, address dst, uint wad)
    }

    @Nested
    @DisplayName("fails when")
    @Order(2)
    class FailedTransferTokenTest {
        @HapiTest
        @DisplayName("transferring owner's tokens using transferFrom function without allowance")
        public Stream<DynamicTest> transferUsingTransferFromWithoutAllowance() {
            return hapiTest(
                    // Transfer using transferFrom function without allowance should fail
                    tokenTransferContract
                            .call(
                                    "transferFromPublic",
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    BigInteger.valueOf(2L))
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("transferring owner's tokens using transferToken function from receiver contract")
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
