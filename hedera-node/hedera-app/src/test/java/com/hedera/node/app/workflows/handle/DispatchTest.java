// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchTest {
    private static final Key KEY =
            Key.newBuilder().ed25519(Bytes.fromHex("11".repeat(32))).build();

    @Mock
    private Dispatch dispatch;

    @Test
    void defaultRequirementsAreExplicitKeyProjections() {
        given(dispatch.requiredKeys()).willReturn(Set.of(KEY));
        given(dispatch.requiredKeyRequirements()).willCallRealMethod();

        assertThat(dispatch.requiredKeyRequirements()).containsExactly(KeyRequirement.forKey(KEY));
    }
}
