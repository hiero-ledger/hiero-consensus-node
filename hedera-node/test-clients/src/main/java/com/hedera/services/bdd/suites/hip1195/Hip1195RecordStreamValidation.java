// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.ADHOC;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.encodeParametersForConstructor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.GLOBAL_WATCHER;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractStateChangesSidecarFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.formattedAssertionValue;
import static com.hedera.services.bdd.suites.integration.hip1195.Hip1195EnabledTest.HOOK_CONTRACT_NUM;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.crypto.Hash.keccak256;

@HapiTestLifecycle
public class Hip1195RecordStreamValidation {
    private static final String OWNER = "owner";

    private static final String STRING_ABI =
            "{\"inputs\":[{\"internalType\":\"string\",\"name\":\"_password\",\"type\":\"string\"}],\"stateMutability\":\"nonpayable\",\"type\":\"constructor\"}";

    @Contract(contract = "OneTimeCodeHook", creationGas = 5_000_000)
    static SpecContract STORAGE_SET_SLOT_HOOK;
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(STORAGE_SET_SLOT_HOOK.getInfo());

        testLifecycle.doAdhoc(withOpContext(
                (spec, opLog) -> GLOBAL_WATCHER.set(new SidecarWatcher(spec.recordStreamsLoc(byNodeId(0))))));
    }

    @HapiTest
    @Tag(ADHOC)
    final Stream<DynamicTest> storageSettingWorks() {
        final var passcode = "open-sesame";
        final var passHash32 = Bytes.wrap(keccak256(org.apache.tuweni.bytes.Bytes.wrap(passcode.getBytes(UTF_8)))
                .toArray());
        final var correctPassword =
                ByteString.copyFrom(encodeParametersForConstructor(new Object[] {passcode}, STRING_ABI));
        final var wrongPassword =
                ByteString.copyFrom(encodeParametersForConstructor(new Object[] {"wrong password"}, STRING_ABI));

        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(124L, STORAGE_SET_SLOT_HOOK.name())),
                // gets rejected because the return value from the allow function is false bye default
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // update the required pass code in the hook.
                // Since the contract uses a keccak256 hash of the passcode, we store that in the slot 0
                accountLambdaSStore(OWNER, 124L)
                        .putSlot(Bytes.EMPTY, passHash32)
                        .signedBy(DEFAULT_PAYER, OWNER),
                // since the contract calls abi.decode on the input bytes, we need to pass in the encoded
                // parameters
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, wrongPassword)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // submitting the correct encoded passcode works
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .signedBy(DEFAULT_PAYER)
                        .via("storageSetTxn"),
                // since it resets the storage slots we should not be able to do another transfer
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                expectContractStateChangesSidecarFor(
                        "storageSetTxn",
                        1,
                        List.of(StateChange.stateChangeFor(HOOK_CONTRACT_NUM)
                                .withStorageChanges(StorageChange.readAndWritten(
                                        formattedAssertionValue(0),
                                        formattedAssertionValue(passHash32.toHex()),
                                        formattedAssertionValue(0))))),
                withOpContext(
                        (spec, opLog) -> requireNonNull(GLOBAL_WATCHER.get()).assertExpectations(spec)));
    }
}
