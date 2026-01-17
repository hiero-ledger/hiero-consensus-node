// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTokenString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.anyResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.redirectCallResult;
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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingNFT;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeTargetLedgerIdTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.contract.Utils.headlongFromHexed;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.Utils.nCopiesOfSender;
import static com.hedera.services.bdd.suites.contract.Utils.nNonMirrorAddressFrom;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.RECEIVER_2;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_TOKEN_PUBLIC;
import static com.hedera.services.bdd.suites.contract.precompile.ERCPrecompileSuite.TRANSFER_SIGNATURE;
import static com.hedera.services.bdd.suites.file.FileUpdateSuite.CIVILIAN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.KNOWABLE_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenPauseStatus.Paused;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.INFINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.Fraction;
import com.hederahashgraph.api.proto.java.FractionalFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenPauseStatus;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
class AtomicBatchPrecompileTest {
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
    private static final String TOKEN_GRANT_KYC = "tokenGrantKyc";
    private static final String TOKEN_REVOKE_KYC = "tokenRevokeKyc";
    private static final String HRC = "HRC";
    private static final String ASSOCIATE = "associate";
    private static final String DISSOCIATE = "dissociate";
    private static final String AUTO_CREATION_MODES = "AutoCreationModes";
    private static final String PAUSE_UNPAUSE_CONTRACT = "PauseUnpauseTokenAccount";
    private static final String PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME = "pauseTokenAccount";
    private static final String THE_PRNG_CONTRACT = "PrngSystemContract";
    private static final String GET_SEED = "getPseudorandomSeed";
    private static final String REDIRECT_TEST_CONTRACT = "RedirectTestContract";
    private static final String MINIMAL_CREATIONS_CONTRACT = "MinimalTokenCreations";
    private static final String TOKEN_INFO_CONTRACT = "TokenInfoContract";
    private static final String GET_INFORMATION_FOR_TOKEN = "getInformationForToken";
    private static final String WIPE_CONTRACT = "WipeTokenAccount";
    private static final String WIPE_FUNGIBLE_TOKEN = "wipeFungibleToken";

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
    private static final String WIPE_KEY = "wipeKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String PAUSE_KEY = "pauseKey";

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
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String HTS_COLLECTOR = "htsCollector";

