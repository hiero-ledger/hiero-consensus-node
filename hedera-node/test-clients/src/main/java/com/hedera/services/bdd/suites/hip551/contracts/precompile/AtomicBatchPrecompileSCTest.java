// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FREEZE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.KYC_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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
import com.hederahashgraph.api.proto.java.ScheduleID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AtomicBatchPrecompileSCTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";
    public static final BigInteger MAX_LONG_PLUS_1_BIG_INT =
            new BigInteger(1, Bytes.fromHex("010000000000000000").toByteArray());

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled", "true",
                "atomicBatch.maxNumberOfTransactions", "50",
                "contracts.throttle.throttleByGas", "false"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    /**
     * NumericValidationTest
     */
    @HapiTest
    @DisplayName("Atomic FT redirect proxy approve(address,uint256)")
    public Stream<DynamicTest> atomicFailToApproveViaProxyFungibleToken(
            @Contract(contract = "NumericContract", creationGas = 8_000_000L) final SpecContract numericContract,
            @FungibleToken(
                            name = "NumericValidationTestFT",
                            initialSupply = 1_000L,
                            maxSupply = 1_200L,
                            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY, WIPE_KEY})
                    final SpecFungibleToken fungibleToken,
            @Contract(contract = "NumericContractComplex", creationGas = 8_000_000L)
                    final SpecContract numericContractComplex) {
        return hapiTest(
                // Authorizations + additional keys
                fungibleToken
                        .authorizeContracts(numericContract, numericContractComplex)
                        .alsoAuthorizing(
                                TokenKeyType.SUPPLY_KEY,
                                TokenKeyType.PAUSE_KEY,
                                TokenKeyType.METADATA_KEY,
                                TokenKeyType.WIPE_KEY),
                // Associations
                numericContract.associateTokens(fungibleToken),
                numericContract
                        .call("approveRedirect", fungibleToken, numericContractComplex, MAX_LONG_PLUS_1_BIG_INT)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, SUCCESS, INNER_TRANSACTION_FAILED)
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                numericContract
                        .call("approveRedirect", fungibleToken, numericContractComplex, BigInteger.ZERO)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, SUCCESS, INNER_TRANSACTION_FAILED)
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
    }

    /**
     * UpdateTokenMetadataTest
     */
    @HapiTest
    @DisplayName("atomic use updateMetadataForNFTs to correctly update metadata for 1 NFT")
    public Stream<DynamicTest> atomicUsingUpdateMetadataForNFTsWorksForSingleNFT(
            @NonFungibleToken(
                            numPreMints = 10,
                            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
                    final SpecNonFungibleToken nft,
            @Contract(contract = "UpdateTokenMetadata", creationGas = 4_000_000L, variant = VARIANT_16C)
                    final SpecContract updateTokenMetadata) {
        final int serialNumber = 1;
        return hapiTest(
                nft.authorizeContracts(updateTokenMetadata)
                        .alsoAuthorizing(TokenKeyType.METADATA_KEY, TokenKeyType.SUPPLY_KEY),
                nft.getInfo(serialNumber).andAssert(info -> info.hasMetadata(metadata("SN#" + serialNumber))),
                updateTokenMetadata
                        .call("callUpdateNFTsMetadata", nft, new long[] {serialNumber}, "The Lion King".getBytes())
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                nft.getInfo(serialNumber).andAssert(info -> info.hasMetadata(metadata("The Lion King"))));
    }

    /**
     * MiscTokenTest
     */
    @HapiTest
    @DisplayName("cannot transfer value to HTS")
    public Stream<DynamicTest> atomicCannotTransferValueToHts(
            @Contract(contract = "InternalCall", creationGas = 1_000_000L) final SpecContract internalCall,
            @FungibleToken(name = "fungibleToken") final SpecFungibleToken fungibleToken) {
        return hapiTest(internalCall
                .call("isATokenWithCall", fungibleToken)
                .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                .sending(100L)
                .andAssert(txn -> txn.hasKnownStatus(INVALID_CONTRACT_ID)));
    }

    @HapiTest
    @DisplayName("atomic get token type")
    public Stream<DynamicTest> atomicCannotUpdateMissingToken(
            @Contract(contract = "TokenAndTypeCheck", creationGas = 4_000_000L, variant = VARIANT_16C)
                    final SpecContract tokenTypeCheckContract,
            @FungibleToken(
                            name = "immutableToken",
                            keys = {FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
                    final SpecFungibleToken immutableToken) {
        return hapiTest(
                tokenTypeCheckContract.call("getType", immutableToken).wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR));
    }

    /**
     * UnknownFunctionSelectorTest
     */
    @HapiTest
    final Stream<DynamicTest> atomicCallScheduleServiceWithUnknownSelector(
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) final SpecAccount account,
            @Account() final SpecAccount receiver,
            @Contract(contract = "UnknownFunctionSelectorContract", creationGas = 1_500_000)
                    final SpecContract contract) {

        final AtomicReference<ScheduleID> scheduleID = new AtomicReference<>();
        final String schedule = "testSchedule";
        return hapiTest(
                account.getInfo(),
                receiver.getInfo(),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(account.name(), receiver.name(), 1)))
                        .exposingCreatedIdTo(scheduleID::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contract.call(
                                        "callScheduleServiceWithFakeSelector",
                                        mirrorAddrWith(spec, scheduleID.get().getScheduleNum()))
                                .payingWith(account)
                                .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                                .gas(1_000_000)
                                .via("txn"))),
                withOpContext((spec, opLog) -> {
                    final var txn = getTxnRecord("txn");
                    allRunFor(spec, txn);

                    final var res = Bytes32.wrap(Arrays.copyOfRange(
                            txn.getResponseRecord()
                                    .getContractCallResult()
                                    .getContractCallResult()
                                    .toByteArray(),
                            32,
                            64));
                    assertEquals(Bytes32.ZERO, res);
                }));
    }
}
