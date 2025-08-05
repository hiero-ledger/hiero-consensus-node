// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.RECEIVER_2;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_TOKEN_PUBLIC;
import static com.hedera.services.bdd.suites.contract.precompile.ERCPrecompileSuite.TRANSFER_SIGNATURE;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.KNOWABLE_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.INFINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenPauseStatus;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchPrecompileTest {
    private static final String DEFAULT_BATCH_OPERATOR = "batchOperator";
    private static final String APPROVE_SIGNATURE = "Approval(address,address,uint256)";
    private static final Tuple[] EMPTY_TUPLE_ARRAY = new Tuple[] {};
    private static final long GAS_TO_OFFER = 5_000_000L;
    private static final long GAS_FOR_AUTO_ASSOCIATING_CALLS = 2_000_000L;
    private static final String TEST_METADATA_1 = "Test metadata 1";
    private static final long AUTO_RENEW_PERIOD = 8_000_000L;
    private static final long DEFAULT_AMOUNT_TO_SEND = 20 * ONE_HBAR;
    public static final String TOKEN_SYMBOL = "tokenSymbol";
    public static final String TOKEN_NAME = "tokenName";
    public static final String MEMO = "memo";
    private static final String INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";

    // contracts and function names
    private static final String DIRECT_ERC_CALLEE = "NonDelegateCallee";
    private static final String HTS_APPROVE_ALLOWANCE_CONTRACT = "HtsApproveAllowance";
    private static final String THE_GRACEFULLY_FAILING_CONTRACT = "GracefullyFailing";
    private static final String ATOMIC_CRYPTO_TRANSFER_CONTRACT = "AtomicCryptoTransfer";
    private static final String TRANSFER_MULTIPLE_TOKENS = "transferMultipleTokens";
    private static final String TOKEN_TRANSFER_CONTRACT = "TokenTransferContract";
    private static final String MULTIVERSION_BURN_CONTRACT = "MultiversionBurn";
    private static final String BURN_TOKEN_V_1 = "burnTokenV1";
    private static final String BURN_TOKEN_V_2 = "burnTokenV2";
    private static final String BURN_TOKEN = "BurnToken";
    private static final String BURN_TOKEN_METHOD = "burnToken";
    private static final String NEGATIVE_MINT_CONTRACT = "NegativeMintContract";
    private static final String TOKEN_CREATE_CONTRACT = "TokenCreateContract";
    private static final String CREATE_FUNGIBLE_TOKEN_WITH_KEYS_AND_EXPIRY_FUNCTION = "createTokenWithKeysAndExpiry";
    private static final String HTS_TRANSFER_FROM_CONTRACT = "HtsTransferFrom";
    private static final String HTS_TRANSFER_FROM = "htsTransferFrom";
    private static final String TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT = "TokenDefaultKycAndFreezeStatus";
    private static final String GET_TOKEN_DEFAULT_FREEZE = "getTokenDefaultFreeze";
    private static final String OUTER_DELEGATE_CONTRACT = "DelegateContract";
    private static final String NESTED_SERVICE_CONTRACT = "ServiceContract";
    private static final String DELETE_TOKEN_CONTRACT = "DeleteTokenContract";
    private static final String TOKEN_DELETE_FUNCTION = "tokenDelete";
    private static final String NEGATIVE_DISSOCIATIONS_CONTRACT = "NegativeDissociationsContract";
    private static final String ERC_20_CONTRACT = "ERC20Contract";
    private static final String FREEZE_CONTRACT = "FreezeUnfreezeContract";
    private static final String TOKEN_FREEZE_FUNC = "tokenFreeze";
    private static final String TOKEN_UNFREEZE_FUNC = "tokenUnfreeze";
    private static final String GRANT_REVOKE_KYC_CONTRACT = "GrantRevokeKyc";
    public static final String TOKEN_GRANT_KYC = "tokenGrantKyc";
    public static final String TOKEN_REVOKE_KYC = "tokenRevokeKyc";

    // keys
    private static final String MULTI_KEY = "multiKey";
    private static final String DELEGATE_KEY = "delegateKey";
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE =
            KeyShape.threshOf(1, KeyShape.SIMPLE, DELEGATE_CONTRACT);
    private static final String ECDSA_KEY = "ecdsaKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final KeyShape CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final String FREEZE_KEY = "freezeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String NON_KYC_KEY = "nonKycKey";

    // accounts
    private static final String OWNER = "owner";
    private static final String TOKEN_TREASURY = "tokenTreasury";
    private static final String SPENDER = "spender";
    private static final String SENDER = "sender";
    private static final String SENDER2 = "sender2";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String RECIPIENT = "recipient";
    private static final String ACCOUNT = "account";
    private static final String ACCOUNT_2 = "account2";
    private static final String ACCOUNT_TO_ASSOCIATE = "accountToAssociate";

    // tokens
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        // upload contracts init code
        testLifecycle.doAdhoc(uploadInitCode(
                HTS_APPROVE_ALLOWANCE_CONTRACT,
                DIRECT_ERC_CALLEE,
                THE_GRACEFULLY_FAILING_CONTRACT,
                ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                MULTIVERSION_BURN_CONTRACT,
                BURN_TOKEN,
                NEGATIVE_MINT_CONTRACT,
                TOKEN_CREATE_CONTRACT,
                TOKEN_TRANSFER_CONTRACT,
                TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                OUTER_DELEGATE_CONTRACT,
                NESTED_SERVICE_CONTRACT,
                DELETE_TOKEN_CONTRACT,
                NEGATIVE_DISSOCIATIONS_CONTRACT,
                ERC_20_CONTRACT,
                FREEZE_CONTRACT,
                GRANT_REVOKE_KYC_CONTRACT));
    }

    /**
     * ApproveAllowanceSuite
     */
    @Nested
    class ApproveAllowanceSuite {

        @HapiTest
        final Stream<DynamicTest> atomicHtsTokenApproveToInnerContract() {
            final var approveTxn = "NestedChildren";
            final var nestedContract = DIRECT_ERC_CALLEE;
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();
            return hapiTest(flattened(
                    setupApproveAllowance(tokenAddress, null, null),
                    contractCreate(HTS_APPROVE_ALLOWANCE_CONTRACT).refusingEthConversion(),
                    contractCreate(nestedContract).adminKey(MULTI_KEY).refusingEthConversion(),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            atomicBatchDefaultOperator(
                                    tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                                    tokenAssociate(HTS_APPROVE_ALLOWANCE_CONTRACT, FUNGIBLE_TOKEN),
                                    tokenAssociate(nestedContract, FUNGIBLE_TOKEN),
                                    contractCall(
                                                    HTS_APPROVE_ALLOWANCE_CONTRACT,
                                                    "htsApprove",
                                                    tokenAddress.get(),
                                                    asHeadlongAddress(asAddress(
                                                            spec.registry().getContractId(nestedContract))),
                                                    BigInteger.valueOf(10))
                                            .payingWith(OWNER)
                                            .gas(4_000_000L)
                                            .via(approveTxn)))),
                    childRecordsCheck(approveTxn, SUCCESS, recordWith().status(SUCCESS)),
                    withOpContext((spec, opLog) -> {
                        final var senderId = spec.registry().getContractId(HTS_APPROVE_ALLOWANCE_CONTRACT);
                        final var senderByteStr = parsedToByteString(
                                senderId.getShardNum(), senderId.getRealmNum(), senderId.getContractNum());
                        final var receiverId = spec.registry().getContractId(nestedContract);
                        final var receiverByteStr = parsedToByteString(
                                receiverId.getShardNum(), receiverId.getRealmNum(), receiverId.getContractNum());
                        final var idOfToken = String.valueOf(
                                spec.registry().getTokenID(FUNGIBLE_TOKEN).getTokenNum());
                        // validate the logs
                        final var txnRecord = getTxnRecord(approveTxn)
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder(logWith()
                                                        .contract(idOfToken)
                                                        .withTopicsInOrder(List.of(
                                                                eventSignatureOf(APPROVE_SIGNATURE),
                                                                senderByteStr,
                                                                receiverByteStr))
                                                        .longValue(10)))))
                                .andAllChildRecords()
                                .logged();
                        allRunFor(spec, txnRecord);
                    })));
        }

        @HapiTest
        final Stream<DynamicTest> atomicHtsTokenAllowanceWithFailingFollowingOp() {
            final var theSpender = SPENDER;
            final var allowanceTxn = "allowanceTxn";
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();
            final AtomicReference<Address> ownerAddress = new AtomicReference<>();
            final AtomicReference<Address> spenderAddress = new AtomicReference<>();
            return hapiTest(flattened(
                    setupApproveAllowance(tokenAddress, ownerAddress, spenderAddress),
                    contractCreate(HTS_APPROVE_ALLOWANCE_CONTRACT),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            atomicBatchDefaultOperator(
                                            tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                                            cryptoTransfer(
                                                    moving(10, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                                            cryptoApproveAllowance()
                                                    .payingWith(DEFAULT_PAYER)
                                                    .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, theSpender, 2L)
                                                    .via("baseApproveTxn")
                                                    .signedBy(DEFAULT_PAYER, OWNER)
                                                    .fee(ONE_HBAR),
                                            contractCall(
                                                            HTS_APPROVE_ALLOWANCE_CONTRACT,
                                                            "htsAllowance",
                                                            tokenAddress.get(),
                                                            ownerAddress.get(),
                                                            spenderAddress.get())
                                                    .payingWith(OWNER)
                                                    .via(allowanceTxn),
                                            // Failing operation
                                            cryptoTransfer(movingHbar(10000 * ONE_HUNDRED_HBARS)
                                                            .between(OWNER, theSpender))
                                                    .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                    childRecordsCheck(
                            allowanceTxn, REVERTED_SUCCESS, recordWith().status(REVERTED_SUCCESS))));
        }

        private static SpecOperation[] setupApproveAllowance(
                @NonNull AtomicReference<Address> tokenAddress,
                @Nullable AtomicReference<Address> ownerAddress,
                @Nullable AtomicReference<Address> spenderAddress) {
            if (ownerAddress == null) {
                ownerAddress = new AtomicReference<>();
            }
            if (spenderAddress == null) {
                spenderAddress = new AtomicReference<>();
            }
            return List.of(
                            newKeyNamed(MULTI_KEY),
                            cryptoCreate(OWNER)
                                    .balance(100 * ONE_HUNDRED_HBARS)
                                    .exposingEvmAddressTo(ownerAddress::set),
                            cryptoCreate(SPENDER).exposingEvmAddressTo(spenderAddress::set),
                            cryptoCreate(TOKEN_TREASURY),
                            tokenCreate(FUNGIBLE_TOKEN)
                                    .tokenType(FUNGIBLE_COMMON)
                                    .supplyType(FINITE)
                                    .initialSupply(10L)
                                    .maxSupply(1000L)
                                    .treasury(TOKEN_TREASURY)
                                    .adminKey(MULTI_KEY)
                                    .supplyKey(MULTI_KEY)
                                    .exposingAddressTo(tokenAddress::set))
                    .toArray(SpecOperation[]::new);
        }
    }

    /**
     * AssociatePrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicFunctionCallWithLessThanFourBytesFailsWithinSingleContractCall() {
        final var ACCOUNT_ADDRESS =
                asHeadlongAddress(asAddress(AccountID.newBuilder().build()));
        final var TOKEN_ADDRESS =
                asHeadlongAddress(asAddress(TokenID.newBuilder().build()));
        return hapiTest(
                contractCreate(THE_GRACEFULLY_FAILING_CONTRACT),
                atomicBatchDefaultOperator(contractCall(
                                THE_GRACEFULLY_FAILING_CONTRACT,
                                "performLessThanFourBytesFunctionCall",
                                ACCOUNT_ADDRESS,
                                TOKEN_ADDRESS)
                        .notTryingAsHexedliteral()
                        .via("Function call with less than 4 bytes txn")
                        .gas(100_000)),
                childRecordsCheck("Function call with less than 4 bytes txn", SUCCESS));
    }

    /**
     * AtomicCryptoTransferHTSSuite
     */
    @Nested
    class AtomicCryptoTransferHtsSuite {

        private List<SpecOperation> deployContractAndUpdateKeys() {
            return List.of(
                    contractCreate(ATOMIC_CRYPTO_TRANSFER_CONTRACT),
                    newKeyNamed(DELEGATE_KEY)
                            .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ATOMIC_CRYPTO_TRANSFER_CONTRACT))));
        }

        @HapiTest
        final Stream<DynamicTest> atomicCryptoTransferTxn() {
            final var cryptoTransferTxn = "cryptoTransferTxn";
            final AtomicReference<AccountID> senderId = new AtomicReference<>();
            final AtomicReference<AccountID> receiverId = new AtomicReference<>();
            return hapiTest(flattened(
                    cryptoCreate(SENDER).balance(ONE_HBAR).exposingCreatedIdTo(senderId::set),
                    cryptoCreate(RECEIVER)
                            .balance(ONE_HBAR)
                            .receiverSigRequired(true)
                            .exposingCreatedIdTo(receiverId::set),
                    deployContractAndUpdateKeys(),
                    // Simple transfer between sender and receiver for ONE_HBAR should succeed
                    sourcing(() -> atomicBatchDefaultOperator(
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(
                                            ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(senderId.get(), -ONE_HBAR, false),
                                                            accountAmount(receiverId.get(), ONE_HBAR, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .via(cryptoTransferTxn)
                                    .gas(GAS_TO_OFFER))),
                    // validate balances
                    getAccountBalance(SENDER).hasTinyBars(0),
                    getAccountBalance(RECEIVER).hasTinyBars(2 * ONE_HBAR),
                    validatedHtsPrecompileResult(cryptoTransferTxn, SUCCESS, SUCCESS)));
        }

        @HapiTest
        final Stream<DynamicTest> atomicCryptoTransferMultiTxn() {
            final var cryptoTransferMultiTxn = "cryptoTransferMultiTxn";
            final AtomicReference<AccountID> senderId = new AtomicReference<>();
            final AtomicReference<AccountID> receiverId = new AtomicReference<>();
            final AtomicReference<AccountID> receiver2Id = new AtomicReference<>();
            final var amountToBeSent = 50 * ONE_HBAR;
            return hapiTest(flattened(
                    // set up accounts and deploy contract
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS).exposingCreatedIdTo(senderId::set),
                    cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true).exposingCreatedIdTo(receiverId::set),
                    cryptoCreate(RECEIVER2)
                            .balance(0L)
                            .receiverSigRequired(true)
                            .exposingCreatedIdTo(receiver2Id::set),
                    deployContractAndUpdateKeys(),
                    newKeyNamed(DELEGATE_KEY)
                            .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ATOMIC_CRYPTO_TRANSFER_CONTRACT))),
                    // submit batch
                    sourcing(() ->
                            // Simple transfer between sender, receiver and
                            // receiver2 for 50 * ONE_HBAR
                            // sender sends 50, receiver get 10 and receiver2 gets
                            // 40
                            // should succeed
                            atomicBatchDefaultOperator(
                                    cryptoUpdate(SENDER).key(DELEGATE_KEY),
                                    cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                                    cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                                    contractCall(
                                                    ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                                                    TRANSFER_MULTIPLE_TOKENS,
                                                    transferList()
                                                            .withAccountAmounts(
                                                                    accountAmount(
                                                                            senderId.get(), -amountToBeSent, false),
                                                                    accountAmount(
                                                                            receiverId.get(), 10 * ONE_HBAR, false),
                                                                    accountAmount(
                                                                            receiver2Id.get(), 40 * ONE_HBAR, false))
                                                            .build(),
                                                    EMPTY_TUPLE_ARRAY)
                                            .via(cryptoTransferMultiTxn)
                                            .gas(GAS_TO_OFFER))),
                    getAccountBalance(SENDER).hasTinyBars(50 * ONE_HBAR),
                    getAccountBalance(RECEIVER).hasTinyBars(10 * ONE_HBAR),
                    getAccountBalance(RECEIVER2).hasTinyBars(40 * ONE_HBAR),
                    validatedHtsPrecompileResult(cryptoTransferMultiTxn, SUCCESS, SUCCESS)));
        }

        @HapiTest
        final Stream<DynamicTest> atomicCryptoTransferRevertTxn() {
            final var cryptoTransferRevertTxn = "cryptoTransferRevertTxn";
            final AtomicReference<AccountID> senderId = new AtomicReference<>();
            final AtomicReference<AccountID> receiverId = new AtomicReference<>();
            final AtomicReference<AccountID> receiver2Id = new AtomicReference<>();

            final var amountToBeSent = 50 * ONE_HBAR;

            return hapiTest(flattened(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS).exposingCreatedIdTo(senderId::set),
                    cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true).exposingCreatedIdTo(receiverId::set),
                    cryptoCreate(RECEIVER2)
                            .balance(0L)
                            .receiverSigRequired(true)
                            .exposingCreatedIdTo(receiver2Id::set),
                    deployContractAndUpdateKeys(),
                    sourcing(() ->
                            // Simple transfer between sender, receiver and
                            // receiver2 for 50 * ONE_HBAR
                            // sender sends 50, receiver get 5 and receiver2 gets 40
                            // should fail because total does not add to 0
                            atomicBatchDefaultOperator(
                                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                                            contractCall(
                                                            ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                                                            TRANSFER_MULTIPLE_TOKENS,
                                                            transferList()
                                                                    .withAccountAmounts(
                                                                            accountAmount(
                                                                                    senderId.get(),
                                                                                    -amountToBeSent,
                                                                                    false),
                                                                            accountAmount(
                                                                                    receiverId.get(),
                                                                                    amountToBeSent - (5 * ONE_HBAR),
                                                                                    false),
                                                                            accountAmount(
                                                                                    receiver2Id.get(),
                                                                                    amountToBeSent - (40 * ONE_HBAR),
                                                                                    false))
                                                                    .build(),
                                                            EMPTY_TUPLE_ARRAY)
                                                    .via(cryptoTransferRevertTxn)
                                                    .gas(GAS_TO_OFFER)
                                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                    getAccountBalance(SENDER).hasTinyBars(ONE_HUNDRED_HBARS),
                    getAccountBalance(RECEIVER).hasTinyBars(0L),
                    getAccountBalance(RECEIVER2).hasTinyBars(0L),
                    validatedHtsPrecompileResult(
                            cryptoTransferRevertTxn, CONTRACT_REVERT_EXECUTED, INVALID_ACCOUNT_AMOUNTS)));
        }

        @HapiTest
        final Stream<DynamicTest> atomicCryptoTransferRevertNoKeyTxn() {
            final var cryptoTransferRevertNoKeyTxn = "cryptoTransferRevertNoKeyTxn";
            final AtomicReference<AccountID> sender2Id = new AtomicReference<>();
            final AtomicReference<AccountID> receiverId = new AtomicReference<>();
            final var amountToBeSent = 50 * ONE_HBAR;
            return hapiTest(flattened(
                    cryptoCreate(SENDER2).balance(10 * ONE_HUNDRED_HBARS).exposingCreatedIdTo(sender2Id::set),
                    cryptoCreate(RECEIVER)
                            .balance(2 * ONE_HUNDRED_HBARS)
                            .receiverSigRequired(true)
                            .exposingCreatedIdTo(receiverId::set),
                    deployContractAndUpdateKeys(),
                    newKeyNamed(DELEGATE_KEY)
                            .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ATOMIC_CRYPTO_TRANSFER_CONTRACT))),
                    sourcing(() ->
                            // Simple transfer between sender2 and receiver for 50 *
                            // ONE_HBAR
                            // should fail because sender2 does not have the right
                            // key
                            atomicBatchDefaultOperator(
                                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                                            contractCall(
                                                            ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                                                            TRANSFER_MULTIPLE_TOKENS,
                                                            transferList()
                                                                    .withAccountAmounts(
                                                                            accountAmount(
                                                                                    sender2Id.get(),
                                                                                    -amountToBeSent,
                                                                                    false),
                                                                            accountAmount(
                                                                                    receiverId.get(),
                                                                                    amountToBeSent,
                                                                                    false))
                                                                    .build(),
                                                            EMPTY_TUPLE_ARRAY)
                                                    .via(cryptoTransferRevertNoKeyTxn)
                                                    .gas(GAS_TO_OFFER)
                                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                    validatedHtsPrecompileResult(
                            cryptoTransferRevertNoKeyTxn, CONTRACT_REVERT_EXECUTED, SPENDER_DOES_NOT_HAVE_ALLOWANCE)));
        }

        @HapiTest
        final Stream<DynamicTest> atomicCryptoTransferRevertBalanceTooLowTxn() {
            final var cryptoTransferRevertBalanceTooLowTxn = "cryptoTransferRevertBalanceTooLowTxn";
            final AtomicReference<AccountID> senderId = new AtomicReference<>();
            final AtomicReference<AccountID> receiverId = new AtomicReference<>();

            return hapiTest(flattened(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS).exposingCreatedIdTo(senderId::set),
                    cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true).exposingCreatedIdTo(receiverId::set),
                    deployContractAndUpdateKeys(),
                    cryptoUpdate(SENDER).key(DELEGATE_KEY),
                    cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                    sourcing(() ->
                            // Simple transfer between sender2 and receiver for 1000
                            // * ONE_HUNDRED_HBAR
                            // should fail because sender does not have enough hbars
                            atomicBatchDefaultOperator(contractCall(
                                                    ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                                                    TRANSFER_MULTIPLE_TOKENS,
                                                    transferList()
                                                            .withAccountAmounts(
                                                                    accountAmount(
                                                                            senderId.get(),
                                                                            -1000 * ONE_HUNDRED_HBARS,
                                                                            false),
                                                                    accountAmount(
                                                                            receiverId.get(),
                                                                            1000 * ONE_HUNDRED_HBARS,
                                                                            false))
                                                            .build(),
                                                    EMPTY_TUPLE_ARRAY)
                                            .via(cryptoTransferRevertBalanceTooLowTxn)
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                    getAccountBalance(SENDER).hasTinyBars(ONE_HUNDRED_HBARS),
                    getAccountBalance(RECEIVER).hasTinyBars(0L),
                    validatedHtsPrecompileResult(
                            cryptoTransferRevertBalanceTooLowTxn,
                            CONTRACT_REVERT_EXECUTED,
                            INSUFFICIENT_ACCOUNT_BALANCE)));
        }
    }

    /**
     * ContractBurnHTSSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicBurnFungibleV1andV2WithZeroAndNegativeValues() {
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                contractCreate(MULTIVERSION_BURN_CONTRACT).gas(GAS_TO_OFFER),
                // Burning 0 amount for Fungible tokens should fail
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MULTIVERSION_BURN_CONTRACT,
                                        BURN_TOKEN_V_1,
                                        tokenAddress.get(),
                                        BigInteger.ZERO,
                                        new long[0])
                                .alsoSigningWithFullPrefix(MULTI_KEY)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MULTIVERSION_BURN_CONTRACT, BURN_TOKEN_V_2, tokenAddress.get(), 0L, new long[0])
                                .alsoSigningWithFullPrefix(MULTI_KEY)
                                .gas(GAS_TO_OFFER)
                                .logged()
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)),
                // Burning negative amount for Fungible tokens should fail
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MULTIVERSION_BURN_CONTRACT,
                                        BURN_TOKEN_V_1,
                                        tokenAddress.get(),
                                        new BigInteger("FFFFFFFFFFFFFF00", 16),
                                        new long[0])
                                .alsoSigningWithFullPrefix(MULTI_KEY)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MULTIVERSION_BURN_CONTRACT,
                                        BURN_TOKEN_V_2,
                                        tokenAddress.get(),
                                        -1L,
                                        new long[0])
                                .alsoSigningWithFullPrefix(MULTI_KEY)
                                .gas(GAS_TO_OFFER)
                                .logged()
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 50));
    }

    /**
     * ContractHTSSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicTransferDontWorkWithoutTopLevelSignatures() {
        final var transferTokenTxn = "transferTokenTxn";
        final var transferTokensTxn = "transferTokensTxn";
        final var transferNFTTxn = "transferNFTTxn";
        final var transferNFTsTxn = "transferNFTsTxn";
        final var contract = TOKEN_TRANSFER_CONTRACT;

        final AtomicReference<Address> ownerAddress = new AtomicReference<>();
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> receiver1Address = new AtomicReference<>();
        final AtomicReference<Address> receiver2Address = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).exposingEvmAddressTo(ownerAddress::set),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(RECEIVER).exposingEvmAddressTo(receiver1Address::set),
                cryptoCreate(RECEIVER_2).exposingEvmAddressTo(receiver2Address::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(1_000)
                        .exposingAddressTo(tokenAddress::set),
                tokenCreate(KNOWABLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .exposingAddressTo(nftAddress::set),
                tokenAssociate(OWNER, VANILLA_TOKEN, KNOWABLE_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN, KNOWABLE_TOKEN),
                tokenAssociate(RECEIVER_2, VANILLA_TOKEN, KNOWABLE_TOKEN),
                mintToken(
                        KNOWABLE_TOKEN,
                        List.of(
                                copyFromUtf8("dark"),
                                copyFromUtf8("matter"),
                                copyFromUtf8("dark1"),
                                copyFromUtf8("matter1"))),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, OWNER)),
                cryptoTransfer(movingUnique(KNOWABLE_TOKEN, 1, 2, 3, 4).between(TOKEN_TREASURY, OWNER)),
                contractCreate(contract).gas(GAS_TO_OFFER),
                // Do transfers by calling contract from EOA, and should be failing with
                // CONTRACT_REVERT_EXECUTED
                withOpContext((spec, opLog) -> {
                    final var accounts =
                            new Address[] {ownerAddress.get(), receiver1Address.get(), receiver2Address.get()};
                    final var amount = 5L;
                    final var amounts = new long[] {-10L, 5L, 5L};
                    final var serials = new long[] {2L, 3L};
                    final var serial = 1L;
                    allRunFor(
                            spec,
                            atomicBatchDefaultOperator(contractCall(
                                                    contract,
                                                    TRANSFER_TOKEN_PUBLIC,
                                                    tokenAddress.get(),
                                                    ownerAddress.get(),
                                                    receiver1Address.get(),
                                                    amount)
                                            .payingWith(OWNER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(GAS_TO_OFFER)
                                            .via(transferTokenTxn))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    contract,
                                                    "transferTokensPublic",
                                                    tokenAddress.get(),
                                                    accounts,
                                                    amounts)
                                            .payingWith(OWNER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(GAS_TO_OFFER)
                                            .via(transferTokensTxn))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    contract,
                                                    "transferNFTPublic",
                                                    nftAddress.get(),
                                                    ownerAddress.get(),
                                                    receiver1Address.get(),
                                                    serial)
                                            .payingWith(OWNER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(GAS_TO_OFFER)
                                            .via(transferNFTTxn))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    contract,
                                                    "transferNFTsPublic",
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(KNOWABLE_TOKEN))),
                                                    new Address[] {ownerAddress.get(), ownerAddress.get()},
                                                    new Address[] {receiver2Address.get(), receiver2Address.get()},
                                                    serials)
                                            .payingWith(OWNER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(GAS_TO_OFFER)
                                            .via(transferNFTsTxn))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
                }),
                // Confirm the transactions fails with no top level signatures enabled
                childRecordsCheck(
                        transferTokenTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferTokensTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferNFTTxn, CONTRACT_REVERT_EXECUTED, recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferNFTsTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                // Confirm the balances are correct
                getAccountInfo(RECEIVER).hasOwnedNfts(0),
                getAccountBalance(RECEIVER).hasTokenBalance(VANILLA_TOKEN, 0),
                getAccountInfo(RECEIVER_2).hasOwnedNfts(0),
                getAccountBalance(RECEIVER_2).hasTokenBalance(VANILLA_TOKEN, 0),
                getAccountInfo(OWNER).hasOwnedNfts(4),
                getAccountBalance(OWNER).hasTokenBalance(VANILLA_TOKEN, 500L));
    }

    /**
     * ContractKeysHTSSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicBurnWithKeyAsPartOf1OfXThreshold() {
        final var delegateContractKeyShape = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var contractKeyShape = KeyShape.threshOf(1, SIMPLE, KeyShape.CONTRACT);
        final var contractKey = "contract key";
        final var burnWithContractKeyTxn = "burn with contract key";
        final var creationTx = "creation tx";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        BURN_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                .via(creationTx))),
                newKeyNamed(DELEGATE_KEY).shape(delegateContractKeyShape.signedWith(sigs(ON, BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                                .via("burn with delegate contract key")
                                .gas(GAS_TO_OFFER)),
                childRecordsCheck(
                        "burn with delegate contract key",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(
                                        changingFungibleBalances().including(FUNGIBLE_TOKEN, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 49),
                newKeyNamed(contractKey).shape(contractKeyShape.signedWith(sigs(ON, BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(contractKey).signedByPayerAnd(MULTI_KEY),
                        contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                                .via(burnWithContractKeyTxn)
                                .gas(GAS_TO_OFFER)),
                childRecordsCheck(
                        burnWithContractKeyTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(48)))
                                .tokenTransfers(
                                        changingFungibleBalances().including(FUNGIBLE_TOKEN, TOKEN_TREASURY, -1))));
    }

    /**
     * ContractMintHTSSuite
     */
    @Nested
    class ContractMintHtsSuite {

        @HapiTest
        final Stream<DynamicTest> atomicMintTokensWithExtremeValues() {
            final var mintExtremeValue = "mintExtremeValue";
            final var mintInvalidAddressType = "mintInvalidAddressType";
            final var invalidTokenTest = "invalidTokenTest";
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(MULTI_KEY),
                    cryptoCreate(RECIPIENT).maxAutomaticTokenAssociations(1),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .supplyType(INFINITE)
                            .initialSupply(1000)
                            .treasury(TOKEN_TREASURY)
                            .adminKey(MULTI_KEY)
                            .supplyKey(MULTI_KEY)
                            .exposingAddressTo(tokenAddress::set),
                    contractCreate(NEGATIVE_MINT_CONTRACT).gas(GAS_TO_OFFER),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            // Fungible Mint calls with extreme values
                            atomicBatchDefaultOperator(contractCall(
                                                    NEGATIVE_MINT_CONTRACT,
                                                    mintExtremeValue,
                                                    new byte[][] {},
                                                    false,
                                                    tokenAddress.get())
                                            .via("mintExtremeValue")
                                            .alsoSigningWithFullPrefix(MULTI_KEY)
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            emptyChildRecordsCheck("mintExtremeValue", CONTRACT_REVERT_EXECUTED),
                            atomicBatchDefaultOperator(contractCall(
                                                    NEGATIVE_MINT_CONTRACT,
                                                    mintExtremeValue,
                                                    new byte[][] {},
                                                    true,
                                                    tokenAddress.get())
                                            .via("mintNegativeExtremeValue")
                                            .alsoSigningWithFullPrefix(MULTI_KEY)
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            emptyChildRecordsCheck("mintNegativeExtremeValue", CONTRACT_REVERT_EXECUTED),
                            atomicBatchDefaultOperator(
                                    contractCall(NEGATIVE_MINT_CONTRACT, mintInvalidAddressType, new byte[][] {}, 100L)
                                            .via(invalidTokenTest)
                                            .alsoSigningWithFullPrefix(MULTI_KEY)
                                            .gas(GAS_TO_OFFER)))),
                    getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 1_000),
                    childRecordsCheck(invalidTokenTest, SUCCESS, recordWith().status(INVALID_TOKEN_ID)));
        }

        @HapiTest
        final Stream<DynamicTest> atomicMintNftWithExtremeValues() {
            var mintExtremeValue = "mintExtremeValue";
            var mintInvalidAddressType = "mintInvalidAddressType";

            var invalidTokenNFTTest = "invalidTokenNFTTest";
            final AtomicReference<Address> nftAddress = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(MULTI_KEY),
                    cryptoCreate(RECIPIENT).maxAutomaticTokenAssociations(1),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate(NON_FUNGIBLE_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .supplyType(INFINITE)
                            .initialSupply(0)
                            .treasury(TOKEN_TREASURY)
                            .adminKey(MULTI_KEY)
                            .supplyKey(MULTI_KEY)
                            .exposingAddressTo(nftAddress::set),
                    contractCreate(NEGATIVE_MINT_CONTRACT).gas(GAS_TO_OFFER),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            // NFT Mint calls with extreme values
                            atomicBatchDefaultOperator(contractCall(
                                                    NEGATIVE_MINT_CONTRACT,
                                                    mintExtremeValue,
                                                    new byte[][] {TEST_METADATA_1.getBytes()},
                                                    false,
                                                    nftAddress.get())
                                            .via("mintExtremeValueNFT")
                                            .alsoSigningWithFullPrefix(MULTI_KEY)
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            emptyChildRecordsCheck("mintExtremeValueNFT", CONTRACT_REVERT_EXECUTED),
                            atomicBatchDefaultOperator(contractCall(
                                                    NEGATIVE_MINT_CONTRACT,
                                                    mintExtremeValue,
                                                    new byte[][] {TEST_METADATA_1.getBytes()},
                                                    true,
                                                    nftAddress.get())
                                            .via("mintNegativeExtremeValueNFT")
                                            .alsoSigningWithFullPrefix(MULTI_KEY)
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            emptyChildRecordsCheck("mintNegativeExtremeValueNFT", CONTRACT_REVERT_EXECUTED),
                            atomicBatchDefaultOperator(contractCall(
                                            NEGATIVE_MINT_CONTRACT,
                                            mintInvalidAddressType,
                                            new byte[][] {TEST_METADATA_1.getBytes()},
                                            0L)
                                    .via(invalidTokenNFTTest)
                                    .alsoSigningWithFullPrefix(MULTI_KEY)
                                    .gas(GAS_TO_OFFER)),
                            getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0),
                            childRecordsCheck(
                                    invalidTokenNFTTest, SUCCESS, recordWith().status(INVALID_TOKEN_ID)))));
        }
    }

    /**
     * CreatePrecompileSuite
     */
    @Nested
    class CreatePrecompileSuite {
        @HapiTest
        final Stream<DynamicTest> atomicFungibleTokenCreateHappyPath() {
            final var tokenCreateContractAsKeyDelegate = "tokenCreateContractAsKeyDelegate";
            final String tokenCreateContractAsKey = "tokenCreateContractAsKey";
            final var createTokenNum = new AtomicLong();
            final AtomicReference<byte[]> ed2551Key = new AtomicReference<>();
            final var contractKey = "thresholdKey";
            final String ed25519Key = "ed25519key";
            final AtomicReference<Address> tokenCreateContractAddress = new AtomicReference<>();
            final AtomicReference<Address> accountToAssociateAddress = new AtomicReference<>();
            final AtomicReference<Address> accountAddress = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP256K1),
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(ACCOUNT_TO_ASSOCIATE).exposingEvmAddressTo(accountToAssociateAddress::set),
                    cryptoCreate(ACCOUNT)
                            .exposingEvmAddressTo(accountAddress::set)
                            .balance(ONE_MILLION_HBARS),
                    contractCreate(TOKEN_CREATE_CONTRACT)
                            .autoRenewAccountId(ACCOUNT)
                            .adminKey(ADMIN_KEY)
                            .gas(GAS_TO_OFFER)
                            .exposingAddressTo(tokenCreateContractAddress::set),
                    newKeyNamed(contractKey)
                            .shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ED25519_ON, TOKEN_CREATE_CONTRACT)))
                            .exposingKeyTo(k -> ed2551Key.set(k.getThresholdKey()
                                    .getKeys()
                                    .getKeys(0)
                                    .getEd25519()
                                    .toByteArray())),
                    cryptoUpdate(ACCOUNT).key(contractKey),
                    cryptoUpdate(ACCOUNT_TO_ASSOCIATE).key(contractKey),
                    withOpContext((spec, opLog) -> {
                        spec.registry()
                                .saveKey(
                                        ed25519Key,
                                        spec.registry()
                                                .getKey(contractKey)
                                                .getThresholdKey()
                                                .getKeys()
                                                .getKeys(0));
                        allRunFor(
                                spec,
                                atomicBatchDefaultOperator(contractCall(
                                                TOKEN_CREATE_CONTRACT,
                                                CREATE_FUNGIBLE_TOKEN_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                ed2551Key.get(),
                                                spec.registry()
                                                        .getKey(ECDSA_KEY)
                                                        .getECDSASecp256K1()
                                                        .toByteArray(),
                                                tokenCreateContractAddress.get(),
                                                tokenCreateContractAddress.get(),
                                                accountAddress.get(),
                                                AUTO_RENEW_PERIOD,
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT_TO_ASSOCIATE))))
                                        .via("first create txn")
                                        .gas(GAS_TO_OFFER)
                                        .sending(DEFAULT_AMOUNT_TO_SEND)
                                        .payingWith(ACCOUNT)
                                        .signedBy(contractKey)
                                        .refusingEthConversion()
                                        .exposingResultTo(result -> {
                                            final var res = (Address) result[0];
                                            createTokenNum.set(numberOfLongZero(HexFormat.of()
                                                    .parseHex(res.toString().substring(2))));
                                        })
                                        .hasKnownStatus(SUCCESS)),
                                newKeyNamed(tokenCreateContractAsKey).shape(CONTRACT.signedWith(TOKEN_CREATE_CONTRACT)),
                                newKeyNamed(tokenCreateContractAsKeyDelegate)
                                        .shape(DELEGATE_CONTRACT.signedWith(TOKEN_CREATE_CONTRACT)));
                    }),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            getContractInfo(TOKEN_CREATE_CONTRACT)
                                    .has(ContractInfoAsserts.contractWith().autoRenewAccountId(ACCOUNT))
                                    .logged(),
                            getAccountBalance(ACCOUNT).logged(),
                            getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                            getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                            childRecordsCheck(
                                    "first create txn",
                                    ResponseCodeEnum.SUCCESS,
                                    TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS),
                                    TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS),
                                    TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS)),
                            sourcing(() -> getAccountInfo(ACCOUNT_TO_ASSOCIATE)
                                    .logged()
                                    .hasTokenRelationShipCount(1)),
                            sourcing(() -> getTokenInfo(String.valueOf(createTokenNum.get()))
                                    .logged()
                                    .hasTokenType(TokenType.FUNGIBLE_COMMON)
                                    .hasSymbol(TOKEN_SYMBOL)
                                    .hasName(TOKEN_NAME)
                                    .hasDecimals(8)
                                    .hasTotalSupply(100)
                                    .hasEntityMemo(MEMO)
                                    .hasTreasury(ACCOUNT)
                                    // Token doesn't inherit contract's auto-renew
                                    // account if set in tokenCreate
                                    .hasAutoRenewAccount(ACCOUNT)
                                    .hasAutoRenewPeriod(AUTO_RENEW_PERIOD)
                                    .hasSupplyType(TokenSupplyType.INFINITE)
                                    .searchKeysGlobally()
                                    .hasAdminKey(ed25519Key)
                                    .hasKycKey(ed25519Key)
                                    .hasFreezeKey(ECDSA_KEY)
                                    .hasWipeKey(ECDSA_KEY)
                                    .hasSupplyKey(tokenCreateContractAsKey)
                                    .hasFeeScheduleKey(tokenCreateContractAsKeyDelegate)
                                    .hasPauseKey(ADMIN_KEY)
                                    .hasPauseStatus(TokenPauseStatus.Unpaused)),
                            cryptoDelete(ACCOUNT).hasKnownStatus(ACCOUNT_IS_TREASURY))));
        }

        private static long numberOfLongZero(@NonNull final byte[] explicit) {
            return longFrom(
                    explicit[12],
                    explicit[13],
                    explicit[14],
                    explicit[15],
                    explicit[16],
                    explicit[17],
                    explicit[18],
                    explicit[19]);
        }

        private static long longFrom(
                final byte b1,
                final byte b2,
                final byte b3,
                final byte b4,
                final byte b5,
                final byte b6,
                final byte b7,
                final byte b8) {
            return (b1 & 0xFFL) << 56
                    | (b2 & 0xFFL) << 48
                    | (b3 & 0xFFL) << 40
                    | (b4 & 0xFFL) << 32
                    | (b5 & 0xFFL) << 24
                    | (b6 & 0xFFL) << 16
                    | (b7 & 0xFFL) << 8
                    | (b8 & 0xFFL);
        }
    }

    /**
     * CryptoTransferHTSSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicHapiTransferFromForFungibleToken() {
        final var allowance = 10L;
        final var successfulTransferFromTxn = "txn";
        final var successfulTransferFromTxn2 = "txn2";
        final var revertingTransferFromTxn = "revertWhenMoreThanAllowance";
        final var revertingTransferFromTxn2 = "revertingTxn";

        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final AtomicReference<Address> ownerAddress = new AtomicReference<>();
        final AtomicReference<AccountID> ownerId = new AtomicReference<>();
        final AtomicReference<ByteString> ownerByteStr = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        final AtomicReference<AccountID> receiverId = new AtomicReference<>();
        final AtomicReference<ByteString> receiverByteStr = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER)
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(5)
                        .exposingCreatedIdTo(ownerId::set)
                        .exposingEvmAddressTo(ownerAddress::set),
                cryptoCreate(RECEIVER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingCreatedIdTo(receiverId::set)
                        .exposingEvmAddressTo(receiverAddress::set),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER)
                        .exposingAddressTo(tokenAddress::set),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)
                        .via("baseApproveTxn")
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                // trying to transfer more than allowance should revert
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        tokenAddress.get(),
                                        ownerAddress.get(),
                                        receiverAddress.get(),
                                        BigInteger.valueOf(allowance + 1))
                                .via(revertingTransferFromTxn)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                // transfer allowance/2 amount
                sourcing(() -> atomicBatchDefaultOperator(
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        tokenAddress.get(),
                                        ownerAddress.get(),
                                        receiverAddress.get(),
                                        BigInteger.valueOf(allowance / 2))
                                .via(successfulTransferFromTxn)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(SUCCESS),
                        // transfer the rest of the allowance
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        tokenAddress.get(),
                                        ownerAddress.get(),
                                        receiverAddress.get(),
                                        BigInteger.valueOf(allowance / 2))
                                .via(successfulTransferFromTxn2)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(SUCCESS))),
                // no allowance left, should fail
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        tokenAddress.get(),
                                        ownerAddress.get(),
                                        receiverAddress.get(),
                                        BigInteger.ONE)
                                .via(revertingTransferFromTxn2)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                validatePrecompileTransferResult(
                        revertingTransferFromTxn,
                        CONTRACT_REVERT_EXECUTED,
                        ParsingConstants.FunctionType.HAPI_TRANSFER_FROM,
                        AMOUNT_EXCEEDS_ALLOWANCE),
                validatePrecompileTransferResult(
                        successfulTransferFromTxn, SUCCESS, ParsingConstants.FunctionType.HAPI_TRANSFER_FROM, SUCCESS),
                validatePrecompileTransferResult(
                        successfulTransferFromTxn2, SUCCESS, ParsingConstants.FunctionType.HAPI_TRANSFER_FROM, SUCCESS),
                validatePrecompileTransferResult(
                        revertingTransferFromTxn2,
                        CONTRACT_REVERT_EXECUTED,
                        ParsingConstants.FunctionType.HAPI_TRANSFER_FROM,
                        SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                withOpContext((spec, log) -> {
                    final var owner = ownerId.get();
                    ownerByteStr.set(
                            parsedToByteString(owner.getShardNum(), owner.getRealmNum(), owner.getAccountNum()));
                    final var receiver = receiverId.get();
                    receiverByteStr.set(parsedToByteString(
                            receiver.getShardNum(), receiver.getRealmNum(), receiver.getAccountNum()));
                }),
                sourcing(() -> getTxnRecord(successfulTransferFromTxn)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .withTopicsInOrder(List.of(
                                                        eventSignatureOf(TRANSFER_SIGNATURE),
                                                        ownerByteStr.get(),
                                                        receiverByteStr.get()))
                                                .longValue(allowance / 2)))))
                        .andAllChildRecords()),
                sourcing(() -> getTxnRecord(successfulTransferFromTxn2)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .withTopicsInOrder(List.of(
                                                        eventSignatureOf(TRANSFER_SIGNATURE),
                                                        ownerByteStr.get(),
                                                        receiverByteStr.get()))
                                                .longValue(allowance / 2)))))
                        .andAllChildRecords()));
    }

    /**
     * DefaultTokenStatusSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicGetTokenDefaultFreezeStatus() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeDefault(true)
                        .freezeKey(FREEZE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                contractCreate(TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                        TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                        GET_TOKEN_DEFAULT_FREEZE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(ACCOUNT)
                                .via("GetTokenDefaultFreezeStatusTx")
                                .gas(GAS_TO_OFFER)),
                        contractCallLocal(
                                TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                GET_TOKEN_DEFAULT_FREEZE,
                                HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get()))))),
                childRecordsCheck(
                        "GetTokenDefaultFreezeStatusTx",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(
                                                        ParsingConstants.FunctionType.GET_TOKEN_DEFAULT_FREEZE_STATUS)
                                                .withStatus(SUCCESS)
                                                .withTokenDefaultFreezeStatus(true)))));
    }

    /**
     * DelegatePrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicDelegateCallForTransfer() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        final var delegateKey = "simpleAndDelegateKey";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(RECEIVER).exposingEvmAddressTo(receiverAddress::set),
                contractCreate(NESTED_SERVICE_CONTRACT).refusingEthConversion(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        OUTER_DELEGATE_CONTRACT,
                                        HapiParserUtil.asHeadlongAddress(
                                                getNestedContractAddress(NESTED_SERVICE_CONTRACT, spec)))
                                .refusingEthConversion(),
                        newKeyNamed(delegateKey)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, OUTER_DELEGATE_CONTRACT))),
                        atomicBatchDefaultOperator(
                                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("First!"))),
                                tokenAssociate(NESTED_SERVICE_CONTRACT, VANILLA_TOKEN),
                                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT))
                                        .payingWith(GENESIS),
                                tokenAssociate(OUTER_DELEGATE_CONTRACT, VANILLA_TOKEN),
                                cryptoUpdate(ACCOUNT).key(delegateKey),
                                contractCall(
                                                OUTER_DELEGATE_CONTRACT,
                                                "transferDelegateCall",
                                                vanillaTokenTokenAddress.get(),
                                                accountAddress.get(),
                                                receiverAddress.get(),
                                                1L)
                                        .payingWith(GENESIS)
                                        .via("delegateTransferCallWithDelegateContractKeyTxn")
                                        .gas(GAS_TO_OFFER)))),
                childRecordsCheck(
                        "delegateTransferCallWithDelegateContractKeyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 0),
                getAccountBalance(RECEIVER).hasTokenBalance(VANILLA_TOKEN, 1));
    }

    /**
     * DeleteTokenPrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicDeleteFungibleToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final var tokenAlreadyDeletedTxn = "tokenAlreadyDeletedTxn";
        final var THRESHOLD_KEY = "thresholdKey";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).key(MULTI_KEY).balance(100 * ONE_HBAR).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(HapiPropertySource.asToken(id)))
                        .initialSupply(1110),
                contractCreate(DELETE_TOKEN_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, DELETE_TOKEN_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).adminKey(THRESHOLD_KEY).signedByPayerAnd(MULTI_KEY, THRESHOLD_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        atomicBatchDefaultOperator(contractCall(
                                        DELETE_TOKEN_CONTRACT,
                                        TOKEN_DELETE_FUNCTION,
                                        HapiParserUtil.asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .gas(GAS_TO_OFFER)
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("deleteTokenTxn")),
                        getTokenInfo(VANILLA_TOKEN).isDeleted().logged(),
                        cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT))
                                .hasKnownStatus(TOKEN_WAS_DELETED),
                        atomicBatchDefaultOperator(contractCall(
                                                DELETE_TOKEN_CONTRACT,
                                                TOKEN_DELETE_FUNCTION,
                                                HapiParserUtil.asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                        .gas(GAS_TO_OFFER)
                                        .via(tokenAlreadyDeletedTxn)
                                        .signedBy(GENESIS, ACCOUNT)
                                        .alsoSigningWithFullPrefix(ACCOUNT)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                childRecordsCheck(
                        tokenAlreadyDeletedTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_WAS_DELETED)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_WAS_DELETED)))));
    }

    /**
     * DissociatePrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicDissociateTokensNegativeScenarios() {
        final AtomicReference<Address> tokenAddress1 = new AtomicReference<>();
        final AtomicReference<Address> tokenAddress2 = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final var nonExistingAccount = "nonExistingAccount";
        final var nonExistingTokenArray = "nonExistingTokenArray";
        final var someNonExistingTokenArray = "someNonExistingTokenArray";
        final var zeroAccountAddress = "zeroAccountAddress";
        final var nullTokenArray = "nullTokens";
        final var nonExistingTokensInArray = "nonExistingTokensInArray";
        return hapiTest(
                contractCreate(NEGATIVE_DISSOCIATIONS_CONTRACT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress1::set),
                tokenCreate("TOKEN1")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress2::set),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(id -> accountAddress.set(idAsHeadlongAddress(id))),
                tokenAssociate(ACCOUNT, List.of(FUNGIBLE_TOKEN, "TOKEN1")),
                withOpContext((spec, custom) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                                NEGATIVE_DISSOCIATIONS_CONTRACT,
                                                "dissociateTokensWithNonExistingAccountAddress",
                                                (Object) new Address[] {tokenAddress1.get(), tokenAddress2.get()})
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .gas(GAS_TO_OFFER)
                                        .via(nonExistingAccount))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith("TOKEN1")),
                        newKeyNamed("CONTRACT_KEY")
                                .shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, NEGATIVE_DISSOCIATIONS_CONTRACT))),
                        atomicBatchDefaultOperator(
                                cryptoUpdate(ACCOUNT).key("CONTRACT_KEY"),
                                contractCall(
                                                NEGATIVE_DISSOCIATIONS_CONTRACT,
                                                "dissociateTokensWithEmptyTokensArray",
                                                accountAddress.get())
                                        .hasKnownStatus(SUCCESS)
                                        .gas(GAS_TO_OFFER)
                                        .signingWith(ACCOUNT)
                                        .via(nonExistingTokenArray)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith("TOKEN1")),
                        contractCall(NEGATIVE_DISSOCIATIONS_CONTRACT, "dissociateTokensWithNullAccount", (Object)
                                        new Address[] {tokenAddress1.get(), tokenAddress2.get()})
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(zeroAccountAddress),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith("TOKEN1")),
                        atomicBatchDefaultOperator(contractCall(
                                                NEGATIVE_DISSOCIATIONS_CONTRACT,
                                                "dissociateTokensWithNullTokensArray",
                                                accountAddress.get())
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .gas(GAS_TO_OFFER)
                                        .signingWith(ACCOUNT)
                                        .via(nullTokenArray))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatchDefaultOperator(contractCall(
                                                NEGATIVE_DISSOCIATIONS_CONTRACT,
                                                "dissociateTokensWithNonExistingTokensArray",
                                                accountAddress.get())
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .gas(GAS_TO_OFFER)
                                        .signingWith(ACCOUNT)
                                        .via(nonExistingTokensInArray))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatchDefaultOperator(contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokensWithTokensArrayWithSomeNonExistingAddresses",
                                        accountAddress.get(),
                                        new Address[] {tokenAddress1.get(), tokenAddress2.get()})
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER)
                                .signingWith(ACCOUNT)
                                .via(someNonExistingTokenArray)),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(FUNGIBLE_TOKEN),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship("TOKEN1"))),
                childRecordsCheck(
                        nonExistingAccount,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(nonExistingTokenArray, SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck(
                        zeroAccountAddress,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        nullTokenArray, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        someNonExistingTokenArray, SUCCESS, recordWith().status(SUCCESS)));
    }

    /**
     * ERCPrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicGetErc20TokenName() {
        final var txnName = "getErc20TokenNameTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(5)
                        .name(TOKEN_NAME)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                        ERC_20_CONTRACT,
                                        "name",
                                        HapiParserUtil.asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(txnName)
                                .gas(4_000_000)
                                .hasKnownStatus(SUCCESS)))),
                childRecordsCheck(
                        txnName,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.ERC_NAME)
                                                .withName(TOKEN_NAME)))));
    }

    /**
     * FreezeUnfreezePrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicNoTokenIdReverts() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeKey(FREEZE_KEY)
                        .initialSupply(1_000),
                contractCreate(FREEZE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                                FREEZE_CONTRACT,
                                                TOKEN_UNFREEZE_FUNC,
                                                HapiParserUtil.asHeadlongAddress(INVALID_ADDRESS),
                                                accountAddress.get())
                                        .payingWith(ACCOUNT)
                                        .gas(GAS_TO_OFFER)
                                        .via("UnfreezeTx")
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        cryptoUpdate(ACCOUNT).key(FREEZE_KEY),
                        atomicBatchDefaultOperator(contractCall(
                                                FREEZE_CONTRACT,
                                                TOKEN_FREEZE_FUNC,
                                                HapiParserUtil.asHeadlongAddress(INVALID_ADDRESS),
                                                accountAddress.get())
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .payingWith(ACCOUNT)
                                        .gas(GAS_TO_OFFER)
                                        .via("FreezeTx"))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                childRecordsCheck(
                        "UnfreezeTx", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        "FreezeTx", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)));
    }

    /**
     * GrantRevokeKycSuite
     */
    @Nested
    class GrantRevokeKycSuite {
        @HapiTest
        final Stream<DynamicTest> atomicRevokeKycFailWithoutKeyTx() {
            final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
            final AtomicReference<Address> secondAccountAddress = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(KYC_KEY),
                    cryptoCreate(ACCOUNT_2).exposingEvmAddressTo(secondAccountAddress::set),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate(VANILLA_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .kycKey(KYC_KEY)
                            .initialSupply(1_000)
                            .exposingAddressTo(vanillaTokenAddress::set),
                    contractCreate(GRANT_REVOKE_KYC_CONTRACT),
                    tokenAssociate(ACCOUNT_2, VANILLA_TOKEN),
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_GRANT_KYC,
                                                    vanillaTokenAddress.get(),
                                                    secondAccountAddress.get())
                                            .via("GrantKycAccountWithoutKeyTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_REVOKE_KYC,
                                                    vanillaTokenAddress.get(),
                                                    secondAccountAddress.get())
                                            .via("RevokeKycAccountWithoutKeyTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                    validatePrecompileStatus(
                            "RevokeKycAccountWithoutKeyTx", CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE),
                    validatePrecompileStatus(
                            "GrantKycAccountWithoutKeyTx", CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE));
        }

        @HapiTest
        final Stream<DynamicTest> atomicGrantRevokeKycFailKeyNotMatchingTokenKey() {
            final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
            final AtomicReference<Address> secondAccountAddress = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(NON_KYC_KEY),
                    cryptoCreate(ACCOUNT_2).exposingEvmAddressTo(secondAccountAddress::set),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate(VANILLA_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .kycKey(KYC_KEY)
                            .initialSupply(1_000)
                            .exposingAddressTo(vanillaTokenAddress::set),
                    contractCreate(GRANT_REVOKE_KYC_CONTRACT),
                    tokenAssociate(ACCOUNT_2, VANILLA_TOKEN),
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            cryptoUpdate(ACCOUNT_2).key(NON_KYC_KEY),
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_GRANT_KYC,
                                                    vanillaTokenAddress.get(),
                                                    secondAccountAddress.get())
                                            .via("GrantKycAccountKeyNotMatchingTokenKeyTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_REVOKE_KYC,
                                                    vanillaTokenAddress.get(),
                                                    secondAccountAddress.get())
                                            .via("RevokeKycAccountKeyNotMatchingTokenKeyTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                    validatePrecompileStatus(
                            "GrantKycAccountKeyNotMatchingTokenKeyTx", CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE),
                    validatePrecompileStatus(
                            "RevokeKycAccountKeyNotMatchingTokenKeyTx", CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE));
        }

        @HapiTest
        final Stream<DynamicTest> atomicGrantRevokeKycFailTokenWithoutKey() {
            final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
            final AtomicReference<Address> secondAccountAddress = new AtomicReference<>();
            final AtomicReference<Address> tokenWithoutKeyAddress = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(NON_KYC_KEY),
                    cryptoCreate(ACCOUNT_2).exposingEvmAddressTo(secondAccountAddress::set),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate("TOKEN_WITHOUT_KEY").exposingAddressTo(tokenWithoutKeyAddress::set),
                    tokenCreate(VANILLA_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .kycKey(KYC_KEY)
                            .initialSupply(1_000)
                            .exposingAddressTo(vanillaTokenAddress::set),
                    contractCreate(GRANT_REVOKE_KYC_CONTRACT),
                    tokenAssociate(ACCOUNT_2, VANILLA_TOKEN),
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            cryptoUpdate(ACCOUNT_2).key(KYC_KEY),
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_GRANT_KYC,
                                                    tokenWithoutKeyAddress.get(),
                                                    secondAccountAddress.get())
                                            .via("GrantKycTokenWithoutKeyTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_REVOKE_KYC,
                                                    tokenWithoutKeyAddress.get(),
                                                    secondAccountAddress.get())
                                            .via("RevokeKycTokenWithoutKeyTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                    validatePrecompileStatus(
                            "GrantKycTokenWithoutKeyTx", CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_KYC_KEY),
                    validatePrecompileStatus(
                            "RevokeKycTokenWithoutKeyTx", CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_KYC_KEY));
        }

        @HapiTest
        final Stream<DynamicTest> atomicGrantRevokeKycFailInvalidToken() {
            final AtomicReference<Address> secondAccountAddress = new AtomicReference<>();
            final var invalidTokenID = TokenID.newBuilder().build();

            return hapiTest(
                    cryptoCreate(ACCOUNT_2).exposingEvmAddressTo(secondAccountAddress::set),
                    uploadInitCode(GRANT_REVOKE_KYC_CONTRACT),
                    contractCreate(GRANT_REVOKE_KYC_CONTRACT),
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_REVOKE_KYC,
                                                    HapiParserUtil.asHeadlongAddress(asAddress(invalidTokenID)),
                                                    secondAccountAddress.get())
                                            .via("RevokeKycWrongTokenTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatchDefaultOperator(contractCall(
                                                    GRANT_REVOKE_KYC_CONTRACT,
                                                    TOKEN_GRANT_KYC,
                                                    HapiParserUtil.asHeadlongAddress(asAddress(invalidTokenID)),
                                                    secondAccountAddress.get())
                                            .via("GrantKycWrongTokenTx")
                                            .gas(GAS_TO_OFFER)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                    validatePrecompileStatus("RevokeKycWrongTokenTx", CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID),
                    validatePrecompileStatus("GrantKycWrongTokenTx", CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID));
        }

        private SpecOperation validatePrecompileStatus(
                String contractCallTxn, ResponseCodeEnum parentStatus, ResponseCodeEnum precompileStatus) {
            return childRecordsCheck(
                    contractCallTxn,
                    parentStatus,
                    recordWith()
                            .status(precompileStatus)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult().withStatus(precompileStatus))));
        }
    }

    // Helper methods

    private SpecOperation validatePrecompileTransferResult(
            String contractCallTxn,
            ResponseCodeEnum parentStatus,
            ParsingConstants.FunctionType functionType,
            ResponseCodeEnum precompileStatus) {
        return childRecordsCheck(
                contractCallTxn,
                parentStatus,
                recordWith()
                        .status(precompileStatus)
                        .contractCallResult(resultWith()
                                .contractCallResult(htsPrecompileResult()
                                        .forFunction(functionType)
                                        .withStatus(precompileStatus))));
    }

    private SpecOperation validatedHtsPrecompileResult(
            String callTxn, ResponseCodeEnum callStatus, ResponseCodeEnum precompileStatus) {
        return childRecordsCheck(
                callTxn,
                callStatus,
                recordWith()
                        .status(precompileStatus)
                        .contractCallResult(resultWith()
                                .contractCallResult(htsPrecompileResult().withStatus(precompileStatus))));
    }

    private HapiAtomicBatch atomicBatchDefaultOperator(final HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }
}
