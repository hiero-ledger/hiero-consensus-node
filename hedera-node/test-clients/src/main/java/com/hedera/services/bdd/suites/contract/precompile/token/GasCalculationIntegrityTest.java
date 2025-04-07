// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.ContextRequirement.UPGRADE_FILE_CONTENT;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ObjLongConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Gas Integrity Tests for Token Contracts")
@Disabled
@HapiTestLifecycle
public class GasCalculationIntegrityTest {

    @Contract(contract = "NumericContract", creationGas = 1_000_000L)
    static SpecContract numericContract;

    @Contract(contract = "NumericContractComplex", creationGas = 1_000_000L)
    static SpecContract numericContractComplex;

    @Contract(contract = "TokenInfoContract", creationGas = 1_000_000L)
    static SpecContract tokenInfoContract;

    @Contract(contract = "ERC20Contract", creationGas = 1_000_000L)
    static SpecContract erc20Contract;

    @Account(maxAutoAssociations = 10, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount alice;

    @Account(maxAutoAssociations = 10, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount bob;

    @FungibleToken
    static SpecFungibleToken token;

    @FungibleToken(
            initialSupply = 1_000L,
            maxSupply = 1_200L,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, WIPE_KEY})
    static SpecFungibleToken fungibleToken;

    @FungibleToken(
            initialSupply = 1_000L,
            maxSupply = 1_200L,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, WIPE_KEY})
    static SpecFungibleToken anotherFungibleToken;

