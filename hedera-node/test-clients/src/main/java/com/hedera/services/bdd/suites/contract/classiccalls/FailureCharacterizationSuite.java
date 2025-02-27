// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.classiccalls.CallResultsSnapshot.CALL_RESULTS_SNAPSHOT;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.CRYPTO_KEY;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.FAILABLE_CALLS_CONTRACT;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.FAILABLE_CONTROL_KEY;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.VALID_ACCOUNT_IDS;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.VALID_FUNGIBLE_TOKEN_IDS;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.VALID_NON_FUNGIBLE_TOKEN_IDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.ApproveFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.ApproveNftFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.AssociateTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.AssociateTokensFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.BurnTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.CreateFungibleTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.CreateFungibleTokenWithCustomFeesFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.CreateNonFungibleTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.CreateNonFungibleTokenWithCustomFeesFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.CryptoTransferFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.DeleteTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.DissociateTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.DissociateTokensFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.FreezeFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.MintTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.PauseTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.RedirectForTokenMutationFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.RevokeKycFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.SetApprovalForAllFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.TransferFromNftFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.TransferNftFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.TransferNftsFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.TransferTokensFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.TransferUnitsFromFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.UnfreezeFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.UnpauseTokenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.UpdateTokenExpiryInfoFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.UpdateTokenInfoFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.UpdateTokenKeysFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.WipeNftFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.mutations.WipeUnitsFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetAllowanceFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetApprovedFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetCustomFeesFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetFungibleTokenInfoFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetNonFungibleTokenInfoFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetTokenDefaultFreezeStatusFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetTokenDefaultKycStatusFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetTokenExpiryInfoFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetTokenInfoFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetTokenKeyFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GetTokenTypeFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.GrantKycFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.IsApprovedForAllFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.IsFrozenFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.IsKycFailableCall;
import com.hedera.services.bdd.suites.contract.classiccalls.views.IsTokenFailableCall;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class FailureCharacterizationSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(FailureCharacterizationSuite.class);

    public static void main(String... args) {
        new FailureCharacterizationSuite().runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(characterizeClassicFailureModes(
                List.of(
                        new IsKycFailableCall(),
                        new IsTokenFailableCall(),
                        new IsFrozenFailableCall(),
                        new IsApprovedForAllFailableCall(),
                        new GetApprovedFailableCall(),
                        new GetCustomFeesFailableCall(),
                        new GetTokenKeyFailableCall(),
                        new GetTokenInfoFailableCall(),
                        new GetTokenTypeFailableCall(),
                        new GetFungibleTokenInfoFailableCall(),
                        new GetTokenDefaultKycStatusFailableCall(),
                        new GetTokenDefaultFreezeStatusFailableCall(),
                        new GetNonFungibleTokenInfoFailableCall(),
                        new GetTokenExpiryInfoFailableCall(),
                        new FreezeFailableCall(),
                        new MintTokenFailableCall(),
                        new BurnTokenFailableCall(),
                        new AssociateTokenFailableCall(),
                        new AssociateTokensFailableCall(),
                        new DissociateTokenFailableCall(),
                        new DissociateTokensFailableCall(),
                        new UnfreezeFailableCall(),
                        new ApproveFailableCall(),
                        new TransferNftsFailableCall(),
                        new TransferNftFailableCall(),
                        new CryptoTransferFailableCall(),
                        new CreateFungibleTokenFailableCall(),
                        new CreateNonFungibleTokenFailableCall(),
                        new CreateFungibleTokenWithCustomFeesFailableCall(),
                        new CreateNonFungibleTokenWithCustomFeesFailableCall(),
                        new RedirectForTokenMutationFailableCall(),
                        new TransferTokensFailableCall(),
                        new WipeNftFailableCall(),
                        new WipeUnitsFailableCall(),
                        new GrantKycFailableCall(),
                        new RevokeKycFailableCall(),
                        new PauseTokenFailableCall(),
                        new DeleteTokenFailableCall(),
                        new ApproveNftFailableCall(),
                        new GetAllowanceFailableCall(),
                        new SetApprovalForAllFailableCall(),
                        new TransferUnitsFromFailableCall(),
                        new TransferFromNftFailableCall(),
                        new UnpauseTokenFailableCall(),
                        new UpdateTokenKeysFailableCall(),
                        new UpdateTokenInfoFailableCall(),
                        new UpdateTokenExpiryInfoFailableCall()),
                CharacterizationMode.RECORD_SNAPSHOT));
    }

    enum CharacterizationMode {
        RECORD_SNAPSHOT,
        ASSERT_MATCHES_SNAPSHOT
    }

    // assertions in production code, repeated string literals
    @SuppressWarnings({"java:S5960", "java:S1192"})
    final Stream<DynamicTest> characterizeClassicFailureModes(
            @NonNull final List<FailableClassicCall> calls, @NonNull final CharacterizationMode characterizationMode) {
        if (characterizationMode == CharacterizationMode.RECORD_SNAPSHOT) {
            CALL_RESULTS_SNAPSHOT.begin();
        } else {
            CALL_RESULTS_SNAPSHOT.load();
        }
        return hapiTest(
                uploadInitCode(FAILABLE_CALLS_CONTRACT),
                contractCreate(FAILABLE_CALLS_CONTRACT),
                classicInventoryIsAvailable(),
                inParallel(Arrays.stream(ClassicFailureMode.values())
                        .flatMap(mode -> calls.stream()
                                .filter(call -> call.hasFailureMode(mode))
                                .map(call -> sourcingContextual(spec -> {
                                    final var params = call.encodedCall(mode, spec);
                                    final var nonStaticTxnId = "CALL-" + call.name() + "-" + mode.name();
                                    final var nonStaticCall = blockingOrder(
                                            contractCall(FAILABLE_CALLS_CONTRACT, "makeClassicCall", params)
                                                    .via(nonStaticTxnId)
                                                    .gas(1_000_000L)
                                                    .hasKnownStatusFrom(SUCCESS, CONTRACT_REVERT_EXECUTED),
                                            getTxnRecord(nonStaticTxnId).exposingAllTo(records -> {
                                                final var actualResult = call.asCallResult(records);
                                                if (characterizationMode == CharacterizationMode.RECORD_SNAPSHOT) {
                                                    CALL_RESULTS_SNAPSHOT.recordResult(call.name(), mode, actualResult);
                                                } else {
                                                    final var expectedResult =
                                                            CALL_RESULTS_SNAPSHOT.expectedResultOf(call.name(), mode);
                                                    assertEquals(
                                                            expectedResult,
                                                            actualResult,
                                                            "Wrong result for " + call.name() + " for failure mode "
                                                                    + mode.name());
                                                }
                                            }));
                                    return !call.staticCallOk()
                                            ? nonStaticCall
                                            : blockingOrder(
                                                    nonStaticCall,
                                                    contractCallLocal(
                                                                    FAILABLE_CALLS_CONTRACT, "makeClassicCall", params)
                                                            .hasAnswerOnlyPrecheckFrom(OK, CONTRACT_REVERT_EXECUTED)
                                                            .exposingFullResultTo((status, result) -> {
                                                                final var actualResult =
                                                                        call.asStaticCallResult(status, result);
                                                                if (characterizationMode
                                                                        == CharacterizationMode.RECORD_SNAPSHOT) {
                                                                    CALL_RESULTS_SNAPSHOT.recordStaticResult(
                                                                            call.name(), mode, actualResult);
                                                                } else {
                                                                    final var expectedResult =
                                                                            CALL_RESULTS_SNAPSHOT
                                                                                    .expectedStaticCallResultOf(
                                                                                            call.name(), mode);
                                                                    assertEquals(
                                                                            expectedResult,
                                                                            actualResult,
                                                                            "Wrong static call result for "
                                                                                    + call.name()
                                                                                    + " for failure mode "
                                                                                    + mode.name());
                                                                }
                                                            }));
                                })))
                        .toArray(HapiSpecOperation[]::new)),
                withOpContext((spec, opLog) -> {
                    if (characterizationMode == CharacterizationMode.RECORD_SNAPSHOT) {
                        CALL_RESULTS_SNAPSHOT.commit();
                    }
                }));
    }

    private HapiSpecOperation classicInventoryIsAvailable() {
        return blockingOrder(
                newKeyNamed(CRYPTO_KEY).shape(ED25519_ON),
                doingContextual(this::saveClassicContractControlledKey),
                inParallel(
                        inParallel(Arrays.stream(VALID_ACCOUNT_IDS)
                                .map(name -> cryptoCreate(name)
                                        .key(FAILABLE_CONTROL_KEY)
                                        .signedBy(DEFAULT_PAYER, CRYPTO_KEY))
                                .toArray(HapiSpecOperation[]::new)),
                        inParallel(Arrays.stream(VALID_FUNGIBLE_TOKEN_IDS)
                                .map(name -> tokenCreate(name)
                                        .tokenType(FUNGIBLE_COMMON)
                                        .adminKey(FAILABLE_CONTROL_KEY)
                                        .supplyKey(FAILABLE_CONTROL_KEY)
                                        .wipeKey(FAILABLE_CONTROL_KEY)
                                        .pauseKey(FAILABLE_CONTROL_KEY)
                                        .freezeKey(FAILABLE_CONTROL_KEY)
                                        .kycKey(FAILABLE_CONTROL_KEY)
                                        .feeScheduleKey(FAILABLE_CONTROL_KEY)
                                        .signedBy(DEFAULT_PAYER, CRYPTO_KEY))
                                .toArray(HapiSpecOperation[]::new)),
                        inParallel(Arrays.stream(VALID_NON_FUNGIBLE_TOKEN_IDS)
                                .map(name -> blockingOrder(
                                        tokenCreate(name)
                                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                                .initialSupply(0)
                                                .adminKey(FAILABLE_CONTROL_KEY)
                                                .supplyKey(FAILABLE_CONTROL_KEY)
                                                .wipeKey(FAILABLE_CONTROL_KEY)
                                                .pauseKey(FAILABLE_CONTROL_KEY)
                                                .freezeKey(FAILABLE_CONTROL_KEY)
                                                .kycKey(FAILABLE_CONTROL_KEY)
                                                .feeScheduleKey(FAILABLE_CONTROL_KEY)
                                                .signedBy(DEFAULT_PAYER, CRYPTO_KEY),
                                        mintToken(name, List.of(ByteString.copyFromUtf8(name + "#1")))
                                                .signedBy(DEFAULT_PAYER, CRYPTO_KEY)))
                                .toArray(HapiSpecOperation[]::new))),
                inParallel(ClassicInventory.VALID_ASSOCIATIONS.stream()
                        .map(association -> tokenAssociate(association.left(), association.right()))
                        .toArray(HapiSpecOperation[]::new)));
    }

    private void saveClassicContractControlledKey(@NonNull final HapiSpec spec) {
        final var registry = spec.registry();
        final var failableControlKey = Key.newBuilder()
                .setThresholdKey(ThresholdKey.newBuilder()
                        .setThreshold(1)
                        .setKeys(KeyList.newBuilder()
                                .addKeys(
                                        Key.newBuilder().setContractID(registry.getContractId(FAILABLE_CALLS_CONTRACT)))
                                .addKeys(registry.getKey(CRYPTO_KEY)))
                        .build())
                .build();
        registry.saveKey(FAILABLE_CONTROL_KEY, failableControlKey);
        spec.keys().setControl(failableControlKey, SigControl.threshSigs(1, OFF, ON));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
