// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1195;

import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.GLOBAL_WATCHER;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractStateChangesSidecarFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.formattedAssertionValue;
import static com.hedera.services.bdd.suites.integration.hip1195.Hip1195EnabledTest.HOOK_CONTRACT_NUM;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Single;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(14)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(CONCURRENT)
public class Hip1195StorageTest {
    private static final String OWNER = "owner";

    @Contract(contract = "StorageMappingHook", creationGas = 5_000_000)
    static SpecContract STORAGE_GET_MAPPING_HOOK;

    @Contract(contract = "OneTimeCodeHook", creationGas = 5_000_000)
    static SpecContract STORAGE_SET_SLOT_HOOK;

    @Contract(contract = "StorageAccessHook", creationGas = 5_000_000)
    static SpecContract STORAGE_GET_SLOT_HOOK;

    @Contract(contract = "StorageLinkedListHook", creationGas = 5_000_000)
    static SpecContract STORAGE_MODIFICATIONS_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(STORAGE_GET_SLOT_HOOK.getInfo());
        testLifecycle.doAdhoc(STORAGE_GET_MAPPING_HOOK.getInfo());
        testLifecycle.doAdhoc(STORAGE_SET_SLOT_HOOK.getInfo());
        testLifecycle.doAdhoc(STORAGE_MODIFICATIONS_HOOK.getInfo());
        testLifecycle.doAdhoc(withOpContext(
                (spec, opLog) -> GLOBAL_WATCHER.set(new SidecarWatcher(spec.recordStreamsLoc(byNodeId(0))))));
    }

    @HapiTest
    final Stream<DynamicTest> lambdaSStoreUpdateExistingStorageSlotWithoutChangingCount() {
        final Bytes slotKey = Bytes.wrap("existingSlot");
        final Bytes oldValue = Bytes.wrap("oldValue");
        final Bytes newValue = Bytes.wrap("newValue");
        return hapiTest(
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(235L, STORAGE_GET_SLOT_HOOK.name())),
                accountEvmHookStore("ownerAccount", 235L)
                        .putSlot(slotKey, oldValue)
                        .signedBy(DEFAULT_PAYER, "ownerAccount"),
                viewAccount("ownerAccount", (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())),
                accountEvmHookStore("ownerAccount", 235L)
                        .putSlot(slotKey, newValue)
                        .signedBy(DEFAULT_PAYER, "ownerAccount"),
                viewAccount("ownerAccount", (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())));
    }

    @HapiTest
    final Stream<DynamicTest> lambdaSStoreAddNewStorageSlotAuthorizedByOwnerKey() {
        final Bytes slotKey = Bytes.wrap("newSlot");
        final Bytes slotValue = Bytes.wrap("newValue");
        return hapiTest(
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(234L, STORAGE_GET_SLOT_HOOK.name())),
                viewAccount("ownerAccount", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                accountEvmHookStore("ownerAccount", 234L)
                        .putSlot(slotKey, slotValue)
                        .signedBy(DEFAULT_PAYER, "ownerAccount"),
                viewAccount("ownerAccount", (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())));
    }

    @HapiTest
    final Stream<DynamicTest> lambdaSStoreRemoveAllStorageSlots() {
        final Bytes slot1 = Bytes.wrap("slot1");
        final Bytes slot2 = Bytes.wrap("slot2");
        final Bytes slot3 = Bytes.wrap("slot3");
        final Bytes value = Bytes.wrap("value");
        return hapiTest(
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(237L, STORAGE_GET_SLOT_HOOK.name())),
                accountEvmHookStore("ownerAccount", 237L)
                        .putSlot(slot1, value)
                        .putSlot(slot2, value)
                        .putSlot(slot3, value)
                        .signedBy(DEFAULT_PAYER, "ownerAccount"),
                viewAccount("ownerAccount", (Account a) -> assertEquals(3, a.numberEvmHookStorageSlots())),
                accountEvmHookStore("ownerAccount", 237L)
                        .removeSlot(slot1)
                        .removeSlot(slot2)
                        .removeSlot(slot3)
                        .signedBy(DEFAULT_PAYER, "ownerAccount"),
                viewAccount("ownerAccount", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())));
    }

    @HapiTest
    final Stream<DynamicTest> lambdaSStoreWithInvalidSignatureFails() {
        final Bytes slotKey = Bytes.wrap("slot");
        final Bytes slotValue = Bytes.wrap("value");
        return hapiTest(
                newKeyNamed("wrongKey"),
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(239L, STORAGE_GET_SLOT_HOOK.name())),
                accountEvmHookStore("ownerAccount", 239L)
                        .putSlot(slotKey, slotValue)
                        .signedBy(DEFAULT_PAYER, "wrongKey")
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> removeStorageSlotsAndDeleteHook() {
        final Bytes A = Bytes.wrap("a");
        final Bytes B = Bytes.wrap("Bb");
        return hapiTest(
                cryptoCreate("accountToCleanup").withHooks(accountAllowanceHook(221L, STORAGE_GET_SLOT_HOOK.name())),
                accountEvmHookStore("accountToCleanup", 221L).putSlot(A, B),
                viewAccount("accountToCleanup", (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())),
                accountEvmHookStore("accountToCleanup", 221L).removeSlot(A),
                viewAccount("accountToCleanup", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                cryptoUpdate("accountToCleanup").removingHooks(221L),
                viewAccount("accountToCleanup", (Account a) -> {
                    assertEquals(0L, a.firstHookId());
                    assertEquals(0, a.numberHooksInUse());
                }));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> accountHookStoreWithMappingEntry() {
        final var mappingSlot = Bytes.EMPTY;
        final AtomicReference<byte[]> keyMirror = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(238L, STORAGE_GET_MAPPING_HOOK.name())),
                withOpContext((spec, opLog) -> keyMirror.set(
                        unhex(asHexedSolidityAddress(spec.registry().getAccountID(DEFAULT_PAYER))))),
                sourcing(() -> accountEvmHookStore("ownerAccount", 238L)
                        .putMappingEntry(
                                mappingSlot,
                                EvmHookMappingEntry.newBuilder()
                                        .key(Bytes.wrap(keyMirror.get()))
                                        .value(Bytes.wrap(new byte[] {(byte) 0x01}))
                                        .build())
                        .signedBy(DEFAULT_PAYER, "ownerAccount")),
                viewAccount("ownerAccount", (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> contractHookStoreWithMappingEntry() {
        final var mappingSlot = Bytes.EMPTY;
        final AtomicReference<byte[]> keyMirror = new AtomicReference<>();
        return hapiTest(
                createDefaultContract("ownerContract")
                        .withHooks(accountAllowanceHook(238L, STORAGE_GET_MAPPING_HOOK.name())),
                withOpContext((spec, opLog) -> keyMirror.set(
                        unhex(asHexedSolidityAddress(spec.registry().getAccountID(DEFAULT_PAYER))))),
                sourcing(() -> contractHookStore("ownerContract", 238L)
                        .putMappingEntry(
                                mappingSlot,
                                EvmHookMappingEntry.newBuilder()
                                        .key(Bytes.wrap(keyMirror.get()))
                                        .value(Bytes.wrap(new byte[] {(byte) 0x01}))
                                        .build())
                        .signedBy(DEFAULT_PAYER, "ownerContract")),
                viewAccount("ownerContract", (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> cryptoCreateAccountWithHookWithStorageSlotsAndMappingEntries() {
        final var mappingSlot = Bytes.EMPTY;
        final AtomicReference<byte[]> payerMirror = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("accountWithStorage")
                        .withHooks(accountAllowanceHook(214L, STORAGE_GET_MAPPING_HOOK.name())),
                withOpContext((spec, opLog) -> payerMirror.set(
                        unhex(asHexedSolidityAddress(spec.registry().getAccountID(DEFAULT_PAYER))))),
                sourcing(() -> accountEvmHookStore("accountWithStorage", 214L)
                        .putSlot(Bytes.wrap(new byte[] {0x01}), Bytes.wrap(new byte[] {0x02}))
                        .putMappingEntry(
                                mappingSlot,
                                EvmHookMappingEntry.newBuilder()
                                        .key(Bytes.wrap(payerMirror.get()))
                                        .value(Bytes.wrap(new byte[] {(byte) 0x01}))
                                        .build())
                        .signedBy(DEFAULT_PAYER, "accountWithStorage")),
                viewAccount("accountWithStorage", (Account a) -> {
                    assertEquals(214L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberEvmHookStorageSlots());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> storageAccessWorks() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(accountAllowanceHook(124L, STORAGE_GET_SLOT_HOOK.name()))
                        .receiverSigRequired(true),
                // gets rejected because the return value from the allow function is false bye default
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // Change the hook storage's zero slot to 0x01 so that the hook returns true
                accountEvmHookStore(OWNER, 124L)
                        .putSlot(Bytes.EMPTY, Bytes.wrap(new byte[] {(byte) 0x01}))
                        .signedBy(DEFAULT_PAYER, OWNER),
                // now the transfer works
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> storageSettingWorks() {
        final var passcode = "open-sesame";
        final var passcodeHash = Bytes.wrap(keccak256(org.apache.tuweni.bytes.Bytes.wrap(passcode.getBytes(UTF_8)))
                .toArray());
        final var tupleType = TupleType.parse("(string)");
        final var correctPassword = ByteString.copyFrom(tupleType.encode(Single.of(passcode)));
        final var wrongPassword = ByteString.copyFrom(tupleType.encode(Single.of("open-sunflower")));

        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(124L, STORAGE_SET_SLOT_HOOK.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(124L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(0, a.numberEvmHookStorageSlots());
                }),
                // gets rejected because the return value from the allow function is false by default
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // update the required pass code in the hook.
                // Since the contract uses a keccak256 hash of the passcode, we store that in the slot 0
                accountEvmHookStore(OWNER, 124L)
                        .putSlot(Bytes.EMPTY, passcodeHash)
                        .signedBy(DEFAULT_PAYER, OWNER),
                viewAccount(OWNER, (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())),
                // since the contract calls abi.decode on the input bytes, we need to pass in the encoded
                // parameters
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, wrongPassword)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // submitting the correct encoded passcode works
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .signedBy(DEFAULT_PAYER)
                        .via("storageSetTxn"),
                viewAccount(OWNER, (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                // since it resets the storage slots we should not be able to do another transfer
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                expectContractStateChangesSidecarFor(
                        "storageSetTxn",
                        1,
                        List.of(StateChange.stateChangeFor(HOOK_CONTRACT_NUM)
                                .withStorageChanges(StorageChange.readAndWritten(
                                        formattedAssertionValue(0),
                                        formattedAssertionValue(passcodeHash.toHex()),
                                        formattedAssertionValue(0))))),
                withOpContext(
                        (spec, opLog) -> requireNonNull(GLOBAL_WATCHER.get()).assertExpectations(spec)));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> contractStorageSettingWorks() {
        final var passcode = "open-sesame";
        final var passHash32 = Bytes.wrap(keccak256(org.apache.tuweni.bytes.Bytes.wrap(passcode.getBytes(UTF_8)))
                .toArray());
        final var tupleType = TupleType.parse("(string)");
        final var correctPassword = ByteString.copyFrom(tupleType.encode(Single.of(passcode)));
        final var wrongPassword = ByteString.copyFrom(tupleType.encode(Single.of("open-sunflower")));

        return hapiTest(
                createDefaultContract(OWNER).withHooks(accountAllowanceHook(124L, STORAGE_SET_SLOT_HOOK.name())),
                cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).between(DEFAULT_PAYER, OWNER)),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(124L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(0, a.numberEvmHookStorageSlots());
                }),
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                contractHookStore(OWNER, 124L).putSlot(Bytes.EMPTY, passHash32).signedBy(DEFAULT_PAYER, OWNER),
                viewAccount(OWNER, (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())),
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, wrongPassword)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword),
                viewAccount(OWNER, (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                // Passcode is a one-time use
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> storageAccessFromMappingWorks() {
        final var mappingSlot = Bytes.EMPTY;
        final AtomicReference<byte[]> defaultPayerMirror = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(124L, STORAGE_GET_MAPPING_HOOK.name())),
                viewAccount(OWNER, (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                withOpContext(
                        (spec, opLog) -> defaultPayerMirror.set(unhex(asHexedSolidityAddress(asAccount(spec, 2))))),
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // Change the hook storage's mapping slot to have key 0x00 -> 0x01 so that the hook returns true
                // (the hook reads the mapping at slot 0, with key msg.sender; if the value is true, it
                // returns true)
                sourcing(() -> accountEvmHookStore(OWNER, 124L)
                        .putMappingEntry(
                                mappingSlot,
                                EvmHookMappingEntry.newBuilder()
                                        .key(minimalKey(Bytes.wrap(defaultPayerMirror.get())))
                                        .value(Bytes.wrap(new byte[] {(byte) 0x01}))
                                        .build())
                        .signedBy(DEFAULT_PAYER, OWNER)),
                viewAccount(OWNER, (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())),
                cryptoTransfer(movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER),
                viewAccount(OWNER, (Account a) -> assertEquals(1, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hookZeroWriteIntoEmptySlotDoesNotChangeCount() {
        final var opcode = ByteString.copyFrom(new byte[] {0x01});
        return hapiTest(
                cryptoCreate("ownerZero").withHooks(accountAllowanceHook(246L, STORAGE_MODIFICATIONS_HOOK.name())),
                viewAccount("ownerZero", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                cryptoTransfer(movingHbar(10).between("ownerZero", GENESIS))
                        .withPreHookFor("ownerZero", 246L, 250_000L, opcode)
                        .signedBy(DEFAULT_PAYER),
                viewAccount("ownerZero", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hookRemoveAllExistingSlotsInOneTransaction() {
        final var opAdd = ByteString.copyFrom(new byte[] {0x02});
        final var opRemoveAll = ByteString.copyFrom(new byte[] {0x03});
        return hapiTest(
                cryptoCreate("ownerRemove").withHooks(accountAllowanceHook(247L, STORAGE_MODIFICATIONS_HOOK.name())),
                // Populate three slots
                cryptoTransfer(movingHbar(10).between("ownerRemove", GENESIS))
                        .withPreHookFor("ownerRemove", 247L, 250_000L, opAdd)
                        .signedBy(DEFAULT_PAYER),
                viewAccount("ownerRemove", (Account a) -> assertEquals(3, a.numberEvmHookStorageSlots())),
                // Remove all slots in one transaction
                cryptoTransfer(movingHbar(10).between("ownerRemove", GENESIS))
                        .withPreHookFor("ownerRemove", 247L, 250_000L, opRemoveAll)
                        .signedBy(DEFAULT_PAYER),
                viewAccount("ownerRemove", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hookRemoveAllExistingSlotsInOneTransactionContract() {
        final var opAdd = ByteString.copyFrom(new byte[] {0x02});
        final var opRemoveAll = ByteString.copyFrom(new byte[] {0x03});
        final var MULTIPURPOSE = "Multipurpose";
        return hapiTest(
                uploadInitCode(MULTIPURPOSE),
                contractCreate(MULTIPURPOSE)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(247L, STORAGE_MODIFICATIONS_HOOK.name())),
                viewContract(MULTIPURPOSE, (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                // Populate three slots
                cryptoTransfer(movingHbar(10).between(MULTIPURPOSE, GENESIS))
                        .withPreHookFor(MULTIPURPOSE, 247L, 250_000L, opAdd)
                        .signedBy(DEFAULT_PAYER),
                viewContract(MULTIPURPOSE, (Account a) -> assertEquals(3, a.numberEvmHookStorageSlots())),
                // Remove all slots in one transaction
                cryptoTransfer(movingHbar(10).between(MULTIPURPOSE, GENESIS))
                        .withPreHookFor(MULTIPURPOSE, 247L, 250_000L, opRemoveAll)
                        .signedBy(DEFAULT_PAYER),
                viewContract(MULTIPURPOSE, (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hookAddAndRemoveAllInSingleTransaction() {
        final var opAddRemove = ByteString.copyFrom(new byte[] {0x04});
        return hapiTest(
                cryptoCreate("ownerAddRem").withHooks(accountAllowanceHook(248L, STORAGE_MODIFICATIONS_HOOK.name())),
                viewAccount("ownerAddRem", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())),
                // Add and remove all in one transaction
                cryptoTransfer(movingHbar(10).between("ownerAddRem", GENESIS))
                        .withPreHookFor("ownerAddRem", 248L, 250_000L, opAddRemove)
                        .signedBy(DEFAULT_PAYER),
                viewAccount("ownerAddRem", (Account a) -> assertEquals(0, a.numberEvmHookStorageSlots())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteHooksWithoutStorageSlots() {
        return hapiTest(
                cryptoCreate("accountToDelete").withHooks(accountAllowanceHook(222L, STORAGE_GET_SLOT_HOOK.name())),
                viewAccount("accountToDelete", (Account a) -> {
                    assertEquals(222L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }),
                cryptoUpdate("accountToDelete").removingHooks(222L),
                viewAccount("accountToDelete", (Account a) -> {
                    assertEquals(0L, a.firstHookId());
                    assertEquals(0, a.numberHooksInUse());
                }));
    }
}
