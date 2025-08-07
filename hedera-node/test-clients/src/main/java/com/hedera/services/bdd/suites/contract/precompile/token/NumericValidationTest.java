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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
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
import java.util.ArrayList;
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

    public record UintTestCase(BigInteger amount, ResponseCodeEnum status) {}

    public record Int64TestCase(Long amount, ResponseCodeEnum status) {}

    public record TestCase(ResponseCodeEnum status, Object... values) {}

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
                nftToken.authorizeContracts(numericContract, numericContractComplex)
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // See CryptoApproveAllowanceHandler.pureChecks
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER (See AllowanceValidator.validateSerialNums)
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(NFT_SERIAL_FOR_APPROVE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveRedirect", nftToken, numericContractComplex, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 approve(address,address,uint)")
        public Stream<DynamicTest> failToApproveFungibleToken() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // See CryptoApproveAllowanceHandler.pureChecks
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approve", fungibleToken, numericContractComplex, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 approveNFT(address,address,uint256)")
        public Stream<DynamicTest> failToApproveNft() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER (See AllowanceValidator.validateSerialNums)
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(NFT_SERIAL_FOR_APPROVE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("approveNFT", nftToken, numericContractComplex, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
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
                            new UintTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
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
                            new UintTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.valueOf(NFT_SERIAL_TRACKER.getAndIncrement()), SUCCESS))
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
                            new UintTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
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
                            new UintTestCase(NEGATIVE_ONE_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TRANSACTION_BODY
                            new UintTestCase(BigInteger.ONE, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
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
                            // INVALID_TOKEN_MINT_AMOUNT
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED), new Int64TestCase(0L, SUCCESS))
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
                            // INVALID_TOKEN_MINT_AMOUNT
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED), new Int64TestCase(0L, SUCCESS))
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // ERC721Metadata: URI query for nonexistent token
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("tokenURI", nftToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 getTokenKey(address,uint)")
        public Stream<DynamicTest> failToGetTokenKeyNft() {
            return Stream.of(
                            // KEY_NOT_PROVIDED
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // KEY_NOT_PROVIDED
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getTokenKey", nftToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 getTokenKey(address,uint256)")
        public Stream<DynamicTest> failToGetTokenKeyFt() {
            return Stream.of(
                            // KEY_NOT_PROVIDED
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // KEY_NOT_PROVIDED
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getTokenKey", fungibleToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getApproved", nftToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT redirect proxy getApproved(uint256)")
        public Stream<DynamicTest> failToGetApprovedERC() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("getApprovedERC", nftToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT redirect proxy ownerOf(uint256)")
        public Stream<DynamicTest> failToOwnerOf() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("ownerOf", nftToken, testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, SUCCESS),
                            // NEGATIVE_ALLOWANCE_AMOUNT
                            new UintTestCase(BigInteger.valueOf(-1), SUCCESS),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // NEGATIVE_ALLOWANCE_AMOUNT
                            new UintTestCase(BigInteger.valueOf(-1), CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
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
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, SUCCESS),
                            new UintTestCase(BigInteger.ZERO, SUCCESS),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("convertTinycentsToTinybars", testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("0x168 tinybarsToTinycents(uint256)")
        public Stream<DynamicTest> convertTinybarsToTinycents() {
            // function working with uint256->BigInteger, so all examples as SUCCESS
            return Stream.of(
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, SUCCESS),
                            new UintTestCase(BigInteger.ZERO, SUCCESS),
                            new UintTestCase(BigInteger.ONE, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContract
                            .call("convertTinybarsToTinycents", testCase.amount())
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("HTS complex non-static functions")
    class CreateAndUpdateTokenTests {

        private static long FUNGIBLE_TOKEN_EXPIRY_SECONDS;
        private static long NFT_TOKEN_EXPIRY_SECONDS;

        @BeforeAll
        public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
            lifecycle.doAdhoc(
                    alice.transferHBarsTo(numericContractComplex, ONE_HUNDRED_HBARS),
                    numericContractComplex.getBalance().andAssert(balance -> balance.hasTinyBars(ONE_HUNDRED_HBARS)),
                    // get fungibleToken expiry seconds for updateTokenInfo test
                    fungibleToken
                            .getInfo()
                            .andGet(e -> FUNGIBLE_TOKEN_EXPIRY_SECONDS =
                                    e.getExpiry().getSeconds()),
                    // get nftToken expiry seconds for updateTokenInfo test
                    nftToken.getInfo()
                            .andGet(e ->
                                    NFT_TOKEN_EXPIRY_SECONDS = e.getExpiry().getSeconds()));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleTokenWithCustomFees HEDERA_TOKEN_V1")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFees() {
            return Stream.of(
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new Int64TestCase(0L, CONTRACT_REVERT_EXECUTED), new Int64TestCase(1L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("createFungibleTokenWithCustomFeesFixedFee", testCase.amount())
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleTokenWithCustomFees HEDERA_TOKEN_V1 FractionalFee")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV1FractionalFee() {
            return Stream.of(
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 1L, 100L, 10000L),
                            // FRACTION_DIVIDES_BY_ZERO
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 0L, 100L, 10000L),
                            // FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 1L, 100L, 10L),
                            new TestCase(SUCCESS, 1L, 1L, 100L, 10000L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createFungibleTokenWithCustomFeesFractionalFee",
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2],
                                    testCase.values()[3])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleTokenWithCustomFees HEDERA_TOKEN_V3")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV3FixedFee() {
            return Stream.of(
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new Int64TestCase(0L, CONTRACT_REVERT_EXECUTED),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new Int64TestCase(-1L, CONTRACT_REVERT_EXECUTED),
                            new Int64TestCase(1L, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("createFungibleTokenWithCustomFeesV3FixedFee", testCase.amount())
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleTokenWithCustomFees HEDERA_TOKEN_V3 FractionalFee")
        public Stream<DynamicTest> failToUseCreateFungibleTokenWithCustomFeesV3FractionalFee() {
            return Stream.of(
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L, 1L, 100L, 10000L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 1L, 100L, 10000L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, -1L, 100L, 10000L),
                            // FRACTION_DIVIDES_BY_ZERO
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 0L, 100L, 10000L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 1L, 100L, -1L),
                            // FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 1L, 100L, 10L),
                            new TestCase(SUCCESS, 1L, 1L, 100L, 10000L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createFungibleTokenWithCustomFeesV3FractionalFee",
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2],
                                    testCase.values()[3])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createNonFungibleTokenWithCustomFees HEDERA_TOKEN_V3 RoyaltyFee")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenWithCustomFeesV3RoyaltyFees() {
            return Stream.of(
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L, 1L, 10L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 1L, 10L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, -1L, 10L),
                            // FRACTION_DIVIDES_BY_ZERO
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 0L, 10L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 1L, -1L),
                            // CUSTOM_FEE_MUST_BE_POSITIVE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1L, 1L, 0L),
                            new TestCase(SUCCESS, 1L, 1L, 100L, 10000L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createNonFungibleTokenWithCustomFeesV3RoyaltyFees",
                                    alice.getED25519KeyBytes(),
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .payingWith(alice)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleToken V1")
        public Stream<DynamicTest> failToUseCreateFungibleToken() {
            return Stream.of(
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 10000L, BigInteger.TEN, BigInteger.TWO),
                            // INVALID_RENEWAL_PERIOD _expiryRenew < autoRenewPeriodMinDuration
                            new TestCase(CONTRACT_REVERT_EXECUTED, 2_000_000L, 10000L, BigInteger.TEN, BigInteger.TWO),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 0L, BigInteger.TEN, BigInteger.TWO),
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new TestCase(
                                    CONTRACT_REVERT_EXECUTED,
                                    3_000_000L,
                                    10000L,
                                    MAX_LONG_PLUS_1_BIG_INT,
                                    BigInteger.TWO),
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new TestCase(
                                    CONTRACT_REVERT_EXECUTED, 3_000_000L, 10000L, BigInteger.TEN, NEGATIVE_ONE_BIG_INT),
                            new TestCase(SUCCESS, 3_000_000L, 10000L, BigInteger.TEN, BigInteger.TWO))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createFungibleToken",
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2],
                                    testCase.values()[3])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.logged().hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleToken V2")
        public Stream<DynamicTest> failToUseCreateFungibleTokenV2() {
            return Stream.of(
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 10000L, BigInteger.TEN, 2L),
                            // INVALID_RENEWAL_PERIOD _expiryRenew < autoRenewPeriodMinDuration
                            new TestCase(CONTRACT_REVERT_EXECUTED, 2_000_000L, 10000L, BigInteger.TEN, 2L),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 0L, BigInteger.TEN, 2L),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, -1L, BigInteger.TEN, 2L),
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 10000L, NEGATIVE_ONE_BIG_INT, 2L),
                            new TestCase(SUCCESS, 3_000_000L, 10000L, BigInteger.TEN, 2L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createFungibleTokenV2",
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2],
                                    testCase.values()[3])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 createFungibleToken V3")
        public Stream<DynamicTest> failToUseCreateFungibleTokenV3() {
            return Stream.of(
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 10000L, 10L, 2),
                            // INVALID_RENEWAL_PERIOD _expiryRenew < autoRenewPeriodMinDuration
                            new TestCase(CONTRACT_REVERT_EXECUTED, 2_000_000L, 10000L, 10L, 2),
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L, 10000L, 10L, 2),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 0L, 10L, 2),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, -1L, 10L, 2),
                            // INVALID_TOKEN_INITIAL_SUPPLY initialSupply < maxSupply
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 5L, 10L, 2),
                            // INVALID_TOKEN_INITIAL_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 10000L, -1L, 2),
                            // INVALID_TOKEN_DECIMALS
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 10000L, 10L, -1),
                            new TestCase(SUCCESS, 3_000_000L, 10000L, 10L, 2))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createFungibleTokenV3",
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2],
                                    testCase.values()[3])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 createNonFungibleToken V2")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenV2() {
            return Stream.of(
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 10L),
                            // INVALID_RENEWAL_PERIOD _expiryRenew < autoRenewPeriodMinDuration
                            new TestCase(CONTRACT_REVERT_EXECUTED, 2_000_000L, 10L),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 0L),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, -1L),
                            new TestCase(SUCCESS, 3_000_000L, 10L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createNonFungibleTokenV2",
                                    alice.getED25519KeyBytes(),
                                    testCase.values()[0],
                                    testCase.values()[1])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .payingWith(alice)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 createNonFungibleToken V3")
        public Stream<DynamicTest> failToUseCreateNonFungibleTokenV3() {
            return Stream.of(
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L, 10L),
                            // INVALID_RENEWAL_PERIOD _expiryRenew < autoRenewPeriodMinDuration
                            new TestCase(CONTRACT_REVERT_EXECUTED, 2_000_000L, 10L),
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L, 10L),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, 0L),
                            // INVALID_TOKEN_MAX_SUPPLY
                            new TestCase(CONTRACT_REVERT_EXECUTED, 3_000_000L, -1L),
                            new TestCase(SUCCESS, 3_000_000L, 10L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "createNonFungibleTokenV3",
                                    alice.getED25519KeyBytes(),
                                    testCase.values()[0],
                                    testCase.values()[1])
                            .gas(1_000_000L)
                            .sending(ONE_HUNDRED_HBARS)
                            .payingWith(alice)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 updateTokenInfo V2")
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
        @DisplayName("FT 0x167 updateTokenInfo V3")
        public Stream<DynamicTest> failToUpdateTokenInfoV3Fungible() {
            return Stream.of(
                            // INVALID_EXPIRATION_TIME
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L, 3_000_000L, 10L),
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, FUNGIBLE_TOKEN_EXPIRY_SECONDS, 0L, 10L),
                            // AUTORENEW_DURATION_NOT_IN_RANGE
                            new TestCase(CONTRACT_REVERT_EXECUTED, FUNGIBLE_TOKEN_EXPIRY_SECONDS, 2_000_000L, 10L),
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, FUNGIBLE_TOKEN_EXPIRY_SECONDS, -1L, 10L),
                            // maxSupply cannot be updated using updateTokenInfo.
                            // Status is success, because the operation ignores it, so we need verify the maxSupply
                            new TestCase(SUCCESS, FUNGIBLE_TOKEN_EXPIRY_SECONDS, 3_000_000L, 0L),
                            new TestCase(SUCCESS, FUNGIBLE_TOKEN_EXPIRY_SECONDS, 3_000_000L, -1L),
                            new TestCase(SUCCESS, FUNGIBLE_TOKEN_EXPIRY_SECONDS, 3_000_000L, 10L))
                    .flatMap(testCase -> {
                        List<SpecOperation> ops = new ArrayList<>();
                        ops.add(numericContractComplex
                                .call(
                                        "updateTokenInfoV3",
                                        fungibleToken,
                                        testCase.values()[0],
                                        testCase.values()[1],
                                        testCase.values()[2])
                                .andAssert(txn -> txn.hasKnownStatus(testCase.status())));
                        if (SUCCESS.equals(testCase.status())) {
                            // if we are expecting SUCCESS updateTokenInfoV3 then check that MaxSupply was not changed
                            ops.add(fungibleToken.getInfo().andAssert(info -> info.hasMaxSupply(1200)));
                        }
                        return hapiTest(ops.toArray(SpecOperation[]::new));
                    });
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 updateTokenInfo V3")
        public Stream<DynamicTest> failToUpdateTokenInfoV3Nft() {
            return Stream.of(
                            // INVALID_EXPIRATION_TIME
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L, 3_000_000L, 0L),
                            // AUTORENEW_DURATION_NOT_IN_RANGE
                            new TestCase(CONTRACT_REVERT_EXECUTED, NFT_TOKEN_EXPIRY_SECONDS, 2_000_000L, 0L),
                            // INVALID_RENEWAL_PERIOD
                            new TestCase(CONTRACT_REVERT_EXECUTED, NFT_TOKEN_EXPIRY_SECONDS, -1L, 0L),
                            new TestCase(SUCCESS, NFT_TOKEN_EXPIRY_SECONDS, 3_000_000L, 0L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "updateTokenInfoV3",
                                    nftToken,
                                    testCase.values()[0],
                                    testCase.values()[1],
                                    testCase.values()[2])
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }

    @Nested
    @DisplayName("HTS non-static transfer functions")
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

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 cryptoTransfer V1")
        public Stream<DynamicTest> failToUseCryptoTransferFungibleV1() {
            return Stream.of(
                            // TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN
                            new TestCase(CONTRACT_REVERT_EXECUTED, new long[] {0, 1}, bob),
                            // TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN
                            new TestCase(CONTRACT_REVERT_EXECUTED, new long[] {-1, 0}, bob),
                            // TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN
                            new TestCase(CONTRACT_REVERT_EXECUTED, new long[] {-5, -5}, bob),
                            new TestCase(SUCCESS, new long[] {-5, 5}, bob))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "cryptoTransferFungibleV1",
                                    fungibleToken,
                                    testCase.values()[0],
                                    fungibleToken.treasury(),
                                    testCase.values()[1])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 cryptoTransfer V2 hbar")
        public Stream<DynamicTest> failToUseCryptoTransferV2() {
            return Stream.of(
                            // INVALID_ACCOUNT_AMOUNTS
                            new TestCase(CONTRACT_REVERT_EXECUTED, new long[] {0, 1}, alice, bob),
                            // INVALID_ACCOUNT_AMOUNTS
                            new TestCase(CONTRACT_REVERT_EXECUTED, new long[] {-1, 0}, alice, bob),
                            // INVALID_ACCOUNT_AMOUNTS
                            new TestCase(CONTRACT_REVERT_EXECUTED, new long[] {-5, -5}, alice, bob),
                            new TestCase(SUCCESS, new long[] {-5, 5}, alice, bob))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("cryptoTransferV2", testCase.values()[0], testCase.values()[1], testCase.values()[2])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 cryptoTransfer V3")
        public Stream<DynamicTest> failToUseCryptoTransferNonFungible() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new TestCase(CONTRACT_REVERT_EXECUTED, 0L),
                            // INVALID_NFT_ID
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1_000_000L),
                            new TestCase(SUCCESS, NFT_SERIAL_TRACKER.getAndIncrement()))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("cryptoTransferNonFungible", nftToken, nftToken.treasury(), bob, testCase.values()[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT 0x167 transferToken(address,address,address,int64)")
        public Stream<DynamicTest> failToUseTransferToken() {
            return Stream.of(
                            // INVALID_TRANSACTION_BODY
                            new TestCase(CONTRACT_REVERT_EXECUTED, -1L),
                            // AMOUNT_EXCEEDS_ALLOWANCE
                            new TestCase(CONTRACT_REVERT_EXECUTED, 1_000_000L),
                            new TestCase(SUCCESS, 10L))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "transferTokenTest",
                                    fungibleToken,
                                    fungibleToken.treasury(),
                                    alice,
                                    testCase.values()[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 transferNFTs(address,address[],address[],int64[])")
        public Stream<DynamicTest> failToUseTransferNFTs() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new TestCase(CONTRACT_REVERT_EXECUTED, alice, new long[] {-1L}),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new TestCase(CONTRACT_REVERT_EXECUTED, alice, new long[] {0L}),
                            // INVALID_NFT_ID
                            new TestCase(CONTRACT_REVERT_EXECUTED, alice, new long[] {1_000_000L}),
                            new TestCase(SUCCESS, alice, new long[] {NFT_SERIAL_TRACKER.getAndIncrement()}))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "transferNFTs",
                                    nftToken,
                                    nftToken.treasury(),
                                    testCase.values()[0],
                                    testCase.values()[1])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 transferNFT(address,address,address,int64)")
        public Stream<DynamicTest> failToUseTransferNFT() {
            return Stream.of(
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new TestCase(CONTRACT_REVERT_EXECUTED, alice, -1L),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new TestCase(CONTRACT_REVERT_EXECUTED, alice, 0L),
                            // INVALID_NFT_ID
                            new TestCase(CONTRACT_REVERT_EXECUTED, alice, 1_000_000L),
                            new TestCase(SUCCESS, alice, NFT_SERIAL_TRACKER.getAndIncrement()))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call(
                                    "transferNFTTest",
                                    nftToken,
                                    nftToken.treasury(),
                                    testCase.values()[0],
                                    testCase.values()[1])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @HapiTest
        @DisplayName("FT 0x167 transferFrom(address,address,address,uint256)")
        public Stream<DynamicTest> failToUseTransferFrom() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("transferFrom", fungibleToken, fungibleToken.treasury(), alice, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("FT redirect proxy transferFrom(address,address,uint256)")
        public Stream<DynamicTest> failToUseTransferFromERC() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.ZERO, SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("transferFromERC", fungibleToken, fungibleToken.treasury(), alice, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }

        @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("NFT 0x167 transferFromNFT(address,address,address,uint256)")
        public Stream<DynamicTest> failToUseTransferNFTFrom() {
            return Stream.of(
                            // java.lang.ArithmeticException: BigInteger out of long range
                            new UintTestCase(MAX_LONG_PLUS_1_BIG_INT, CONTRACT_REVERT_EXECUTED),
                            // INVALID_TOKEN_NFT_SERIAL_NUMBER
                            new UintTestCase(BigInteger.ZERO, CONTRACT_REVERT_EXECUTED),
                            // INVALID_NFT_ID
                            new UintTestCase(BigInteger.valueOf(1_000_000), CONTRACT_REVERT_EXECUTED),
                            new UintTestCase(BigInteger.valueOf(NFT_SERIAL_TRACKER.getAndIncrement()), SUCCESS))
                    .flatMap(testCase -> hapiTest(numericContractComplex
                            .call("transferFromNFT", nftToken, nftToken.treasury(), alice, testCase.amount())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(testCase.status()))));
        }
    }
}
