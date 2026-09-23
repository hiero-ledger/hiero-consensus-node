// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor.ONE_TO_ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ThrottleReqsManagerTest {

    @Mock
    private DeterministicThrottle throttle;

    /**
     * The scaled requirement handed to a bucket must reflect the true product of the logical-operation
     * count and the per-transaction ops requirement. When that product exceeds {@link Integer#MAX_VALUE}
     * it must be clamped (by {@link com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor})
     * rather than wrapping: an int-only multiplication would wrap to a negative value that
     * {@code scaling(...)} then floors to 1, silently under-counting the requirement.
     */
    @Test
    void scalingUsesLongArithmeticForLargeLogicalOps() {
        final int opsRequired = 3;
        // 800_000_000 * 3 = 2_400_000_000, which is greater than Integer.MAX_VALUE (2_147_483_647).
        final int nTransactions = 800_000_000;
        final var subject = new ThrottleReqsManager(List.of(Pair.of(throttle, opsRequired)));

        subject.allReqsMetAt(Instant.EPOCH, nTransactions, ONE_TO_ONE, null);

        final var scaledOps = ArgumentCaptor.forClass(Integer.class);
        verify(throttle).allow(scaledOps.capture(), any());
        assertThat(scaledOps.getValue()).isEqualTo(Integer.MAX_VALUE);
    }
}
