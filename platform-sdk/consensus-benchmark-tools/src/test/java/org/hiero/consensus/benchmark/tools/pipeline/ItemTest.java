// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ItemTest {

    @Test
    void evenlySpacedItemsStartAtTheEpoch() {
        assertThat(Item.evenlySpaced(4, 2))
                .extracting(Item::time)
                .containsExactly(
                        Instant.EPOCH,
                        Instant.EPOCH.plusMillis(500),
                        Instant.EPOCH.plusMillis(1_000),
                        Instant.EPOCH.plusMillis(1_500));
        assertThat(Item.evenlySpaced(0, 2)).isEmpty();
    }

    @Test
    void anItemIsItsOwnPristineInstance() {
        final Item item = new Item(Instant.EPOCH);

        assertThat(item.pristine()).isSameAs(item);
    }

    @Test
    void theCountAndTheRateAreChecked() {
        assertThatThrownBy(() -> Item.evenlySpaced(-1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Item.evenlySpaced(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Item.evenlySpaced(1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
