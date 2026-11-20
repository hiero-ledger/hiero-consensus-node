// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GeneralizedIndexTest {

    @ParameterizedTest(name = "gindex {0} → depth {1}, index {2}")
    @CsvSource({"1, 0, 0", "2, 1, 0", "3, 1, 1", "8, 3, 0", "15, 3, 7", "16, 4, 0"})
    @DisplayName("given a generalized index, then depth is floor(log2(gindex)) and index is gindex - 2^depth")
    void givenGeneralizedIndex_thenSplitsIntoDepthAndIndex(final int gindex, final int depth, final int index) {
        final GeneralizedIndex generalizedIndex = new GeneralizedIndex(gindex);

        assertThat(generalizedIndex.depth()).isEqualTo(depth);
        assertThat(generalizedIndex.index()).isEqualTo(index);
    }

    @ParameterizedTest(name = "gindex {0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("given a generalized index below 1, then the constructor rejects it")
    void givenGindexBelowOne_thenThrowsIllegalArgumentException(final int gindex) {
        assertThatThrownBy(() -> new GeneralizedIndex(gindex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generalized index must be at least 1, got " + gindex);
    }
}