    // tokens
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String SYMBOL = "T";
    private static final int MAX_SUPPLY = 1000;
    private static final int NUMERATOR = 1;
    private static final int DENOMINATOR = 2;
    private static final int MINIMUM_TO_COLLECT = 5;
    private static final int MAXIMUM_TO_COLLECT = 400;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(Map.of("contracts.throttle.throttleByGas", "false"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        // upload contracts init code
        testLifecycle.doAdhoc(uploadInitCode(
                HTS_APPROVE_ALLOWANCE_CONTRACT,
                DIRECT_ERC_CALLEE,
                THE_GRACEFULLY_FAILING_CONTRACT,
                ATOMIC_CRYPTO_TRANSFER_CONTRACT,
                HTS_TRANSFER_FROM_CONTRACT,
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
                GRANT_REVOKE_KYC_CONTRACT,
                HRC,
                AUTO_CREATION_MODES,
                PAUSE_UNPAUSE_CONTRACT,
                THE_PRNG_CONTRACT,
                REDIRECT_TEST_CONTRACT,
                MINIMAL_CREATIONS_CONTRACT,
                TOKEN_INFO_CONTRACT,
                WIPE_CONTRACT));
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

        private List<SpecOperation> deployContractAndUpdateKeys() {
            return List.of(
                    contractCreate(ATOMIC_CRYPTO_TRANSFER_CONTRACT),
                    newKeyNamed(DELEGATE_KEY)
                            .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ATOMIC_CRYPTO_TRANSFER_CONTRACT))));
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
    final Stream<DynamicTest> atomicTransferDoesntWorkWithoutTopLevelSignatures() {
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
    @Tag(MATS)
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

    /**
     * HRCPrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicHrcCanDissociateFromDeletedToken() {
        final AtomicReference<String> nonfungibleTokenNum = new AtomicReference<>();
        final var associateTxn = "associateTxn";
        final var dissociateTxn = "dissociateTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .name(TOKEN_NAME)
                        .symbol(TOKEN_SYMBOL)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(nonfungibleTokenNum::set),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("PRICELESS")))
                        .payingWith(ACCOUNT)
                        .via("mintTxn"),
                contractCreate(HRC),
                withOpContext((spec, opLog) -> {
                    var nonfungibleTokenAddress = asHexedSolidityAddress(asToken(nonfungibleTokenNum.get()));
                    allRunFor(
                            spec,
                            // Associate non-fungible token
                            atomicBatchDefaultOperator(contractCallWithFunctionAbi(
                                            nonfungibleTokenAddress,
                                            getABIFor(Utils.FunctionType.FUNCTION, ASSOCIATE, HRC))
                                    .payingWith(ACCOUNT)
                                    .gas(1_000_000)
                                    .via(associateTxn)),
                            cryptoTransfer(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1)
                                    .between(TOKEN_TREASURY, ACCOUNT)),
                            tokenDelete(NON_FUNGIBLE_TOKEN).via("deleteTxn"),
                            // Dissociate non-fungible token
                            contractCallWithFunctionAbi(
                                            nonfungibleTokenAddress,
                                            getABIFor(Utils.FunctionType.FUNCTION, DISSOCIATE, HRC))
                                    .payingWith(ACCOUNT)
                                    .gas(1_000_000)
                                    .via(dissociateTxn));
                }),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                associateTxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(
                                                        htsPrecompileResult().withStatus(SUCCESS)))),
                        childRecordsCheck(
                                dissociateTxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(
                                                        htsPrecompileResult().withStatus(SUCCESS)))))));
    }

    /**
     * IsAssociatedSystemContractTest
     */
    @Nested
    class IsAssociatedSystemContractTest {

        @FungibleToken(name = "fungibleToken")
        static SpecFungibleToken fungibleToken;

        @NonFungibleToken(name = "nonFungibleToken")
        static SpecNonFungibleToken nonFungibleToken;

        @Account(name = "senderAccount", tinybarBalance = ONE_HUNDRED_HBARS)
        static SpecAccount senderAccount;

        @FungibleToken(name = "fungibleToken2")
        static SpecFungibleToken fungibleToken2;

        @NonFungibleToken(name = "nonFungibleToken2")
        static SpecNonFungibleToken nonFungibleToken2;

        @HapiTest
        @DisplayName("returns true for EOA msg.sender exactly when associated")
        Stream<DynamicTest> atomicReturnsTrueIffEoaMsgSenderIsAssociated() {
            return hapiTest(
                    assertAtomicEoaGetsResultForBothTokens(false),
                    senderAccount.associateTokens(fungibleToken2, nonFungibleToken2),
                    assertAtomicEoaGetsResultForBothTokens(true),
                    senderAccount.dissociateTokens(fungibleToken2, nonFungibleToken2),
                    assertAtomicEoaGetsResultForBothTokens(false));
        }

        private SpecOperation assertAtomicEoaGetsResultForBothTokens(final boolean isAssociated) {
            return blockingOrder(
                    fungibleToken
                            .call(TokenRedirectContract.HRC, "isAssociated")
                            .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                            .payingWith(senderAccount)
                            .andAssert(txn -> txn.hasResults(
                                    anyResult(),
                                    redirectCallResult(TokenRedirectContract.HRC, "isAssociated", isAssociated))),
                    nonFungibleToken
                            .call(TokenRedirectContract.HRC, "isAssociated")
                            .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                            .payingWith(senderAccount)
                            .andAssert(txn -> txn.hasResults(
                                    anyResult(),
                                    redirectCallResult(TokenRedirectContract.HRC, "isAssociated", isAssociated))));
        }
    }

    /**
     * LazyCreateThroughPrecompileSuite
     */
    @LeakyHapiTest(overrides = {"consensus.handle.maxFollowingRecords"})
    final Stream<DynamicTest> atomicResourceLimitExceededRevertsAllRecords() {
        final var n = 6; // + 2 for the mint and approve allowance in the batch
        final var nft = "nft";
        final var nftKey = ADMIN_KEY;
        final var creationAttempt = "CREATION_ATTEMPT";
        final AtomicLong civilianId = new AtomicLong();
        final AtomicReference<String> nftMirrorAddr = new AtomicReference<>();

        return hapiTest(
                overriding("consensus.handle.maxFollowingRecords", "" + (n - 1)),
                newKeyNamed(nftKey),
                contractCreate(AUTO_CREATION_MODES),
                cryptoCreate(CIVILIAN).keyShape(ED25519).exposingCreatedIdTo(id -> civilianId.set(id.getAccountNum())),
                tokenCreate(nft)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(nftKey)
                        .initialSupply(0)
                        .treasury(CIVILIAN)
                        .exposingCreatedIdTo(
                                idLit -> nftMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(
                                        mintToken(
                                                nft,
                                                IntStream.range(0, n)
                                                        .mapToObj(i -> ByteString.copyFromUtf8("ONE_TIME" + i))
                                                        .toList()),
                                        cryptoApproveAllowance()
                                                .payingWith(CIVILIAN)
                                                .addNftAllowance(CIVILIAN, nft, AUTO_CREATION_MODES, true, List.of()),
                                        contractCall(
                                                        AUTO_CREATION_MODES,
                                                        "createSeveralDirectly",
                                                        headlongFromHexed(nftMirrorAddr.get()),
                                                        nCopiesOfSender(n, mirrorAddrWith(spec, civilianId.get())),
                                                        nNonMirrorAddressFrom(n, civilianId.get() + 3_050_000),
                                                        LongStream.iterate(1L, l -> l + 1)
                                                                .limit(n)
                                                                .toArray())
                                                .via(creationAttempt)
                                                .gas(GAS_TO_OFFER)
                                                .alsoSigningWithFullPrefix(CIVILIAN)
                                                .hasKnownStatusFrom(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                childRecordsCheck(
                        creationAttempt, CONTRACT_REVERT_EXECUTED, recordWith().status(MAX_CHILD_RECORDS_EXCEEDED)));
    }

    /**
     * PauseUnpauseTokenAccountPrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicPauseFungibleToken() {
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        final var contractKey = "contractKey";
        final var wrongPauseKeyTxn = "pauseFungibleAccountDoesNotOwnPauseKeyFailingTxn";
        final var tokenDeletedTxn = "pauseFungibleAccountIsDeletedFailingTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ACCOUNT),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .pauseKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .initialSupply(1_000)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                                PAUSE_UNPAUSE_CONTRACT,
                                                PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                                vanillaTokenAddress.get())
                                        .signedBy(GENESIS, ACCOUNT)
                                        .alsoSigningWithFullPrefix(ACCOUNT)
                                        .via(wrongPauseKeyTxn)
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        newKeyNamed(contractKey).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, PAUSE_UNPAUSE_CONTRACT))),
                        atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN).pauseKey(contractKey).signedByPayerAnd(MULTI_KEY),
                                cryptoUpdate(ACCOUNT).key(contractKey),
                                contractCall(
                                                PAUSE_UNPAUSE_CONTRACT,
                                                PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                                vanillaTokenAddress.get())
                                        .signedBy(GENESIS, ACCOUNT)
                                        .alsoSigningWithFullPrefix(ACCOUNT)
                                        .gas(GAS_TO_OFFER)),
                        getTokenInfo(VANILLA_TOKEN).hasPauseStatus(Paused),
                        atomicBatchDefaultOperator(
                                        tokenUnpause(VANILLA_TOKEN),
                                        tokenDelete(VANILLA_TOKEN),
                                        contractCall(
                                                        PAUSE_UNPAUSE_CONTRACT,
                                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                                        vanillaTokenAddress.get())
                                                .signedBy(GENESIS, ACCOUNT)
                                                .alsoSigningWithFullPrefix(ACCOUNT)
                                                .via(tokenDeletedTxn)
                                                .gas(GAS_TO_OFFER)
                                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                validatedHtsPrecompileResult(
                        wrongPauseKeyTxn, CONTRACT_REVERT_EXECUTED, INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE),
                validatedHtsPrecompileResult(tokenDeletedTxn, CONTRACT_REVERT_EXECUTED, TOKEN_WAS_DELETED));
    }

    /**
     * PrngPrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicMultipleCallsHaveIndependentResults() {
        final var prng = THE_PRNG_CONTRACT;
        final var gasToOffer = 400_000;
        final var numCalls = 5;
        final List<String> prngSeeds = new ArrayList<>();
        return hapiTest(
                contractCreate(prng),
                withOpContext((spec, opLog) -> {
                    for (int i = 0; i < numCalls; i++) {
                        final var txn = "call" + i;
                        final var call = atomicBatchDefaultOperator(
                                contractCall(prng, GET_SEED).gas(gasToOffer).via(txn));
                        final var lookup = getTxnRecord(txn).andAllChildRecords();
                        allRunFor(spec, call, lookup);
                        final var response = lookup.getResponseRecord();
                        final var rawResult = response.getContractCallResult()
                                .getContractCallResult()
                                .toByteArray();
                        // Since this contract returns the result of the Prng system
                        // contract, its call result
                        // should be identical to the result of the system contract
                        // in the child record
                        for (final var child : lookup.getChildRecords()) {
                            if (child.hasContractCallResult()) {
                                assertArrayEquals(
                                        rawResult,
                                        child.getContractCallResult()
                                                .getContractCallResult()
                                                .toByteArray());
                            }
                        }
                        prngSeeds.add(CommonUtils.hex(rawResult));
                    }
                    opLog.info("Got prng seeds  : {}", prngSeeds);
                    assertEquals(
                            prngSeeds.size(),
                            new HashSet<>(prngSeeds).size(),
                            "An N-3 running hash was repeated, which is" + " inconceivable");
                }),
                // It's possible to call these contracts in a static context with no issues
                contractCallLocal(prng, GET_SEED).gas(gasToOffer));
    }

    /**
     * RedirectPrecompileSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicBalanceOf() {
        final var totalSupply = 50;
        final var transactionName = "balanceOfTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(totalSupply)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                contractCreate(REDIRECT_TEST_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                        REDIRECT_TEST_CONTRACT,
                                        "getBalanceOf",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(TOKEN_TREASURY))))
                                .payingWith(ACCOUNT)
                                .via(transactionName)
                                .hasKnownStatus(SUCCESS)
                                .gas(1_000_000)))),
                childRecordsCheck(
                        transactionName,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.ERC_BALANCE)
                                                .withBalance(totalSupply))
                                        .gasUsed(2607L))));
    }

    /**
     * SigningResSuite
     */
    @LeakyHapiTest(overrides = {"contracts.keys.legacyActivations"})
    final Stream<DynamicTest> atomicAutoRenewAccountCanUseLegacySigActivationIfConfigured() {
        final var autoRenew = "autoRenew";
        final AtomicReference<Address> autoRenewMirrorAddr = new AtomicReference<>();
        final AtomicReference<ContractID> contractId = new AtomicReference<>();
        final var origKey = KeyShape.threshOf(1, ED25519, CONTRACT);
        final AtomicReference<TokenID> createdToken = new AtomicReference<>();
        final var firstCreateTxn = "firstCreateTxn";
        final var secondCreateTxn = "secondCreateTxn";

        return hapiTest(
                cryptoCreate(CIVILIAN).balance(10L * ONE_HUNDRED_HBARS),
                contractCreate(MINIMAL_CREATIONS_CONTRACT)
                        .exposingContractIdTo(contractId::set)
                        .gas(5_000_000L)
                        .refusingEthConversion(),
                cryptoCreate(autoRenew)
                        .keyShape(origKey.signedWith(sigs(ON, MINIMAL_CREATIONS_CONTRACT)))
                        .exposingCreatedIdTo(id -> autoRenewMirrorAddr.set(idAsHeadlongAddress(id))),
                // Fails without the auto-renew account's full-prefix signature
                sourcing(() -> contractCall(
                                MINIMAL_CREATIONS_CONTRACT,
                                "makeRenewableTokenIndirectly",
                                autoRenewMirrorAddr.get(),
                                THREE_MONTHS_IN_SECONDS)
                        .via(firstCreateTxn)
                        .gas(10L * GAS_TO_OFFER)
                        .sending(DEFAULT_AMOUNT_TO_SEND)
                        .payingWith(CIVILIAN)
                        .refusingEthConversion()
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTxnRecord(firstCreateTxn).andAllChildRecords().logged(),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var autoRenewNum = registry.getAccountID(autoRenew).getAccountNum();
                    final var parentContractNum =
                            registry.getContractId(MINIMAL_CREATIONS_CONTRACT).getContractNum();
                    final var overrideValue = autoRenewNum + "by[" + parentContractNum + "]";
                    final var propertyUpdate = overriding("contracts.keys.legacyActivations", overrideValue);
                    CustomSpecAssert.allRunFor(spec, propertyUpdate);
                }),
                // Succeeds now because the called contract received legacy activation privilege
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                MINIMAL_CREATIONS_CONTRACT,
                                "makeRenewableTokenIndirectly",
                                autoRenewMirrorAddr.get(),
                                THREE_MONTHS_IN_SECONDS)
                        .via(secondCreateTxn)
                        .gas(10L * GAS_TO_OFFER)
                        .sending(DEFAULT_AMOUNT_TO_SEND)
                        .payingWith(CIVILIAN)
                        .refusingEthConversion())),
                getTxnRecord(secondCreateTxn)
                        .andAllChildRecords()
                        .exposingTokenCreationsTo(creations -> createdToken.set(creations.getFirst())),
                childRecordsCheck(
                        firstCreateTxn,
                        CONTRACT_REVERT_EXECUTED,
                        TransactionRecordAsserts.recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                sourcing(() -> getTokenInfo(asTokenString(createdToken.get())).hasAutoRenewAccount(autoRenew)));
    }

    /**
     * TokenExpiryInfoSuite
     */
    @Nested
    class TokenExpiryInfoSuite {
        private static final Address ZERO_ADDRESS = HapiParserUtil.asHeadlongAddress(new byte[20]);
        public static final long MONTH_IN_SECONDS = 7_000_000L;

        @Contract(contract = "TokenExpiryContract", creationGas = 1_000_000L)
        static SpecContract tokenExpiryContract;

        @Account
        static SpecAccount newAutoRenewAccount;

        @HapiTest
        @DisplayName("atomic cannot update a missing token's expiry info")
        final Stream<DynamicTest> atomicCannotUpdateMissingToken() {
            return hapiTest(
                    // This function takes four arguments---a token address, an expiry second, an auto-renew account
                    // address, and an auto-renew period---and tries to update the token at that address with the given
                    // metadata; when expiry second is zero like here, it is ignored
                    tokenExpiryContract
                            .call("updateExpiryInfoForToken", ZERO_ADDRESS, 0L, newAutoRenewAccount, MONTH_IN_SECONDS)
                            .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                            .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
        }
    }

    /**
     * TokenInfoHTSSuite
     */
    @Nested
    class TokenInfoHTSSuite {

        @HapiTest
        final Stream<DynamicTest> atomicHappyPathGetTokenInfo() {
            final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
            final var tokenInfoTxn = "tokenInfoTxn";
            return hapiTest(
                    cryptoCreate(TOKEN_TREASURY).balance(0L),
                    cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                    cryptoCreate(HTS_COLLECTOR),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    newKeyNamed(PAUSE_KEY),
                    newKeyNamed(TokenKeyType.METADATA_KEY.name()),
                    contractCreate(TOKEN_INFO_CONTRACT).gas(4_000_000L),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .supplyType(TokenSupplyType.FINITE)
                            .entityMemo(MEMO)
                            .symbol(SYMBOL)
                            .name(FUNGIBLE_TOKEN)
                            .treasury(TOKEN_TREASURY)
                            .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                            .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                            .maxSupply(MAX_SUPPLY)
                            .initialSupply(500L)
                            .adminKey(ADMIN_KEY)
                            .freezeKey(FREEZE_KEY)
                            .kycKey(KYC_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .wipeKey(WIPE_KEY)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .pauseKey(PAUSE_KEY)
                            .metadataKey(TokenKeyType.METADATA_KEY.name())
                            .metaData("metadata")
                            .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                            // Include a fractional fee with no minimum to collect
                            .withCustom(
                                    fractionalFee(NUMERATOR, DENOMINATOR * 2L, 0, OptionalLong.empty(), TOKEN_TREASURY))
                            .withCustom(fractionalFee(
                                    NUMERATOR,
                                    DENOMINATOR,
                                    MINIMUM_TO_COLLECT,
                                    OptionalLong.of(MAXIMUM_TO_COLLECT),
                                    TOKEN_TREASURY)),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            atomicBatchDefaultOperator(contractCall(
                                            TOKEN_INFO_CONTRACT,
                                            GET_INFORMATION_FOR_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                    .via(tokenInfoTxn)
                                    .gas(1_000_000L)),
                            contractCallLocal(
                                    TOKEN_INFO_CONTRACT,
                                    GET_INFORMATION_FOR_TOKEN,
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN)))))),
                    exposeTargetLedgerIdTo(targetLedgerId::set),
                    withOpContext((spec, opLog) -> {
                        final var getTokenInfoQuery = getTokenInfo(FUNGIBLE_TOKEN);
                        allRunFor(spec, getTokenInfoQuery);
                        final var expirySecond = getTokenInfoQuery
                                .getResponse()
                                .getTokenGetInfo()
                                .getTokenInfo()
                                .getExpiry()
                                .getSeconds();

                        allRunFor(
                                spec,
                                childRecordsCheck(
                                        tokenInfoTxn,
                                        SUCCESS,
                                        recordWith()
                                                .status(SUCCESS)
                                                .contractCallResult(resultWith()
                                                        .contractCallResult(htsPrecompileResult()
                                                                .forFunction(
                                                                        ParsingConstants.FunctionType
                                                                                .HAPI_GET_TOKEN_INFO)
                                                                .withStatus(SUCCESS)
                                                                .withTokenInfo(
                                                                        buildBaseTokenInfo(
                                                                                        spec,
                                                                                        FUNGIBLE_TOKEN,
                                                                                        SYMBOL,
                                                                                        MEMO,
                                                                                        spec.registry()
                                                                                                .getAccountID(
                                                                                                        TOKEN_TREASURY),
                                                                                        spec.registry()
                                                                                                .getKey(ADMIN_KEY),
                                                                                        expirySecond,
                                                                                        targetLedgerId.get(),
                                                                                        TokenKycStatus.Revoked)
                                                                                .build())))));
                    }));
        }

