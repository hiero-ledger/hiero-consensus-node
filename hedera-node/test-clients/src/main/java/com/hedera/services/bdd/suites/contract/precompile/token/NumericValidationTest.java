// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("numericValidation")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class NumericValidationTest {

    public static final long EXPIRY_RENEW = 3_000_000L;
    public static final long EXPIRY_SECOND = 10L;

    @Contract(contract = "NumericContract", creationGas = 8_000_000L)
    static SpecContract numericContract;

    @Contract(contract = "NumericContractComplex", creationGas = 8_000_000L)
    static SpecContract numericContractComplex;

    @Account(maxAutoAssociations = 10, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount alice;

    @Account(maxAutoAssociations = 10, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount bob;

    @FungibleToken(name = "NumericValidationTestFT", initialSupply = 1_000L, maxSupply = 1_200L,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY, WIPE_KEY})
    static SpecFungibleToken fungibleToken;

    @NonFungibleToken(
            numPreMints = 5,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
    static SpecNonFungibleToken nftToken;

    // TODO Glib: what is this mean? all functions are using uint 256/64 (unsigned). So this is really max Ulong
    private static final String NEGATIVE_ONE = "FFFFFFFFFFFFFFFF";
    private static final String MAX_LONG_PLUS_1 = "010000000000000000";
    public static final BigInteger NEGATIVE_ONE_BIG_INT =
            new BigInteger(1, Bytes.fromHex(NEGATIVE_ONE).toByteArray());
    public static final BigInteger MAX_LONG_PLUS_1_BIG_INT =
            new BigInteger(1, Bytes.fromHex(MAX_LONG_PLUS_1).toByteArray());

    public record BigIntegerTestCase(BigInteger amount, ResponseCodeEnum status) {
    }

    // Big integer test cases for negative, and greater than Long.MAX_VALUE amounts with expected failed status, zero expected success
    public static final List<BigIntegerTestCase> zeroSuccess = List.of(
            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS));

    // Big integer test cases for zero, negative, and greater than Long.MAX_VALUE amounts with expected failed status
    public static final List<BigIntegerTestCase> allFail = List.of(
            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED));

    public record Int64TestCase(Long amount, ResponseCodeEnum status) {
    }

    public static final List<Int64TestCase> int64zeroSuccess = List.of(
            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED),
            new Int64TestCase(0L, SUCCESS));

    /**
     * Validate that functions calls to the HTS system contract that take numeric values handle error cases correctly.
     */
    @Nested
    @DisplayName("calls fail to approve functions with invalid amounts")
    class ApproveTests {

        @BeforeAll
        public static void tokensAssociateBeforeAll(final @NonNull TestLifecycle lifecycle) {
            lifecycle.doAdhoc(numericContract.associateTokens(fungibleToken),
                    numericContract.associateTokens(nftToken));
        }

        // TODO Glib: how to check if in smart contract we are getting exactly this failures? there are silent halts now
        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = java.lang.ArithmeticException: BigInteger out of long range
        // 0 = success (See CryptoApproveAllowanceHandler.pureChecks)
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using fungible token via redirect proxy contract")
        public Stream<DynamicTest> failToApproveViaProxyFungibleToken() {
            return zeroSuccess.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveRedirect", fungibleToken, numericContractComplex, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))
                    ));
        }

        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = java.lang.ArithmeticException: BigInteger out of long range
        // 0 = INVALID_TOKEN_NFT_SERIAL_NUMBER (See AllowanceValidator.validateSerialNums)
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using nft via redirect proxy contract")
        public Stream<DynamicTest> failToApproveViaProxyNft() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveRedirect", nftToken, numericContractComplex, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = java.lang.ArithmeticException: BigInteger out of long range
        // 0 = success (See CryptoApproveAllowanceHandler.pureChecks)
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using fungible token hts system contract")
        public Stream<DynamicTest> failToApproveFungibleToken() {
            return zeroSuccess.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approve", fungibleToken, numericContractComplex, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = java.lang.ArithmeticException: BigInteger out of long range
        // 0 = INVALID_TOKEN_NFT_SERIAL_NUMBER (See AllowanceValidator.validateSerialNums)
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using nft hts system contract")
        public Stream<DynamicTest> failToApproveNft() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveNFT", nftToken, numericContractComplex, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }
    }

    @Nested
    @DisplayName("calls fail to burn functions with invalid amounts")
    class BurnTests {

        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = not matching function signature
        // 0 = INVALID_SIGNATURE (See DispatchProcessor.alreadyFailed) // TODO Glib:
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using fungible tokens via the V1 version of the burn function")
        public Stream<DynamicTest> failToBurnFTV1() {
            return Stream.of(NEGATIVE_ONE_BIG_INT, BigInteger.ZERO)
                    .flatMap(amount -> hapiTest(numericContract
                            .call("burnTokenV1", fungibleToken, amount, new long[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = not matching function signature
        // 0 = INVALID_SIGNATURE // TODO Glib:
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using nft via the V1 version of the burn function")
        public Stream<DynamicTest> failToBurnNftV1() {
            return Stream.of(NEGATIVE_ONE_BIG_INT, BigInteger.ZERO)
                    .flatMap(amount -> hapiTest(numericContract
                            .call("burnTokenV1", nftToken, amount, new long[]{1L})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                    ));
        }

        // -1 = INVALID_TOKEN_BURN_AMOUNT
        // 0 = INVALID_SIGNATURE // TODO Glib:
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using fungible tokens via the V2 version of the burn function")
        public Stream<DynamicTest> failToBurnFTV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return int64zeroSuccess.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("burnTokenV2", fungibleToken, testCase.amount(), new long[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))
                    ));
        }

        // -1 = INVALID_TOKEN_BURN_AMOUNT
        // 0 = INVALID_SIGNATURE // TODO Glib:
        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using nft via the V2 version of the burn function")
        public Stream<DynamicTest> failToBurnNftV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return int64zeroSuccess.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("burnTokenV2", nftToken, testCase.amount(), new long[]{1L})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))
                    ));
        }
    }

    @Nested
    @DisplayName("calls fail to mint functions with invalid amounts")
    class MintTests {

        // NEGATIVE_ONE = java.lang.ArithmeticException: BigInteger out of long range
        // MAX_LONG_PLUS_1 = not matching function signature
        // 0 = INVALID_SIGNATURE (See DispatchProcessor.alreadyFailed) // TODO Glib:
        @HapiTest
        @DisplayName("when using fungible tokens via the V1 version of the mint function")
        public Stream<DynamicTest> failToMintFTV1() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return hapiTest(numericContract
                    .call("mintTokenV1", fungibleToken, NEGATIVE_ONE_BIG_INT, new byte[0][0])
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using nft via the V1 version of the mint function")
        public Stream<DynamicTest> failToMintNftV1() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return hapiTest(numericContract
                    .call("mintTokenV1", nftToken, BigInteger.ZERO, new byte[][]{{(byte) 0x1}})
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using fungible tokens via the V2 version of the mint function")
        public Stream<DynamicTest> failToMintFTV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return hapiTest(numericContract
                    .call("mintTokenV2", fungibleToken, -1L, new byte[0][0])
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using nft via the V2 version of the mint function")
        public Stream<DynamicTest> failToMintNftV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return hapiTest(numericContract
                    .call("mintTokenV2", nftToken, 0L, new byte[][]{{(byte) 0x1}})
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }
    }

    @Nested
    @DisplayName("calls fail to wipe functions with invalid amounts")
    class WipeTests {

        /*
         ** We should have a test for wipe function V1 with fungible tokens but all values are either valid or
         ** the abi definition will block an attempt to send
         */

        // -1 = INVALID_WIPING_AMOUNT
        // 0 = INVALID_SIGNATURE // TODO Glib:
        @HapiTest
        @DisplayName("when using fungible tokens via the V2 version of the wipe function")
        public Stream<DynamicTest> failToWipeFTV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return int64zeroSuccess.stream()
                    .flatMap(testCase ->  hapiTest(numericContract
                    .call("wipeFungibleV2", fungibleToken, numericContract, testCase.amount())
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @HapiTest
        @DisplayName("when using nft the wipe function")
        public Stream<DynamicTest> failToWipeNft() {
            // only negative number serial numbers are invalid. zero is considered valid and the abi definition will
            // block an attempt
            // to send number greater than Long.MAX_VALUE
            return hapiTest(numericContract
                    .call("wipeNFT", nftToken, numericContract, new long[]{-1L})
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }
    }

    @Nested
    @DisplayName("calls fail to static functions with invalid amounts")
    class StaticFunctionsTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using tokenURI")
        public Stream<DynamicTest> failTokenURI() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("tokenURI", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using getTokenKey for NFT")
        public Stream<DynamicTest> failToGetTokenKeyNFT() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getTokenKey", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using getTokenKey for Fungible Token")
        public Stream<DynamicTest> failToGetTokenKeyFT() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getTokenKey", fungibleToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @HapiTest
        @DisplayName("when using getNonFungibleTokenInfo")
        public Stream<DynamicTest> failToGetNonFungibleTokenInfo() {
            return hapiTest(numericContract
                    .call("getNonFungibleTokenInfo", nftToken, -1L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using getApproved")
        public Stream<DynamicTest> failToGetApproved() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getApproved", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using getApprovedERC")
        public Stream<DynamicTest> failToGetApprovedERC() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getApprovedERC", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using ownerOf")
        public Stream<DynamicTest> failToOwnerOf() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("ownerOf", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }
    }

    @Nested
    @DisplayName("fail to call HAS functions with invalid amounts")
    class HASFunctionsTests {

        @Account(name = "owner", tinybarBalance = ONE_HUNDRED_HBARS)
        static SpecAccount owner;

        @Account(name = "spender")
        static SpecAccount spender;

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using hbarApprove via redirect proxy contract")
        public Stream<DynamicTest> failToApproveHbar() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("hbarApproveProxy", owner, spender, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using hbarApprove")
        public Stream<DynamicTest> failToHbarApprove() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("hbarApprove", owner, spender, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    // TODO Glib: all zeroNegativeAndGreaterThanLong are success
    @DisplayName("fail to call Exchange Rate System contract functions")
    class ExchangeRateSystemContractTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when converting tinycents to tinybars")
        public Stream<DynamicTest> convertTinycentsToTinybars() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("convertTinycentsToTinybars", testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when converting tinybars to tinycents")
        public Stream<DynamicTest> convertTinybarsToTinycents() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("convertTinybarsToTinycents", testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("calls fail to non-static create/update token functions with invalid values")
    class CreateAndUpdateTokenTests {

        @BeforeAll
        public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
            lifecycle.doAdhoc(
                    fungibleToken.authorizeContracts(numericContractComplex),
                    alice.transferHBarsTo(numericContractComplex, ONE_HUNDRED_HBARS),
                    numericContractComplex.getBalance().andAssert(balance -> balance.hasTinyBars(ONE_HUNDRED_HBARS)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenWithCustomFees with FixedFee")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFees() {
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenWithCustomFeesFixedFee", 0L)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenWithCustomFeesV3 with Negative FixedFee")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV3NegativeFixedFee() {
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenWithCustomFeesV3WithNegativeFixedFee")
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenWithCustomFeesV3 with fractionalFee where maxAmount < minAmount")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV3FractionalFee() {
            final long nominator = 1;
            final long denominator = 1;
            final long maxAmount = Long.MAX_VALUE - 1;
            final long minAmount = Long.MAX_VALUE;
            return hapiTest(numericContractComplex
                    .call(
                            "createFungibleTokenWithCustomFeesV3FractionalFee",
                            nominator,
                            denominator,
                            minAmount,
                            maxAmount)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using createFungibleTokenWithCustomFeesV3 with fractionalFee where denominator is < 0")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV3FractionalFeeNegativeDenominator() {
            final long nominator = 1;
            return Stream.of(-1L, 0L)
                    .flatMap(denominator -> hapiTest(numericContractComplex
                            .call("createFungibleTokenWithCustomFeesV3FractionalFee", nominator, denominator, 10L, 100L)
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using createNonFungibleTokenWithCustomFeesV3 with fractionalFee where denominator is bad")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenWithCustomRoyaltyFeesV3WithBadDenominator() {
            return Stream.of(-1L, 0L)
                    .flatMap(denominator -> hapiTest(numericContractComplex
                            .call(
                                    "createNonFungibleTokenWithCustomRoyaltyFeesV3",
                                    alice.getED25519KeyBytes(),
                                    1L,
                                    denominator,
                                    10L)
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .payingWith(alice)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @HapiTest
        @DisplayName("when using createNonFungibleTokenWithCustomFeesV3 with fractionalFee where amount is negative")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenWithCustomRoyaltyFeesV3WithNegativeAmount() {
            return hapiTest(numericContractComplex
                    .call("createNonFungibleTokenWithCustomRoyaltyFeesV3", alice.getED25519KeyBytes(), 1L, 1L, -1L)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .payingWith(alice)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleToken with bad expiry")
        public Stream<DynamicTest> failToUseCreateFungibleWithBadExpiry() {
            return hapiTest(numericContractComplex
                    .call("createFungibleToken", 0L, 0L, 10000L, BigInteger.TEN, BigInteger.TWO)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.logged().hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleToken with negative decimals")
        public Stream<DynamicTest> failToUseCreateFungible() {
            return hapiTest(numericContractComplex
                    .call(
                            "createFungibleToken",
                            EXPIRY_SECOND,
                            EXPIRY_RENEW,
                            10000L,
                            BigInteger.TEN,
                            NEGATIVE_ONE_BIG_INT)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.logged().hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using createFungibleTokenV2 with negative initial supply")
        public Stream<DynamicTest> failToUseCreateFungibleTokenV2() {
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenV2", 15L, NEGATIVE_ONE_BIG_INT, 10L)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenV3 with negative decimals")
        public Stream<DynamicTest> failToUseCreateFungibleTokenV3() {
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenV3", EXPIRY_SECOND, EXPIRY_RENEW, 10L, 0L, -1)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenV3 with maxSupply < initialSupply")
        public Stream<DynamicTest> failToUseCreateFungibleTokenV3WhenMaxAndInitialSupplyMismatch() {
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenV3", EXPIRY_SECOND, EXPIRY_RENEW, 5L, 10L, 2)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenV3 with negative expiry")
        public Stream<DynamicTest> failToUseCreateFungibleTokenV3WithNegativeExpiry() {
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenV3", EXPIRY_SECOND, -1L, 100L, 10L, 2)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(INVALID_RENEWAL_PERIOD)));
        }

        @HapiTest
        @DisplayName("when using createNonFungibleTokenV2 with negative maxSupply")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenV2() {
            return hapiTest(numericContractComplex
                    .call("createNonFungibleTokenV2", alice.getED25519KeyBytes(), EXPIRY_SECOND, EXPIRY_RENEW, -1L)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .payingWith(alice)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createNonFungibleTokenV3 with negative expiry")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenV3WithNegativeExpiry() {
            return hapiTest(numericContractComplex
                    .call("createNonFungibleTokenV3", alice.getED25519KeyBytes(), EXPIRY_RENEW, -EXPIRY_RENEW, 10L)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .payingWith(alice)
                    .andAssert(txn -> txn.hasKnownStatus(INVALID_RENEWAL_PERIOD)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using updateTokenInfoV2 for fungible token with new maxSupply")
        public Stream<DynamicTest> failToUpdateTokenInfoV2FungibleMaxSupply() {
            // maxSupply cannot be updated using updateTokenInfo.
            // Status is success, because the operation ignores it, so we need verify the maxSupply
            return Stream.of(-1L, 0L, 500L, 1201L)
                    .flatMap(maxSupply -> hapiTest(
                            numericContractComplex
                                    .call("updateTokenInfoV2", fungibleToken, maxSupply)
                                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                            fungibleToken.getInfo().andAssert(info -> info.hasMaxSupply(1200))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using updateTokenInfoV3 for both fungible and nonFungible token")
        public Stream<DynamicTest> failToUpdateTokenInfoV3FungibleAndNft() {
            return Stream.of(fungibleToken, nftToken)
                    .flatMap(testCaseToken -> hapiTest(numericContractComplex
                            .call("updateTokenInfoV3", testCaseToken, -1L, -1L, 5000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using updateNFTsMetadata for specific NFT from NFT collection with invalid serial number")
        public Stream<DynamicTest> failToUpdateNFTsMetadata() {
            return Stream.of(new long[]{Long.MAX_VALUE}, new long[]{0}, new long[]{-1, 1}, new long[]{-1})
                    .flatMap(invalidSerialNumbers -> hapiTest(numericContract
                            .call("updateNFTsMetadata", nftToken, invalidSerialNumbers, "tiger".getBytes())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @HapiTest
        @DisplayName("when using updateNFTsMetadata for specific NFT from NFT collection with empty serial numbers")
        public Stream<DynamicTest> failToUpdateNFTsMetadataWithEmptySerialNumbers() {
            return hapiTest(numericContract
                    .call("updateNFTsMetadata", nftToken, new long[]{}, "zebra".getBytes())
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }
    }

    @Nested
    @DisplayName("calls fail to non-static transfer functions with invalid values")
    class TransfersTests {

        @BeforeAll
        public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
            lifecycle.doAdhoc(
                    fungibleToken.treasury().approveTokenAllowance(fungibleToken, numericContractComplex, 100L),
                    nftToken.treasury()
                            .approveNFTAllowance(nftToken, numericContractComplex, true, List.of(1L, 2L, 3L)),
                    alice.approveCryptoAllowance(numericContractComplex, ONE_HBAR));
        }

        @HapiTest
        @DisplayName("when using cryptoTransferFungibleV1")
        public Stream<DynamicTest> failToUseCryptoTransferFungibleV1() {
            return hapiTest(numericContractComplex
                    .call("cryptoTransferFungibleV1", fungibleToken, new long[]{-5, -5}, fungibleToken.treasury(), bob)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using cryptoTransferV2 for hBar transfer")
        public Stream<DynamicTest> failToUseCryptoTransferV2() {
            return hapiTest(numericContractComplex
                    .call("cryptoTransferV2", new long[]{-5, -5}, alice, bob)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using cryptoTransferNonFungible for nft transfer")
        public Stream<DynamicTest> failToUseCryptoTransferNonFungible() {
            return hapiTest(numericContractComplex
                    .call("cryptoTransferNonFungible", nftToken, nftToken.treasury(), bob, -1L)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using transferNFTs with invalid serial numbers")
        public Stream<DynamicTest> failToUseTransferNFTs() {
            return hapiTest(numericContractComplex
                    .call("transferNFTs", nftToken, nftToken.treasury(), alice, new long[]{-1L})
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using transferToken with negative amount")
        public Stream<DynamicTest> failToUseTransferToken() {
            return hapiTest(numericContractComplex
                    .call("transferTokenTest", fungibleToken, fungibleToken.treasury(), alice, -1L)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using transferTokenERC")
        public Stream<DynamicTest> failToUseTransferTokenERC() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("transferTokenERC", fungibleToken, fungibleToken.treasury(), alice, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @HapiTest
        @DisplayName("when using transferNFT")
        public Stream<DynamicTest> failToUseTransferNFT() {
            return hapiTest(numericContractComplex
                    .call("transferNFTTest", nftToken, nftToken.treasury(), alice, -1L)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using transferFrom")
        public Stream<DynamicTest> failToUseTransferFrom() {
            // note: zero seems to be supported
            return hapiTest(numericContractComplex
                    .call("transferFrom", fungibleToken, fungibleToken.treasury(), alice, NEGATIVE_ONE_BIG_INT)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using transferFromERC")
        public Stream<DynamicTest> failToUseTransferFromERC() {
            return Stream.of(NEGATIVE_ONE_BIG_INT, MAX_LONG_PLUS_1_BIG_INT)
                    .flatMap(amount -> hapiTest(numericContractComplex
                            .call("transferFromERC", fungibleToken, fungibleToken.treasury(), alice, amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("when using transferFromNFT")
        public Stream<DynamicTest> failToUseTransferNFTFrom() {
            return allFail.stream()
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("transferFromNFT", nftToken, nftToken.treasury(), alice, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }
    }
}
