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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.*;
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
import com.hedera.services.bdd.suites.contract.openzeppelin.ERC20ContractInteractions;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

    @FungibleToken(name = "fungibleToken", initialSupply = 20)
    static SpecFungibleToken fungibleToken;

    @NonFungibleToken(name = "nonFungibleToken", numPreMints = 4)
    static SpecNonFungibleToken nonFungibleToken;

    /**
     * The behavior of the transferToken function and transferFrom function differs for contracts that are token owners.
     * The tests below highlight the differences and shows that an allowance approval is required for the transferFrom function
     * in order to be consistent with the ERC20 standard.
     */
    @Nested
    @DisplayName("successful when")
    @Order(1)
    class SuccessfulTransferTokenTest {

        private static final String TXN_NAME = "transferTxn";
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
                    nonFungibleToken.treasury()
                            .getInfo()
                            .andAssert(e -> e.exposingIdTo(nonFungibleTokenTreasuryId::set))
            );
        }

        public record ReceiverAmount(Supplier<ByteString> from, Supplier<ByteString> to, Long amount) {
        }

        // ------------------------------ FT ------------------------------
        public static HapiGetTxnRecord validateFTLogEvent(final ReceiverAmount... receivers) {
            ContractLogAsserts[] logsChecker = new ContractLogAsserts[receivers.length + 1];
            for (int i = 0; i < receivers.length; i++) {
                ReceiverAmount receiver = receivers[i];
                logsChecker[i] = logWith() // first log event is ERC event
                        .contract(() -> String.valueOf(fungibleTokenId.get().getTokenNum()))
                        .withTopicsInOrder(() -> List.of(
                                eventSignatureOf(ERC20ContractInteractions.TRANSFER_EVENT_SIGNATURE),
                                receiver.from().get(),
                                receiver.to().get()))
                        .longValue(receiver.amount());
            }
            logsChecker[receivers.length] = logWith(); // last log event is event from 'tokenTransferContract'
            return getTxnRecord(TXN_NAME)
                    .logged()
                    .hasPriority(recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith().logs(inOrder(logsChecker))));
        }

        @HapiTest
        @DisplayName("'transferToken' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferToken() {
            return hapiTest(
                    // Transfer using transferToken function
                    tokenTransferContract
                            .call(TRANSFER_TOKEN, fungibleToken, tokenTransferContract, tokenReceiverContract, 2L)
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateFTLogEvent(
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverContractId.get()), 2L)));
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
                                    new Address[]{
                                            tokenTransferContract.addressOn(spec.targetNetworkOrThrow()),
                                            tokenReceiverContract.addressOn(spec.targetNetworkOrThrow()),
                                            tokenReceiverAccount.addressOn(spec.targetNetworkOrThrow())
                                    },
                                    new long[]{-5L, 2L, 3L})
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateFTLogEvent(
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverContractId.get()), 2L),
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverAccountId.get()), 3L)))));
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
                    validateFTLogEvent(
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverContractId.get()), 2L)));
        }

        // ------------------------------ NFT ------------------------------
        public static HapiGetTxnRecord validateNFTLogEvent(final ReceiverAmount... receivers) {
            ContractLogAsserts[] logsChecker = new ContractLogAsserts[receivers.length + 1];
            for (int i = 0; i < receivers.length; i++) {
                ReceiverAmount receiver = receivers[i];
                logsChecker[i] = logWith() // first log event is ERC event
                        .contract(() -> String.valueOf(nonFungibleTokenId.get().getTokenNum()))
                        .withTopicsInOrder(() -> List.of(
                                eventSignatureOf(ERC20ContractInteractions.TRANSFER_EVENT_SIGNATURE),
                                receiver.from().get(),
                                receiver.to().get(),
                                parsedToByteString(receiver.amount())));
            }
            logsChecker[receivers.length] = logWith(); // last log event is event from 'tokenTransferContract'
            return getTxnRecord(TXN_NAME)
                    .logged()
                    .hasPriority(recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith().logs(inOrder(logsChecker))));
        }

        @HapiTest
        @DisplayName("'transferNFT' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferNFT() {
            return hapiTest(
                    // Transfer using transferToken function
                    tokenTransferContract
                            .call(TRANSFER_NFT, nonFungibleToken, tokenTransferContract, tokenReceiverContract, 1L)
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateNFTLogEvent(
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverContractId.get()), 1L)));
        }

        @HapiTest
        @DisplayName("'transferNFTs' function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferNFTs() {
            return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                    spec,
                    // Transfer using transferTokens function
                    tokenTransferContract
                            .call(
                                    TRANSFER_NFTS,
                                    nonFungibleToken,
                                    new Address[]{
                                            tokenTransferContract.addressOn(spec.targetNetworkOrThrow()),
                                            tokenTransferContract.addressOn(spec.targetNetworkOrThrow())
                                    },
                                    new Address[]{
                                            tokenReceiverContract.addressOn(spec.targetNetworkOrThrow()),
                                            tokenReceiverAccount.addressOn(spec.targetNetworkOrThrow())
                                    },
                                    new long[]{2L, 3L})
                            .gas(1_000_000L)
                            .via(TXN_NAME),
                    validateNFTLogEvent(
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverContractId.get()), 2L),
                            new ReceiverAmount(() -> parsedToByteString(tokenTransferContractId.get()), () -> parsedToByteString(tokenReceiverAccountId.get()), 3L)))));
        }

        @HapiTest
        @DisplayName("'transferFromNFT' function given allowance")
        public Stream<DynamicTest> transferUsingTransferFromNFTWithAllowance() {
            return hapiTest(
                    // Approve the 'TokenTransferContract' to spend 4th NFT token
                    // We cant approve from 'TokenTransferContract' because it is not an owner of the token. "DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL"
                    // Event if 'TokenTransferContract' is an owner of the token, there is "SPENDER_ACCOUNT_SAME_AS_OWNER"

                    // TODO Glib: Why we are not allowing self approve for NFT?
//                    cryptoApproveAllowance()
//                            .payingWith(DEFAULT_PAYER)
//                            .addNftAllowance("TokenTransferContract", "nonFungibleToken", "TokenTransferContract", false, List.of(4L))
//                            .signedBy(DEFAULT_PAYER, "TokenTransferContract")
//                            .fee(ONE_HBAR),
//                    tokenTransferContract
//                            .call("approveNFTPublic", nonFungibleToken, tokenTransferContract, BigInteger.valueOf(4L))
//                            .gas(1_000_000L),

                    cryptoApproveAllowance()
                            .payingWith(DEFAULT_PAYER)
                            .addNftAllowance("nonFungibleToken" + SpecFungibleToken.DEFAULT_TREASURY_NAME_SUFFIX, "nonFungibleToken", "TokenTransferContract", false, List.of(4L))
                            .signedBy(DEFAULT_PAYER, "nonFungibleToken" + SpecFungibleToken.DEFAULT_TREASURY_NAME_SUFFIX)
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
                    validateNFTLogEvent(
                            new ReceiverAmount(() -> parsedToByteString(nonFungibleTokenTreasuryId.get()), () -> parsedToByteString(tokenReceiverContractId.get()), 4L)));
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