        private static TokenInfo.Builder buildBaseTokenInfo(
                final HapiSpec spec,
                final String tokenName,
                final String symbol,
                final String memo,
                final AccountID treasury,
                final Key adminKey,
                final long expirySecond,
                ByteString ledgerId,
                final TokenKycStatus kycDefault) {

            final var autoRenewAccount = spec.registry().getAccountID(AUTO_RENEW_ACCOUNT);
            final var customFees = getExpectedCustomFees(spec);

            return TokenInfo.newBuilder()
                    .setLedgerId(ledgerId)
                    .setSupplyTypeValue(TokenSupplyType.FINITE_VALUE)
                    .setExpiry(Timestamp.newBuilder().setSeconds(expirySecond))
                    .setAutoRenewAccount(autoRenewAccount)
                    .setAutoRenewPeriod(Duration.newBuilder()
                            .setSeconds(THREE_MONTHS_IN_SECONDS)
                            .build())
                    .setSymbol(symbol)
                    .setName(tokenName)
                    .setMemo(memo)
                    .setTreasury(treasury)
                    .setTotalSupply(500L)
                    .setMaxSupply(MAX_SUPPLY)
                    .addAllCustomFees(customFees)
                    .setAdminKey(adminKey)
                    .setKycKey(spec.registry().getKey(KYC_KEY))
                    .setFreezeKey(spec.registry().getKey(FREEZE_KEY))
                    .setWipeKey(spec.registry().getKey(WIPE_KEY))
                    .setSupplyKey(spec.registry().getKey(SUPPLY_KEY))
                    .setFeeScheduleKey(spec.registry().getKey(FEE_SCHEDULE_KEY))
                    .setPauseKey(spec.registry().getKey(PAUSE_KEY))
                    .setDefaultKycStatus(kycDefault);
        }

