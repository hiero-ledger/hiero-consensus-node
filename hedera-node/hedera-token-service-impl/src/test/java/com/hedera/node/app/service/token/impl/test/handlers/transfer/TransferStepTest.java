// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.token.impl.handlers.transfer.TransferStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferStepTest extends StepsBase {
    private TransferStep transferStep = transferContext -> {
        throw new UnsupportedOperationException();
    };

    @Test
    void assertKeysEmpty() {
        assertThat(transferStep.authorizingKeysIn(transferContext)).isEmpty();
    }
}
