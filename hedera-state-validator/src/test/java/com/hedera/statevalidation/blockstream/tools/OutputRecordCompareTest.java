// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.block.stream.output.*;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.token.Account;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutputRecordCompareTest {
    private static final int ACCOUNTS = StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal();
    private static final MapChangeKey KEY =
            MapChangeKey.newBuilder().accountIdKey(AccountID.DEFAULT).build();
    private static final Account BALANCE =
            Account.newBuilder().tinybarBalance(11).build();
    private static final Account MEMO = Account.newBuilder().memo("new").build();

    @Test
    void differentClearsWithIdenticalSparsePayloadsAreDifferent() {
        assertDifferent(List.of(patch(Account.DEFAULT, 5)), List.of(patch(Account.DEFAULT, 6)));
    }

    @Test
    void identicalLastPatchDoesNotHideAnEarlierDifference() {
        assertDifferent(
                List.of(patch(Account.newBuilder().memo("a").build()), patch(BALANCE)),
                List.of(patch(Account.newBuilder().memo("b").build()), patch(BALANCE)));
    }

    @Test
    void splitAndCombinedPatchesHaveTheSameEffect() {
        assertSameEffect(
                List.of(patch(MEMO), patch(BALANCE)),
                List.of(patch(MEMO.copyBuilder().tinybarBalance(11).build())));
    }

    @Test
    void laterReplacementOverridesAnEarlierClear() {
        assertSameEffect(List.of(patch(Account.DEFAULT, 6), patch(MEMO)), List.of(patch(MEMO)));
    }

    @Test
    void laterClearOverridesAnEarlierReplacementAndPreservesOtherClears() {
        assertSameEffect(List.of(patch(MEMO, 5), patch(Account.DEFAULT, 6)), List.of(patch(Account.DEFAULT, 5, 6)));
    }

    @Test
    void fullThenPartialMatchesTheReconstructedFullValue() {
        final var baseline = Account.newBuilder().memo("old").tinybarBalance(10).build();
        assertSameEffect(
                List.of(full(baseline), patch(BALANCE)),
                List.of(full(baseline.copyBuilder().tinybarBalance(11).build())));
        assertSameEffect(
                List.of(full(baseline), patch(Account.DEFAULT, 6)),
                List.of(full(baseline.copyBuilder().memo("").build())));
    }

    @Test
    void finalFullReplacementDiscardsAllEarlierPatches() {
        assertSameEffect(List.of(patch(MEMO, 5), full(BALANCE)), List.of(full(BALANCE)));
    }

    @Test
    void fullAndUnseededPartialCannotBeAssumedEqual() {
        assertDifferent(List.of(full(BALANCE)), List.of(patch(BALANCE)));
    }

    @Test
    void emptyPartialHasNoNetEffect() {
        assertSameEffect(List.of(patch(Account.DEFAULT)), List.of());
    }

    @Test
    void partialAfterDeleteRequiresANewFullBaseline() {
        final var delete = StateChange.newBuilder()
                .stateId(ACCOUNTS)
                .mapDelete(MapDeleteChange.newBuilder().key(KEY))
                .build();
        assertThrows(IllegalArgumentException.class, () -> differences(List.of(delete, patch(BALANCE)), List.of()));
        assertSameEffect(
                List.of(delete, full(MEMO), patch(BALANCE)),
                List.of(full(MEMO.copyBuilder().tinybarBalance(11).build())));
    }

    @Test
    void singletonClearsAndEarlierPatchesAlsoParticipateInComparison() {
        assertDifferent(List.of(singleton(BlockInfo.DEFAULT, 4)), List.of(singleton(BlockInfo.DEFAULT, 6)));
        final var later = singleton(BlockInfo.newBuilder().lastBlockNumber(8).build());
        assertDifferent(
                List.of(singleton(BlockInfo.DEFAULT, 4), later), List.of(singleton(BlockInfo.DEFAULT, 6), later));
    }

    @Test
    void ignoredServicesRemainIgnored() {
        final var differences = differences(List.of(patch(Account.DEFAULT, 5)), List.of(patch(Account.DEFAULT, 6)));
        final String service = differences.iterator().next().split("\\.")[0];
        assertTrue(OutputRecordCompare.differingStateServices(
                        List.of(patch(Account.DEFAULT, 5)), List.of(patch(Account.DEFAULT, 6)), Set.of(service))
                .isEmpty());
    }

    private static StateChange patch(Account value, Integer... clears) {
        return map(value, true, List.of(clears));
    }

    private static StateChange full(Account value) {
        return map(value, false, List.of());
    }

    private static StateChange map(Account value, boolean partial, List<Integer> clears) {
        return StateChange.newBuilder()
                .stateId(ACCOUNTS)
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(KEY)
                        .value(MapChangeValue.newBuilder().accountValue(value))
                        .partial(partial)
                        .clearedFields(clears))
                .build();
    }

    private static StateChange singleton(BlockInfo value, Integer... clears) {
        return StateChange.newBuilder()
                .stateId(StateIdentifier.STATE_ID_BLOCKS.protoOrdinal())
                .singletonUpdate(SingletonUpdateChange.newBuilder()
                        .blockInfoValue(value)
                        .partial(true)
                        .clearedFields(List.of(clears)))
                .build();
    }

    private static Set<String> differences(List<StateChange> a, List<StateChange> b) {
        return OutputRecordCompare.differingStateServices(a, b, Set.of());
    }

    private static void assertSameEffect(List<StateChange> a, List<StateChange> b) {
        assertTrue(differences(a, b).isEmpty());
    }

    private static void assertDifferent(List<StateChange> a, List<StateChange> b) {
        assertFalse(differences(a, b).isEmpty());
    }
}