    @NonFungibleToken(
            numPreMints = 7,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, WIPE_KEY})
    static SpecNonFungibleToken nft;

    public static final long EXPIRY_RENEW = 3_000_000L;

    private final Stream<RatesProvider> testCases = Stream.of(
            new RatesProvider(30000, 16197),
            new RatesProvider(30000, 359789),
            new RatesProvider(30000, 2888899),
            new RatesProvider(30000, 269100));

    private record RatesProvider(int hBarEquiv, int centEquiv) {}

    @BeforeAll
    public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
        // Fetch exchange rates before tests
        lifecycle.doAdhoc(
                // Save exchange rates before tests
                withOpContext((spec, opLog) -> {
                    var fetch = getFileContents(EXCHANGE_RATES).logged();
                    allRunFor(spec, fetch);
                    final var validRates = fetch.getResponse()
                            .getFileGetContents()
                            .getFileContents()
                            .getContents();
                    spec.registry().saveBytes("originalRates", validRates);
                }),

                // Authorizations
                fungibleToken.authorizeContracts(numericContractComplex),
                anotherFungibleToken.authorizeContracts(numericContractComplex),
                nft.authorizeContracts(numericContractComplex),
                numericContract.associateTokens(fungibleToken, anotherFungibleToken, nft),

                // Approvals
                fungibleToken.treasury().approveTokenAllowance(fungibleToken, numericContractComplex, 1000L),
                anotherFungibleToken
                        .treasury()
                        .approveTokenAllowance(anotherFungibleToken, numericContractComplex, 1000L),
                nft.treasury().approveNFTAllowance(nft, numericContractComplex, true, List.of(1L, 2L, 3L, 4L, 5L)),
                alice.approveCryptoAllowance(numericContractComplex, ONE_HBAR),
                // Transfers
                fungibleToken.treasury().transferUnitsTo(numericContract, 100L, fungibleToken),
                anotherFungibleToken.treasury().transferUnitsTo(numericContract, 100L, anotherFungibleToken),
                nft.treasury().transferNFTsTo(numericContract, nft, 7L),
                alice.transferHBarsTo(numericContractComplex, ONE_HUNDRED_HBARS));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using nft via redirect proxy contract")
    public Stream<DynamicTest> approveViaProxyNft() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContract
                        .call("approveRedirect", nft, bob, BigInteger.valueOf(7))
                        .gas(756_829L)
                        .via("approveRedirectTxn")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("approveRedirectTxn").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using fungible token hts system contract")
    public Stream<DynamicTest> approveFungibleToken() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContract
                        .call("approve", fungibleToken, alice, BigInteger.TWO)
                        .gas(742_977L)
                        .via("approveTxn")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("approveTxn").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using createFungibleTokenWithCustomFeesV3 with fractionalFee")
    public Stream<DynamicTest> createFungibleTokenWithCustomFeesV3FractionalFee() {
        final long nominator = 1;
        final long denominator = 1;
        final long maxAmount = 500;
        final long minAmount = 100;
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call(
                                "createFungibleTokenWithCustomFeesV3FractionalFee",
                                nominator,
                                denominator,
                                minAmount,
                                maxAmount)
                        .gas(165_038L)
                        .sending(THOUSAND_HBAR)
                        .via("createWithCustomFeeFractional")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("createWithCustomFeeFractional").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using createNonFungibleTokenWithCustomFeesV3 with fractionalFee")
    public Stream<DynamicTest> createNonFungibleTokenWithCustomRoyaltyFeesV3() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("createNonFungibleTokenWithCustomRoyaltyFeesV3", alice.getED25519KeyBytes(), 1L, 2L, 10L)
                        .gas(169_584L)
                        .sending(THOUSAND_HBAR)
                        .payingWith(alice)
                        .via("createWithCustomFeeRoyalty")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("createWithCustomFeeRoyalty").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using createFungibleToken")
    public Stream<DynamicTest> createFungible() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("createFungibleToken", EXPIRY_RENEW, EXPIRY_RENEW, 10000L, BigInteger.TEN, BigInteger.TWO)
                        .gas(165_800L)
                        .sending(THOUSAND_HBAR)
                        .via("createFungibleToken")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("createFungibleToken").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using createNonFungibleTokenV3")
    public Stream<DynamicTest> createNonFungibleTokenV3() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("createNonFungibleTokenV3", alice.getED25519KeyBytes(), EXPIRY_RENEW, EXPIRY_RENEW, 10L)
                        .gas(166_944L)
                        .sending(THOUSAND_HBAR)
                        .payingWith(alice)
                        .via("createNonFungibleTokenV3")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("createNonFungibleTokenV3").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using cryptoTransferV2 for hBar transfer")
    public Stream<DynamicTest> useCryptoTransferV2() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("cryptoTransferV2", new long[] {-5, 5}, alice, bob)
                        .gas(33_404L)
                        .via("cryptoTransferV2")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("cryptoTransferV2").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using cryptoTransferFungibleV1 with internal auto associate")
    public Stream<DynamicTest> useCryptoTransferFungibleV1() {
        final AtomicLong autoAssociateGasUsed = new AtomicLong();
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call(
                                "cryptoTransferFungibleV1",
                                fungibleToken,
                                new long[] {-5, 5},
                                fungibleToken.treasury(),
                                bob)
                        .via("cryptoTransferFungibleV1")
                        .gas(763_580L)
                        .andAssert(
                                txn -> txn.exposingGasTo(autoAssociationGasAssertion(autoAssociateGasUsed, gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("cryptoTransferFungibleV1").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using cryptoTransferNonFungible with internal auto associate")
    public Stream<DynamicTest> useCryptoTransferNonFungible() {
        final AtomicLong autoAssociateGasUsed = new AtomicLong();
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("cryptoTransferNonFungible", nft, nft.treasury(), bob, 1L)
                        .gas(761_170L)
                        .via("cryptoTransferNonFungible")
                        .andAssert(
                                txn -> txn.exposingGasTo(autoAssociationGasAssertion(autoAssociateGasUsed, gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("cryptoTransferNonFungible").logged(),
                bob.transferNFTsTo(nft.treasury(), nft, 1L)));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using transferNFTs with internal auto associate")
    public Stream<DynamicTest> useTransferNFTs() {
        final AtomicLong autoAssociateGasUsed = new AtomicLong();
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("transferNFTs", nft, nft.treasury(), alice, new long[] {4L})
                        .via("transferNFTs")
                        .gas(761_619L)
                        .andAssert(
                                txn -> txn.exposingGasTo(autoAssociationGasAssertion(autoAssociateGasUsed, gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("transferNFTs").logged(),
                alice.transferNFTsTo(nft.treasury(), nft, 4L)));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using transferToken with internal auto associate")
    public Stream<DynamicTest> useTransferToken() {
        final AtomicLong autoAssociateGasUsed = new AtomicLong();
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("transferTokenTest", anotherFungibleToken, anotherFungibleToken.treasury(), alice, 1L)
                        .via("transferTokenTest")
                        .gas(758_668L)
                        .andAssert(
                                txn -> txn.exposingGasTo(autoAssociationGasAssertion(autoAssociateGasUsed, gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("transferTokenTest").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using transferNFT")
    public Stream<DynamicTest> useTransferNFT() {
        final AtomicLong gasUsed = new AtomicLong();
        // Cannot be tested directly as it requires associate from previous test
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("transferNFTTest", nft, nft.treasury(), alice, 3L)
                        .via("transferNFTTest")
                        .gas(42_335L)
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("transferNFTTest").logged(),
                alice.transferNFTsTo(nft.treasury(), nft, 3L)));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using transferFrom")
    public Stream<DynamicTest> useTransferFrom() {
        final AtomicLong gasUsed = new AtomicLong();
        // Cannot be tested directly as it requires associate from previous test
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("transferFrom", fungibleToken, fungibleToken.treasury(), alice, BigInteger.ONE)
                        .via("transferFrom")
                        .gas(42_364L)
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("transferFrom").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using transferFromERC")
    public Stream<DynamicTest> useTransferFromERC() {
        final AtomicLong gasUsed = new AtomicLong();
        // Cannot be tested directly as it requires associate from previous test
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("transferFromERC", fungibleToken, fungibleToken.treasury(), alice, BigInteger.ONE)
                        .via("transferFromERC")
                        .gas(44_900L)
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("transferFromERC").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("when using transferFromNFT")
    public Stream<DynamicTest> useTransferNFTFrom() {
        final AtomicLong gasUsed = new AtomicLong();
        // Cannot be tested directly as it requires associate from previous test
        return testCases.flatMap(rates -> hapiTest(
                updateRates(rates.hBarEquiv, rates.centEquiv),
                numericContractComplex
                        .call("transferFromNFT", nft, nft.treasury(), alice, BigInteger.TWO)
                        .via("transferFromNFT")
                        .gas(42_363L)
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                getTxnRecord("transferFromNFT").logged(),
                restoreOriginalRates(),
                alice.transferNFTsTo(nft.treasury(), nft, 2L)));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("for token info call")
    public Stream<DynamicTest> checkTokenGetInfoGas() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(ratesProvider -> hapiTest(
                updateRates(ratesProvider.hBarEquiv, ratesProvider.centEquiv),
                tokenInfoContract
                        .call("getInformationForToken", token)
                        .gas(78_905L)
                        .via("tokenInfo")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("tokenInfo").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("for token custom fees call")
    public Stream<DynamicTest> checkTokenGetCustomFeesGas() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(ratesProvider -> hapiTest(
                updateRates(ratesProvider.hBarEquiv, ratesProvider.centEquiv),
                tokenInfoContract
                        .call("getCustomFeesForToken", token)
                        .gas(31_521L)
                        .via("customFees")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("customFees").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("for token name call")
    public Stream<DynamicTest> checkErc20Name() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(ratesProvider -> hapiTest(
                updateRates(ratesProvider.hBarEquiv, ratesProvider.centEquiv),
                erc20Contract
                        .call("name", token)
                        .gas(30_307L)
                        .via("name")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("name").logged()));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    @DisplayName("for token balance of call")
    public Stream<DynamicTest> checkErc20BalanceOf() {
        final AtomicLong gasUsed = new AtomicLong();
        return testCases.flatMap(ratesProvider -> hapiTest(
                updateRates(ratesProvider.hBarEquiv, ratesProvider.centEquiv),
                erc20Contract
                        .call("balanceOf", token, alice)
                        .gas(30_174L)
                        .via("balance")
                        .andAssert(txn -> txn.exposingGasTo(constantGasAssertion(gasUsed))),
                restoreOriginalRates(),
                getTxnRecord("balance").logged()));
    }

    private static HapiFileUpdate updateRates(final int hBarEquiv, final int centEquiv) {
        return fileUpdate(EXCHANGE_RATES)
                .contents(spec ->
                        spec.ratesProvider().rateSetWith(hBarEquiv, centEquiv).toByteString());
    }

    private static CustomSpecAssert restoreOriginalRates() {
        return withOpContext((spec, opLog) -> {
            var resetRatesOp = fileUpdate(EXCHANGE_RATES)
                    .contents(contents -> ByteString.copyFrom(spec.registry().getBytes("originalRates")));
            allRunFor(spec, resetRatesOp);
        });
    }

    public static ObjLongConsumer<ResponseCodeEnum> constantGasAssertion(AtomicLong gasUsed) {
        return (status, gas) -> {
            if (gasUsed.get() == 0) {
                gasUsed.set(gas);
            }
            assertEquals(gasUsed.get(), gas, "Gas used should be constant!");
        };
    }

    public static ObjLongConsumer<ResponseCodeEnum> autoAssociationGasAssertion(
            AtomicLong gasWithAutoAssociation, AtomicLong gasUsed) {
        return (status, gas) -> {
            // first iteration always sets the gasWithAutoAssociation
            if (gasWithAutoAssociation.get() == 0) {
                gasWithAutoAssociation.set(gas);
            } else {
                // next iterations should have less gas and should be constant
                assertTrue(gasWithAutoAssociation.get() > gas, "Gas used should be constant!");
                constantGasAssertion(gasUsed).accept(status, gas);
            }
        };
    }
}
