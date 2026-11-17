// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(ATOMIC_BATCH)
@HapiTestLifecycle
public class AtomicBatchEndToEndSmartContractsHTSCallsAndAssociationsTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";
    private static final long GAS_TO_OFFER = 6_000_000L;
    private static final String OWNER = "owner";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_NOT_ASSOCIATED = "receiverNotAssociated";
    private static final String FT_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String CONTRACT_KEY = "contractKey";
    private static final String HTS_CALLS_CONTRACT = "HTSCalls";
    private static final String supplyKey = "supplyKey";
    private static final String adminKey = "adminKey";
    private static final String wipeKey = "wipeKey";

    @HapiTest
    @DisplayName("Call contract for token mint with insufficient gas fails in atomic batch")
    final Stream<DynamicTest> contractMintWithInsufficientGasFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, supplyKey, wipeKey, fungibleAddress),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT).gas(GAS_TO_OFFER).via("contractCreateTxn"),

                // Make the contract the supply-key of the fungible token so that it can mint tokens
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                tokenUpdate(FT_TOKEN).supplyKey(CONTRACT_KEY).payingWith(OWNER).signedBy(OWNER, adminKey),

                // Call the contract in atomic batch with insufficient gas
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        HTS_CALLS_CONTRACT,
                                        "mintTokenCall",
                                        fungibleAddress.get(),
                                        BigInteger.valueOf(10L),
                                        new byte[][] {})
                                .payingWith(OWNER)
                                .gas(500L) // Insufficient gas
                                .via("callTokenMintContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_GAS)),

                // Assert the token is not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Second token treasury update not signed by contract rolls back atomic batch")
    final Stream<DynamicTest> secondTokenTreasuryUpdateNotSignedByContractRollsBackAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys, tokens, and the prospective treasury contract
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .via("mintContractCreateTxn"),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),

                // The first update succeeds, but must be rolled back when the second lacks the new treasury signature
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn"),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn")
                                        .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                getTokenInfo(FT_TOKEN).hasTreasury(OWNER).hasTotalSupply(0L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTreasury(OWNER).hasTotalSupply(0L)));
    }

    @HapiTest
    @DisplayName("Token treasury update not signed by contract fails in atomic batch")
    final Stream<DynamicTest> tokenTreasuryUpdateNotSignedByContractFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys, token, and the prospective treasury contract
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .via("mintContractCreateTxn"),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),

                // The new treasury contract has not signed this update
                sourcing(() -> atomicBatchDefaultOperator(tokenUpdate(FT_TOKEN)
                                .treasury(HTS_CALLS_CONTRACT)
                                .payingWith(OWNER)
                                .signedBy(OWNER, adminKey)
                                .via("updateFungibleTokenTreasuryToContractInnerTxn")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                getTokenInfo(FT_TOKEN).hasTreasury(OWNER).hasTotalSupply(0L)));
    }

    @HapiTest
    @DisplayName("Token treasury update to contract without auto-associations fails in atomic batch")
    final Stream<DynamicTest> tokenTreasuryUpdateToContractWithoutAutoAssociationsFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys, token, and a contract with no automatic associations
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT).gas(GAS_TO_OFFER).via("mintContractCreateTxn"),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),

                // The update cannot create the required treasury association
                sourcing(() -> atomicBatchDefaultOperator(tokenUpdate(FT_TOKEN)
                                .treasury(HTS_CALLS_CONTRACT)
                                .payingWith(OWNER)
                                .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                .via("updateFungibleTokenTreasuryToContractInnerTxn")
                                .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                getTokenInfo(FT_TOKEN).hasTreasury(OWNER).hasTotalSupply(0L)));
    }

    /* --------------- Helper methods --------------- */

    private HapiAtomicBatch atomicBatchDefaultOperator(HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }

    private HapiTokenCreate createFungibleTokenWithAdminKeyAndSaveAddress(
            String tokenName,
            long supply,
            String treasury,
            String adminKey,
            String supplyKey,
            String wipeKey,
            AtomicReference<Address> fungibleAddress) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey)
                .tokenType(FUNGIBLE_COMMON)
                .exposingAddressTo(fungibleAddress::set);
    }

    private HapiTokenCreate createNonFungibleTokenWithAdminKeyAndSaveAddress(
            String tokenName,
            long supply,
            String treasury,
            String adminKey,
            String supplyKey,
            String wipeKey,
            AtomicReference<Address> nonFungibleAddress) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .exposingAddressTo(nonFungibleAddress::set);
    }

    private List<SpecOperation> createAccountsAndKeys(
            final AtomicReference<Address> receiverAddressFirst, final AtomicReference<Address> receiverAddressSecond) {
        return List.of(
                cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST)
                        .balance(ONE_HBAR)
                        .exposingCreatedIdTo(
                                id -> receiverAddressFirst.set(asHeadlongAddress(asHexedSolidityAddress(id)))),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND)
                        .balance(ONE_HBAR)
                        .exposingCreatedIdTo(
                                id -> receiverAddressSecond.set(asHeadlongAddress(asHexedSolidityAddress(id)))),
                cryptoCreate(RECEIVER_NOT_ASSOCIATED).balance(ONE_HBAR),
                newKeyNamed(supplyKey),
                newKeyNamed(adminKey),
                newKeyNamed(wipeKey));
    }
}
