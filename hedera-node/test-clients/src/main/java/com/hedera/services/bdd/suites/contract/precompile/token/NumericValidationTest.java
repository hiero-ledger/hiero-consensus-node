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
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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

    @FungibleToken(
            name = "NumericValidationTestFT",
            initialSupply = 1_000L,
            maxSupply = 1_200L,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY, WIPE_KEY})
    static SpecFungibleToken fungibleToken;

    @NonFungibleToken(
            numPreMints = 10,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY, WIPE_KEY})
    static SpecNonFungibleToken nftToken;

    private static final AtomicLong NFT_SERIAL_TRACKER = new AtomicLong(1);
    private static BigInteger NFT_SERIAL_FOR_APPROVE;
    private static BigInteger NFT_SERIAL_FOR_WIPE;

    public static final BigInteger NEGATIVE_ONE_BIG_INT =
            new BigInteger(1, Bytes.fromHex("FFFFFFFFFFFFFFFF").toByteArray());
    public static final BigInteger MAX_LONG_PLUS_1_BIG_INT =
            new BigInteger(1, Bytes.fromHex("010000000000000000").toByteArray());

    public record BigIntegerTestCase(BigInteger amount, ResponseCodeEnum status) {}

    public record Int64TestCase(Long amount, ResponseCodeEnum status) {}

    // Big integer test cases for zero, negative, and greater than Long.MAX_VALUE amounts with expected failed status
    public static final List<BigIntegerTestCase> allFail = List.of(
            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED));

    @BeforeAll
    public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
        NFT_SERIAL_FOR_APPROVE = BigInteger.valueOf(NFT_SERIAL_TRACKER.getAndIncrement());
        NFT_SERIAL_FOR_WIPE = BigInteger.valueOf(NFT_SERIAL_TRACKER.getAndIncrement());
        lifecycle.doAdhoc(
                // Authorizations + additional keys
                fungibleToken
                        .authorizeContracts(numericContract, numericContractComplex)
                        .alsoAuthorizing(
                                TokenKeyType.SUPPLY_KEY,
                                TokenKeyType.PAUSE_KEY,
                                TokenKeyType.METADATA_KEY,
                                TokenKeyType.WIPE_KEY),
                nftToken.authorizeContracts(numericContract)
                        .alsoAuthorizing(
                                TokenKeyType.SUPPLY_KEY,
                                TokenKeyType.PAUSE_KEY,
                                TokenKeyType.METADATA_KEY,
                                TokenKeyType.WIPE_KEY),
                // Associations
                numericContract.associateTokens(fungibleToken),
                numericContract.associateTokens(nftToken),
                // Transfers
                // transfer nft to 'numericContract' to be able to 'approve' its transfer from 'numericContract' in
                // ApproveTests
                nftToken.treasury().transferNFTsTo(numericContract, nftToken, NFT_SERIAL_FOR_APPROVE.longValue()),
                nftToken.treasury().transferNFTsTo(numericContract, nftToken, NFT_SERIAL_FOR_WIPE.longValue()));
    }

    /**
     * Validate that functions calls to the HTS system contract that take numeric values handle error cases correctly.
     */
    @Nested
    @DisplayName("Approve functions")
    class ApproveTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT redirect proxy approve(address,uint256)")
        public Stream<DynamicTest> failToApproveViaProxyFungibleToken() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // See CryptoApproveAllowanceHandler.pureChecks
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveRedirect", fungibleToken, numericContractComplex, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT redirect proxy approve(address,uint256)")
        // HTS proxy approve with NFT
        public Stream<DynamicTest> failToApproveViaProxyNft() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER (See AllowanceValidator.validateSerialNums)
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(NFT_SERIAL_FOR_APPROVE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveRedirect", nftToken, numericContractComplex, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 approve(address,address,uint256)")
        public Stream<DynamicTest> failToApproveFungibleToken() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // See CryptoApproveAllowanceHandler.pureChecks
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approve", fungibleToken, numericContractComplex, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 approveNFT(address,address,uint256)")
        public Stream<DynamicTest> failToApproveNft() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER (See AllowanceValidator.validateSerialNums)
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(NFT_SERIAL_FOR_APPROVE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveNFT", nftToken, numericContractComplex, testCase.amount)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }
    }

    @Nested
    @DisplayName("Burn functions")
    class BurnTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 burnToken(address,uint64,int64[])")
        public Stream<DynamicTest> failToBurnFtV1() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("burnTokenV1", fungibleToken, testCase.amount(), new long[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 burnToken(address,uint64,int64[])")
        public Stream<DynamicTest> failToBurnNftV1() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.valueOf(NFT_SERIAL_TRACKER.getAndIncrement()), SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("burnTokenV1", nftToken, testCase.amount(), new long[] {
                                testCase.amount().longValue()
                            })
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 burnToken(address,int64,int64[])")
        public Stream<DynamicTest> failToBurnFtV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            // INVALID_TOKEN_BURN_AMOUNT
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED), new Int64TestCase(0L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("burnTokenV2", fungibleToken, testCase.amount(), new long[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 burnToken(address,int64,int64[])")
        public Stream<DynamicTest> failToBurnNftV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            // INVALID_TOKEN_BURN_AMOUNT
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED),
                            // using '2' here because '1' was already burned by 'failToBurnNftV1'
                            new Int64TestCase(NFT_SERIAL_TRACKER.getAndIncrement(), SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("burnTokenV2", nftToken, testCase.amount(), new long[] {testCase.amount()})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("Mint functions")
    class MintTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 mintToken(address,uint64,bytes[])")
        public Stream<DynamicTest> failToMintFtV1() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("mintTokenV1", fungibleToken, testCase.amount(), new byte[0][0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 mintToken(address,uint64,bytes[])")
        public Stream<DynamicTest> failToMintNftV1() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(
                                    BigInteger.ONE, CONTRACT_REVERT_EXECUTED), // INVALID_TRANSACTION_BODY
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("mintTokenV1", nftToken, testCase.amount(), new byte[][] {{(byte) 0x1}})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 mintToken(address,int64,bytes[])")
        public Stream<DynamicTest> failToMintFTV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED), // INVALID_TOKEN_MINT_AMOUNT
                            new Int64TestCase(0L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("mintTokenV2", fungibleToken, testCase.amount(), new byte[0][0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 mintToken(address,int64,bytes[])")
        public Stream<DynamicTest> failToMintNftV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED), // INVALID_TOKEN_MINT_AMOUNT
                            new Int64TestCase(0L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("mintTokenV2", nftToken, testCase.amount(), new byte[][] {{(byte) 0x1}})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("Wipe functions")
    class WipeTests {

        @HapiTest
        @DisplayName("FT 0x167 wipeTokenAccount(address,address,uint32)")
        public Stream<DynamicTest> failToWipeFtV1() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return hapiTest(numericContract
                    .call("wipeFungibleV1", fungibleToken, numericContract, 0L)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 wipeTokenAccount(address,address,int64)")
        public Stream<DynamicTest> failToWipeFtV2() {
            // only negative numbers are invalid. zero is considered valid and the abi definition will block an attempt
            // to send number greater than Long.MAX_VALUE
            return Stream.of(
                            // INVALID_WIPING_AMOUNT
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED), new Int64TestCase(0L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("wipeFungibleV2", fungibleToken, numericContract, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 wipeTokenAccountNFT(address,address,int64[])")
        public Stream<DynamicTest> failToWipeNft() {
            // only negative number serial numbers are invalid. zero is considered valid and the abi definition will
            // block an attempt to send number greater than Long.MAX_VALUE
            return Stream.of(
                            // INVALID_NFT_ID
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED),
                            // INVALID_NFT_ID
                            new Int64TestCase(0L, CONTRACT_REVERT_EXECUTED),
                            new Int64TestCase(NFT_SERIAL_FOR_WIPE.longValue(), SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("wipeNFT", nftToken, numericContract, new long[] {testCase.amount()})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("Static functions")
    class StaticFunctionsTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT redirect proxy tokenURI(uint256)")
        public Stream<DynamicTest> failTokenURI() {
            return Stream.of(
                            // ERC721Metadata: URI query for nonexistent token
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // ERC721Metadata: URI query for nonexistent token
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("tokenURI", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 getTokenKey(address,uint256)")
        public Stream<DynamicTest> failToGetTokenKeyNft() {
            return Stream.of(
                            // KEY_NOT_PROVIDED
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // KEY_NOT_PROVIDED
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getTokenKey", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 getTokenKey(address,uint256)")
        public Stream<DynamicTest> failToGetTokenKeyFt() {
            return Stream.of(
                            // KEY_NOT_PROVIDED
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // KEY_NOT_PROVIDED
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getTokenKey", fungibleToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 getNonFungibleTokenInfo(address,int64)")
        public Stream<DynamicTest> failToGetNonFungibleTokenInfo() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new Int64TestCase(0L, CONTRACT_REVERT_EXECUTED),
                            new Int64TestCase(1L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getNonFungibleTokenInfo", nftToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 getApproved(address,uint256)")
        public Stream<DynamicTest> failToGetApproved() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getApproved", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT redirect proxy getApproved(uint256)")
        public Stream<DynamicTest> failToGetApprovedERC() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getApprovedERC", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT redirect proxy ownerOf(uint256)")
        public Stream<DynamicTest> failToOwnerOf() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new BigIntegerTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("ownerOf", nftToken, testCase.amount)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status))));
        }
    }

    @Nested
    @DisplayName("HAS functions")
    class HASFunctionsTests {

        @Account(name = "owner", tinybarBalance = ONE_HUNDRED_HBARS)
        static SpecAccount owner;

        @Account(name = "spender")
        static SpecAccount spender;

        @BeforeAll
        public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
            lifecycle.doAdhoc(owner.authorizeContract(numericContract));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("redirect proxy hbarApprove(address,int256)")
        public Stream<DynamicTest> failToApproveHbar() {
            // https://github.com/hiero-ledger/hiero-consensus-node/issues/19704 call from contract not going to
            // HbarApproveTranslator.callFrom
            // see also HbarAllowanceApprovalTest.hrc632ApproveFromEOA test
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, SUCCESS),
                            // NEGATIVE_ALLOWANCE_AMOUNT
                            new BigIntegerTestCase(BigInteger.valueOf(-1), SUCCESS),
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("hbarApproveProxy", owner, spender, testCase.amount())
                            .gas(1_000_000L)
                            .payingWith(owner)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("0x16a hbarApprove(address,address,int256)")
        public Stream<DynamicTest> failToHbarApprove() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // NEGATIVE_ALLOWANCE_AMOUNT
                            new BigIntegerTestCase(BigInteger.valueOf(-1), CONTRACT_REVERT_EXECUTED),
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("hbarApprove", owner, spender, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("Exchange Rate System contract functions")
    class ExchangeRateSystemContractTests {

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("0x168 tinycentsToTinybars(uint256)")
        public Stream<DynamicTest> convertTinycentsToTinybars() {
            // function working with uint256->BigInteger, so all examples as SUCCESS
            return Stream.of(
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, SUCCESS),
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("convertTinycentsToTinybars", testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("0x168 tinybarsToTinycents(uint256)")
        public Stream<DynamicTest> convertTinybarsToTinycents() {
            // function working with uint256->BigInteger, so all examples as SUCCESS
            return Stream.of(
                            new BigIntegerTestCase(MAX_LONG_PLUS_1_BIG_INT, SUCCESS),
                            new BigIntegerTestCase(BigInteger.ZERO, SUCCESS),
                            new BigIntegerTestCase(BigInteger.ONE, SUCCESS))
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
                    alice.transferHBarsTo(numericContractComplex, ONE_HUNDRED_HBARS),
                    numericContractComplex.getBalance().andAssert(balance -> balance.hasTinyBars(ONE_HUNDRED_HBARS)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenWithCustomFees with FixedFee")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFees() {
            // CUSTOM_FEE_MUST_BE_POSITIVE
            return hapiTest(numericContractComplex
                    .call("createFungibleTokenWithCustomFeesFixedFee", 0L)
                    .gas(1_000_000L)
                    .sending(ONE_HUNDRED_HBARS)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using createFungibleTokenWithCustomFeesV3 with Negative FixedFee")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV3NegativeFixedFee() {
            // CUSTOM_FEE_MUST_BE_POSITIVE
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
            return Stream.of(new long[] {Long.MAX_VALUE}, new long[] {0}, new long[] {-1, 1}, new long[] {-1})
                    .flatMap(invalidSerialNumbers -> hapiTest(numericContract
                            .call("updateNFTsMetadata", nftToken, invalidSerialNumbers, "tiger".getBytes())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
        }

        @HapiTest
        @DisplayName("when using updateNFTsMetadata for specific NFT from NFT collection with empty serial numbers")
        public Stream<DynamicTest> failToUpdateNFTsMetadataWithEmptySerialNumbers() {
            return hapiTest(numericContract
                    .call("updateNFTsMetadata", nftToken, new long[] {}, "zebra".getBytes())
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
                            .approveNFTAllowance(
                                    nftToken,
                                    numericContractComplex,
                                    true,
                                    List.of(
                                            NFT_SERIAL_TRACKER.getAndIncrement(),
                                            NFT_SERIAL_TRACKER.getAndIncrement(),
                                            NFT_SERIAL_TRACKER.getAndIncrement())),
                    alice.approveCryptoAllowance(numericContractComplex, ONE_HBAR));
        }

        @HapiTest
        @DisplayName("when using cryptoTransferFungibleV1")
        public Stream<DynamicTest> failToUseCryptoTransferFungibleV1() {
            return hapiTest(numericContractComplex
                    .call("cryptoTransferFungibleV1", fungibleToken, new long[] {-5, -5}, fungibleToken.treasury(), bob)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("when using cryptoTransferV2 for hBar transfer")
        public Stream<DynamicTest> failToUseCryptoTransferV2() {
            return hapiTest(numericContractComplex
                    .call("cryptoTransferV2", new long[] {-5, -5}, alice, bob)
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
                    .call("transferNFTs", nftToken, nftToken.treasury(), alice, new long[] {-1L})
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
