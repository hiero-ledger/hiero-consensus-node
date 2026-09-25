// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.node.app.service.schedule.impl.handlers.AbstractScheduleHandler.KEY_COMPARATOR;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractScheduleHandlerTest {
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().contractNum(666L).build();
    private static final AccountID ACCOUNT_CONTRACT_ID =
            AccountID.newBuilder().accountNum(CONTRACT_ID.contractNumOrThrow()).build();
    private static final ContractID OTHER_CONTRACT_ID =
            ContractID.newBuilder().contractNum(777L).build();
    private static final ContractID CONTRACT_ALIAS =
            ContractID.newBuilder().evmAddress(Bytes.fromHex("012345")).build();
    private static final ContractID OTHER_CONTRACT_ALIAS =
            ContractID.newBuilder().evmAddress(Bytes.fromHex("678901")).build();
    public static final Key CONTRACT_ID_KEY =
            Key.newBuilder().contractID(CONTRACT_ID).build();
    public static final Key OTHER_CONTRACT_ID_KEY =
            Key.newBuilder().contractID(OTHER_CONTRACT_ID).build();
    private static final Key CONTRACT_ALIAS_KEY =
            Key.newBuilder().contractID(CONTRACT_ALIAS).build();
    private static final Key OTHER_CONTRACT_ALIAS_KEY =
            Key.newBuilder().contractID(OTHER_CONTRACT_ALIAS).build();
    private static final Key DELEGATABLE_CONTRACT_ID_KEY =
            Key.newBuilder().delegatableContractId(CONTRACT_ID).build();
    private static final Key DELEGATABLE_CONTRACT_ALIAS_KEY =
            Key.newBuilder().delegatableContractId(CONTRACT_ALIAS).build();
    private static final Key ED25519_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0101010101010101010101010101010101010101010101010101010101010101"))
            .build();
    private static final Key OTHER_ED25519_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0202020202020202020202020202020202020202020202020202020202020202"))
            .build();

    private static final Key ECDSA_KEY = Key.newBuilder()
            .ecdsaSecp256k1(Bytes.fromHex("010101010101010101010101010101010101010101010101010101010101010101"))
            .build();

    @Mock
    private ReadableAccountStore accountStore;

    @Test
    void accumulatesSpecificallyContractIdKeys() {
        final var newSignatories = AbstractScheduleHandler.newSignatories(
                sortedSetOf(
                        DELEGATABLE_CONTRACT_ALIAS_KEY,
                        CONTRACT_ID_KEY,
                        CONTRACT_ALIAS_KEY,
                        DELEGATABLE_CONTRACT_ID_KEY),
                emptyList(),
                emptyList());
        assertThat(newSignatories).containsExactlyInAnyOrder(CONTRACT_ID_KEY, DELEGATABLE_CONTRACT_ID_KEY);
    }

    /**
     * Per {@code Schedule.signatories} (services/state/schedule/schedule.proto:165-181): "The only keys stored
     * are 'primitive' keys (ED25519 or ECDSA_SECP256K1) in order to ensure that any key list or threshold keys
     * are correctly handled, regardless of signing order, intervening changes, or other situations." Recording
     * is therefore per constituent and independent of whether the threshold is already met - the same field
     * documentation states the transaction "SHALL NOT be executed before this list is sufficient to 'activate'
     * the required keys".
     */
    @Test
    void recordsOnlySigningConstituentsOfARequiredThresholdKey() {
        final var thresholdKey = Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(2)
                        .keys(KeyList.newBuilder().keys(ED25519_KEY, ECDSA_KEY, OTHER_ED25519_KEY)))
                .build();
        // Two of the three constituents signed; only those two are recorded, even though the threshold is unmet
        final var newSignatories = AbstractScheduleHandler.newSignatories(
                sortedSetOf(ED25519_KEY, OTHER_ED25519_KEY), emptyList(), List.of(thresholdKey));
        assertThat(newSignatories).containsExactlyInAnyOrder(ED25519_KEY, OTHER_ED25519_KEY);
    }

    /**
     * The key-list counterpart of the threshold case, per the same
     * {@code Schedule.signatories} documentation (schedule.proto:175-177).
     */
    @Test
    void recordsOnlySigningConstituentsOfARequiredKeyList() {
        final var keyListKey = Key.newBuilder()
                .keyList(KeyList.newBuilder().keys(ED25519_KEY, ECDSA_KEY))
                .build();
        final var newSignatories =
                AbstractScheduleHandler.newSignatories(sortedSetOf(ECDSA_KEY), emptyList(), List.of(keyListKey));
        assertThat(newSignatories).containsExactly(ECDSA_KEY);
    }

    /**
     * {@code Schedule.signatories} (schedule.proto:170-173) makes signing necessary - "A Key SHALL NOT be stored
     * in this list unless the corresponding private key has signed either the original {@code schedule_create}
     * transaction or a subsequent {@code schedule_sign} transaction intended for, and referencing to, this
     * specific schedule." This asserts the stricter rule the implementation applies: signing is necessary but
     * not sufficient, because a key is only accumulated while walking the schedule's currently required keys.
     */
    @Test
    void doesNotRecordASigningKeyThatIsNotARequiredConstituent() {
        final var thresholdKey = Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(1)
                        .keys(KeyList.newBuilder().keys(ED25519_KEY)))
                .build();
        // OTHER_ED25519_KEY signed but is no part of the required structure, so it is not recorded
        final var newSignatories = AbstractScheduleHandler.newSignatories(
                sortedSetOf(OTHER_ED25519_KEY), emptyList(), List.of(thresholdKey));
        assertThat(newSignatories).isEmpty();
    }

    /**
     * Nested composition of the same rule, exercising the recursive descent that keeps the stored list made of
     * primitive keys only (schedule.proto:175-177).
     */
    @Test
    void recordsSigningConstituentsOfANestedThresholdKey() {
        final var nested = Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(1)
                        .keys(KeyList.newBuilder()
                                .keys(
                                        Key.newBuilder()
                                                .thresholdKey(ThresholdKey.newBuilder()
                                                        .threshold(1)
                                                        .keys(KeyList.newBuilder()
                                                                .keys(ED25519_KEY, ECDSA_KEY)))
                                                .build(),
                                        OTHER_ED25519_KEY)))
                .build();
        final var newSignatories =
                AbstractScheduleHandler.newSignatories(sortedSetOf(ECDSA_KEY), emptyList(), List.of(nested));
        assertThat(newSignatories).containsExactly(ECDSA_KEY);
    }

    @Test
    void cryptoSignatoriesAreRespected() {
        final var keyVerifier = AbstractScheduleHandler.simpleKeyVerifierFrom(accountStore, List.of(ED25519_KEY));
        assertThat(keyVerifier.test(ED25519_KEY)).isTrue();
        assertThat(keyVerifier.test(ECDSA_KEY)).isFalse();
    }

    @Test
    void contractIdAndDelegatableContractIdKeysAreActivatedByIdKey() {
        given(accountStore.getAccountIDByAlias(0, 0, CONTRACT_ALIAS.evmAddressOrThrow()))
                .willReturn(ACCOUNT_CONTRACT_ID);
        final var keyVerifier = AbstractScheduleHandler.simpleKeyVerifierFrom(accountStore, List.of(CONTRACT_ID_KEY));
        assertThat(keyVerifier.test(CONTRACT_ID_KEY)).isTrue();
        assertThat(keyVerifier.test(DELEGATABLE_CONTRACT_ID_KEY)).isTrue();
        assertThat(keyVerifier.test(CONTRACT_ALIAS_KEY)).isTrue();
        assertThat(keyVerifier.test(DELEGATABLE_CONTRACT_ALIAS_KEY)).isTrue();
        assertThat(keyVerifier.test(OTHER_CONTRACT_ID_KEY)).isFalse();
        assertThat(keyVerifier.test(OTHER_CONTRACT_ALIAS_KEY)).isFalse();
        assertThat(keyVerifier.test(
                        Key.newBuilder().contractID(ContractID.DEFAULT).build()))
                .isFalse();
    }

    @Test
    void onlyDelegatableContractIdKeysAreActivatedByDelegatableIdKey() {
        given(accountStore.getAccountIDByAlias(0, 0, CONTRACT_ALIAS.evmAddressOrThrow()))
                .willReturn(ACCOUNT_CONTRACT_ID);
        final var keyVerifier =
                AbstractScheduleHandler.simpleKeyVerifierFrom(accountStore, List.of(DELEGATABLE_CONTRACT_ID_KEY));
        assertThat(keyVerifier.test(CONTRACT_ID_KEY)).isFalse();
        assertThat(keyVerifier.test(DELEGATABLE_CONTRACT_ID_KEY)).isTrue();
        assertThat(keyVerifier.test(CONTRACT_ALIAS_KEY)).isFalse();
        assertThat(keyVerifier.test(DELEGATABLE_CONTRACT_ALIAS_KEY)).isTrue();
        assertThat(keyVerifier.test(OTHER_CONTRACT_ID_KEY)).isFalse();
        assertThat(keyVerifier.test(OTHER_CONTRACT_ALIAS_KEY)).isFalse();
        assertThat(keyVerifier.test(
                        Key.newBuilder().contractID(ContractID.DEFAULT).build()))
                .isFalse();
    }

    private SortedSet<Key> sortedSetOf(Key... keys) {
        return new TreeSet<>(KEY_COMPARATOR) {
            {
                addAll(List.of(keys));
            }
        };
    }
}