        private static ArrayList<CustomFee> getExpectedCustomFees(final HapiSpec spec) {
            final var fixedFee = FixedFee.newBuilder().setAmount(500L).build();
            final var customFixedFee = CustomFee.newBuilder()
                    .setFixedFee(fixedFee)
                    .setFeeCollectorAccountId(spec.registry().getAccountID(HTS_COLLECTOR))
                    .build();

            final var firstFraction = Fraction.newBuilder()
                    .setNumerator(NUMERATOR)
                    .setDenominator(DENOMINATOR * 2L)
                    .build();
            final var firstFractionalFee = FractionalFee.newBuilder()
                    .setFractionalAmount(firstFraction)
                    .build();
            final var firstCustomFractionalFee = CustomFee.newBuilder()
                    .setFractionalFee(firstFractionalFee)
                    .setFeeCollectorAccountId(spec.registry().getAccountID(TOKEN_TREASURY))
                    .build();

            final var fraction = Fraction.newBuilder()
                    .setNumerator(NUMERATOR)
                    .setDenominator(DENOMINATOR)
                    .build();
            final var fractionalFee = FractionalFee.newBuilder()
                    .setFractionalAmount(fraction)
                    .setMinimumAmount(MINIMUM_TO_COLLECT)
                    .setMaximumAmount(MAXIMUM_TO_COLLECT)
                    .build();
            final var customFractionalFee = CustomFee.newBuilder()
                    .setFractionalFee(fractionalFee)
                    .setFeeCollectorAccountId(spec.registry().getAccountID(TOKEN_TREASURY))
                    .build();

            final var customFees = new ArrayList<CustomFee>();
            customFees.add(customFixedFee);
            customFees.add(firstCustomFractionalFee);
            customFees.add(customFractionalFee);
            return customFees;
        }
    }

    /**
     * TokenRejectSuite
     */
    @HapiTest
    final Stream<DynamicTest> atomicTokenRejectWorksAndAvoidsCustomFees() {
        final var ALT_TOKEN_TREASURY = "altTokenTreasury";
        final var FUNGIBLE_TOKEN_B = "fungibleTokenB";
        final var NON_FUNGIBLE_TOKEN = "nonFungibleTokenA";
        final long TOTAL_SUPPLY = 1_000;
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(HTS_COLLECTOR).balance(0L).maxAutomaticTokenAssociations(5),
                cryptoCreate(ACCOUNT).maxAutomaticTokenAssociations(5),
                cryptoCreate(ACCOUNT_2).balance(0L).maxAutomaticTokenAssociations(1),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ALT_TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(fixedHbarFee(ONE_MILLION_HBARS, HTS_COLLECTOR))
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(HTS_COLLECTOR, FUNGIBLE_TOKEN),
                tokenCreate(FUNGIBLE_TOKEN_B)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(fixedHtsFee(1000L, FUNGIBLE_TOKEN, HTS_COLLECTOR))
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(fixedHbarFee(ONE_MILLION_HBARS, HTS_COLLECTOR))
                        .treasury(ALT_TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("fire"), copyFromUtf8("goat"))),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_B, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(
                        moving(250L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT),
                        moving(10L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT_2),
                        moving(250L, FUNGIBLE_TOKEN_B).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(ALT_TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(
                                // Try rejecting NON_FUNGIBLE_TOKEN_A with FIXED hBar custom fee
                                tokenReject(ACCOUNT, rejectingNFT(NON_FUNGIBLE_TOKEN, 1L)),
                                // Try rejecting FUNGIBLE_TOKEN_A with FIXED hBar custom fee
                                tokenReject(rejectingToken(FUNGIBLE_TOKEN)).payingWith(ACCOUNT),
                                // Try rejecting FUNGIBLE_TOKEN_B with FIXED hts custom fee
                                tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_B))),

                        // Transaction fails because payer does not have hBars
                        atomicBatchDefaultOperator(tokenReject(ACCOUNT_2, rejectingToken(FUNGIBLE_TOKEN_B))
                                        .payingWith(ACCOUNT_2))
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE))),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L)
                        .hasAccountID(ALT_TOKEN_TREASURY)
                        .hasNoSpender(),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN, 990L),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_B, 1000L),
                // Verify that fee collector account has no tokens and no hBars
                getAccountBalance(HTS_COLLECTOR)
                        .hasTinyBars(0)
                        .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                        .hasTokenBalance(FUNGIBLE_TOKEN_B, 0L));
    }

    /**
     * UpdateTokenFeeScheduleTest
     */
    @Nested
    class UpdateTokenFeeScheduleTest {

        @Contract(contract = "UpdateTokenFeeSchedules", creationGas = 4_000_000L)
        static SpecContract updateTokenFeeSchedules;

        @FungibleToken(
                name = "fungibleToken",
                keys = {SpecTokenKey.ADMIN_KEY, SpecTokenKey.FEE_SCHEDULE_KEY})
        static SpecFungibleToken fungibleToken;

        @Account(name = "feeCollector", tinybarBalance = ONE_HUNDRED_HBARS)
        static SpecAccount feeCollector;

        @HapiTest
        @DisplayName("fungible token with fixed ℏ fee")
        Stream<DynamicTest> atomicUpdateFungibleTokenWithHbarFixedFee() {

            return hapiTest(
                    fungibleToken
                            .authorizeContracts(updateTokenFeeSchedules)
                            .alsoAuthorizing(TokenKeyType.FEE_SCHEDULE_KEY),
                    updateTokenFeeSchedules
                            .call("updateFungibleFixedHbarFee", fungibleToken, 10L, feeCollector)
                            .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR),
                    fungibleToken
                            .getInfo()
                            .andAssert(info -> info.hasCustom(fixedHbarFeeInSchedule(10L, feeCollector.name()))));
        }
    }

    /**
     * WipeTokenAccountPrecompileSuite
     */
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> atomicWipeFungibleTokenScenarios() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> secondAccountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        final var admin = "admin";
        final var contractKey = "contractKey";
        return hapiTest(
                newKeyNamed(WIPE_KEY),
                cryptoCreate(admin),
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(ACCOUNT_2).exposingEvmAddressTo(secondAccountAddress::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .wipeKey(WIPE_KEY)
                        .adminKey(WIPE_KEY)
                        .initialSupply(1_000)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(WIPE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(ACCOUNT_2, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatchDefaultOperator(contractCall(
                                                WIPE_CONTRACT,
                                                WIPE_FUNGIBLE_TOKEN,
                                                vanillaTokenAddress.get(),
                                                accountAddress.get(),
                                                10L)
                                        .signedBy(GENESIS, admin)
                                        .via("accountDoesNotOwnWipeKeyTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        newKeyNamed(contractKey).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, WIPE_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).wipeKey(contractKey).signedByPayerAnd(WIPE_KEY),
                        cryptoUpdate(admin).key(contractKey),
                        atomicBatchDefaultOperator(contractCall(
                                                WIPE_CONTRACT,
                                                WIPE_FUNGIBLE_TOKEN,
                                                vanillaTokenAddress.get(),
                                                accountAddress.get(),
                                                1_000L)
                                        .signedBy(GENESIS, admin)
                                        .alsoSigningWithFullPrefix(admin)
                                        .via("amountLargerThanBalanceTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatchDefaultOperator(contractCall(
                                                WIPE_CONTRACT,
                                                WIPE_FUNGIBLE_TOKEN,
                                                vanillaTokenAddress.get(),
                                                secondAccountAddress.get(),
                                                10L)
                                        .signedBy(GENESIS, admin)
                                        .alsoSigningWithFullPrefix(admin)
                                        .via("accountDoesNotOwnTokensTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatchDefaultOperator(contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        vanillaTokenAddress.get(),
                                        accountAddress.get(),
                                        10L)
                                .alsoSigningWithFullPrefix(admin)
                                .via("wipeFungibleTxn")
                                .gas(GAS_TO_OFFER)),
                        atomicBatchDefaultOperator(contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        vanillaTokenAddress.get(),
                                        accountAddress.get(),
                                        0L)
                                .signedBy(GENESIS, admin)
                                .alsoSigningWithFullPrefix(admin)
                                .via("wipeFungibleTxnWithZeroAmount")
                                .gas(GAS_TO_OFFER)))),
                validatedHtsPrecompileResult(
                        "accountDoesNotOwnWipeKeyTxn", CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE),
                validatedHtsPrecompileResult(
                        "amountLargerThanBalanceTxn", CONTRACT_REVERT_EXECUTED, INVALID_WIPING_AMOUNT),
                validatedHtsPrecompileResult(
                        "accountDoesNotOwnTokensTxn", CONTRACT_REVERT_EXECUTED, INVALID_WIPING_AMOUNT),
                validatedHtsPrecompileResult("wipeFungibleTxnWithZeroAmount", SUCCESS, SUCCESS),
                getTokenInfo(VANILLA_TOKEN).hasTotalSupply(990),
                getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 490));
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
