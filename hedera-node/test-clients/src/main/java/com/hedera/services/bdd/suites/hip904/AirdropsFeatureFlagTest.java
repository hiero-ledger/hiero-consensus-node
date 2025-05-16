// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiPropertySource.asSolidityAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.crypto.CryptoDeleteSuite.TREASURY;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.FUNGIBLE_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests expected behavior when the {@code "entities.unlimitedAutoAssociationsEnabled"} feature flag is toggled
 * from off to on for <a href="https://hips.hedera.com/hip/hip-904">HIP-904, "Frictionless Airdrops"</a>.
 */
public class AirdropsFeatureFlagTest {
    @LeakyHapiTest(
            requirement = PROPERTY_OVERRIDES,
            overrides = {"entities.unlimitedAutoAssociationsEnabled"})
    final Stream<DynamicTest> createHollowAccountOnDeletedAliasViaHBARTransferAndCompleteIt() {
        final var hollowAccountKey = "hollowAccountKey";
        final AtomicReference<byte[]> treasuryAlias = new AtomicReference<>();
        final AtomicReference<byte[]> hollowAccountAlias = new AtomicReference<>();
        final var transferHBARSToHollowAccountTxn = "transferHBARSToHollowAccountTxn";
        return hapiTest(
                overriding("entities.unlimitedAutoAssociationsEnabled", "false"),
                newKeyNamed(hollowAccountKey).shape(SECP_256K1_SHAPE),
                cryptoCreate(TREASURY).balance(10_000 * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var treasuryAccountId = registry.getAccountID(TREASURY);
                    treasuryAlias.set(asSolidityAddress(treasuryAccountId));
                    // Save the alias for the hollow account
                    final var ecdsaKey = spec.registry()
                            .getKey(hollowAccountKey)
                            .getECDSASecp256K1()
                            .toByteArray();
                    hollowAccountAlias.set(recoverAddressFromPubKey(ecdsaKey));
                }),
                withOpContext((spec, opLog) -> {
                    // Create a hollow account
                    var hollowCreate = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, treasuryAlias.get(), -3 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, hollowAccountAlias.get(), +3 * ONE_HBAR))))
                            .payingWith(TREASURY)
                            .signedBy(TREASURY)
                            .via(transferHBARSToHollowAccountTxn);

                    final HapiGetTxnRecord hapiGetTxnRecord = getTxnRecord(transferHBARSToHollowAccountTxn)
                            .andAllChildRecords()
                            .assertingNothingAboutHashes();
                    allRunFor(spec, hollowCreate, hapiGetTxnRecord);
                    if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                        final var newAccountID = hapiGetTxnRecord
                                .getFirstNonStakingChildRecord()
                                .getReceipt()
                                .getAccountID();
                        spec.registry().saveAccountId(hollowAccountKey, newAccountID);
                    }
                    // Verify maxAutomaticAssociations is set to 0
                    var getInfo = getAliasedAccountInfo(hollowAccountKey)
                            .has(accountWith().hasEmptyKey())
                            .hasAlreadyUsedAutomaticAssociations(0)
                            .hasMaxAutomaticAssociations(0)
                            .exposingIdTo(id -> spec.registry().saveAccountId(hollowAccountKey, id));

                    // Delete the account
                    var delete = cryptoDelete(hollowAccountKey)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowAccountKey))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, getInfo, delete);
                }),
                withOpContext((spec, opLog) -> {
                    var changeFlag = overriding("entities.unlimitedAutoAssociationsEnabled", "true");

                    // Create hollow account with the deleted account alias
                    var hollowCreate2 = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, treasuryAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, hollowAccountAlias.get(), +2 * ONE_HBAR))))
                            .payingWith(TREASURY)
                            .signedBy(TREASURY)
                            .via(transferHBARSToHollowAccountTxn);

                    // Verify new hollow account is created and has no associations
                    var getInfo2 = getAliasedAccountInfo(hollowAccountKey)
                            .has(accountWith().hasEmptyKey())
                            .hasAlreadyUsedAutomaticAssociations(0)
                            .hasMaxAutomaticAssociations(-1)
                            .exposingIdTo(id -> spec.registry().saveAccountId(hollowAccountKey, id));

                    // Sends HBAR from hollow account
                    var hollowAccountTransferHBAR = cryptoTransfer(
                                    movingHbar(ONE_HBAR).between(hollowAccountKey, TREASURY))
                            .payingWith(hollowAccountKey)
                            .signedBy(hollowAccountKey, TREASURY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowAccountKey))
                            .via(transferHBARSToHollowAccountTxn);

                    // Verify hollow account is completed
                    var getInfo3 = getAliasedAccountInfo(hollowAccountKey)
                            .hasAlreadyUsedAutomaticAssociations(0)
                            .hasMaxAutomaticAssociations(-1);
                    allRunFor(spec, changeFlag, hollowCreate2, getInfo2, hollowAccountTransferHBAR, getInfo3);
                }));
    }

    @LeakyHapiTest(
            requirement = PROPERTY_OVERRIDES,
            overrides = {"entities.unlimitedAutoAssociationsEnabled"})
    final Stream<DynamicTest> createHollowAccountOnDeletedAliasViaFtTransferAndCompleteIt() {
        final var hollowAccountKey = "hollowAccountKey";
        final AtomicReference<TokenID> fungibleTokenId = new AtomicReference<>();
        final AtomicReference<byte[]> treasuryAlias = new AtomicReference<>();
        final AtomicReference<byte[]> hollowAccountAlias = new AtomicReference<>();
        final var transferFtToHollowAccountTxn = "transferFtToHollowAccountTxn";
        return hapiTest(
                overriding("entities.unlimitedAutoAssociationsEnabled", "false"),
                newKeyNamed(hollowAccountKey).shape(SECP_256K1_SHAPE),
                cryptoCreate(TREASURY).balance(10_000 * ONE_HBAR),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(10L)
                        .treasury(TREASURY),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var treasuryAccountId = registry.getAccountID(TREASURY);
                    treasuryAlias.set(asSolidityAddress(treasuryAccountId));
                    // Save the alias for the hollow account
                    final var ecdsaKey = spec.registry()
                            .getKey(hollowAccountKey)
                            .getECDSASecp256K1()
                            .toByteArray();
                    hollowAccountAlias.set(recoverAddressFromPubKey(ecdsaKey));
                    fungibleTokenId.set(registry.getTokenID(FUNGIBLE_TOKEN));
                }),
                withOpContext((spec, opLog) -> {
                    // Create a hollow account
                    var hollowCreate = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, treasuryAlias.get(), -3 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, hollowAccountAlias.get(), +3 * ONE_HBAR))))
                            .payingWith(TREASURY)
                            .signedBy(TREASURY)
                            .via(transferFtToHollowAccountTxn);

                    final HapiGetTxnRecord hapiGetTxnRecord = getTxnRecord(transferFtToHollowAccountTxn)
                            .andAllChildRecords()
                            .assertingNothingAboutHashes();
                    allRunFor(spec, hollowCreate, hapiGetTxnRecord);
                    if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                        final var newAccountID = hapiGetTxnRecord
                                .getFirstNonStakingChildRecord()
                                .getReceipt()
                                .getAccountID();
                        spec.registry().saveAccountId(hollowAccountKey, newAccountID);
                    }
                    // Verify maxAutomaticAssociations is set to 0
                    var getInfo = getAccountInfo(hollowAccountKey)
                            .hasAlreadyUsedAutomaticAssociations(0)
                            .has(accountWith().hasEmptyKey())
                            .hasMaxAutomaticAssociations(0)
                            .exposingIdTo(id -> spec.registry().saveAccountId(hollowAccountKey, id));

                    // Delete the account
                    var delete = cryptoDelete(hollowAccountKey)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowAccountKey))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, getInfo, delete);
                }),
                withOpContext((spec, opLog) -> {
                    var changeFlag = overriding("entities.unlimitedAutoAssociationsEnabled", "true");

                    // Create hollow account with the deleted account alias
                    var hollowCreate2 = cryptoTransfer((s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(fungibleTokenId.get())
                                    .addTransfers(Utils.aaWith(s, treasuryAlias.get(), -1))
                                    .addTransfers(Utils.aaWith(s, hollowAccountAlias.get(), +1))))
                            .payingWith(TREASURY)
                            .signedBy(TREASURY)
                            .via(transferFtToHollowAccountTxn);

                    // Verify new hollow account is created and has an association
                    var getInfo2 = getAliasedAccountInfo(hollowAccountKey)
                            .has(accountWith().hasEmptyKey())
                            .hasAlreadyUsedAutomaticAssociations(1)
                            .hasMaxAutomaticAssociations(-1)
                            .exposingIdTo(id -> spec.registry().saveAccountId(hollowAccountKey, id));

                    allRunFor(spec, changeFlag, hollowCreate2, getInfo2);
                }));
    }
}
