// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mapping from node letters to node ids. */
class NamedRosterTest {

    @Test
    @DisplayName("node ids stay small but are never equal to the letter's position")
    void nodeIdsAreSmallButNotPositions() {
        final List<String> letters = List.of("a", "b", "c", "d", "e", "f");
        for (int index = 0; index < letters.size(); index++) {
            final long id = NamedRoster.nodeIdFor(letters.get(index)).id();
            assertThat(id)
                    .as("'%s' is at position %d, so its id must differ", letters.get(index), index)
                    .isNotEqualTo(index);
        }
        assertThat(NamedRoster.nodeIdFor("d").id())
                .as("ids advance one to three per node, as randomRoster does, so they stay memorable")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("ids start one above the first node id and advance by gaps of 1, 3 and 2")
    void idsFollowTheDocumentedSequence() {
        final List<Long> ids = "abcdefg"
                .chars()
                .mapToObj(letter ->
                        NamedRoster.nodeIdFor(Character.toString(letter)).id())
                .toList();
        assertThat(ids).as("the values nodeIdFor's javadoc lists").containsExactly(1L, 2L, 5L, 7L, 8L, 11L, 13L);
        assertThat(NamedRoster.nodeIdFor("z").id()).isEqualTo(50L);
    }

    @Test
    @DisplayName("every letter has its own node id, and nothing else is a letter")
    void everyLetterHasItsOwnNodeId() {
        final Set<Long> ids = new HashSet<>();
        for (char letter = 'a'; letter <= 'z'; letter++) {
            assertThat(ids.add(NamedRoster.nodeIdFor(String.valueOf(letter)).id()))
                    .as("'%s' must not share a node id with an earlier letter", letter)
                    .isTrue();
        }
        assertThatThrownBy(() -> NamedRoster.nodeIdFor("A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NamedRoster.nodeIdFor("ab")).isInstanceOf(IllegalArgumentException.class);
    }
}
