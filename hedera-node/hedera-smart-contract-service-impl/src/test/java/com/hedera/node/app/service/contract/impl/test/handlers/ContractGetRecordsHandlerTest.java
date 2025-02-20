// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.service.contract.impl.handlers.ContractGetRecordsHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractGetRecordsHandlerTest {
    @Mock
    private QueryContext context;

    @Test
    void validatesAsNotSupported() {
        final var subject = new ContractGetRecordsHandler();

        final var e = assertThrows(PreCheckException.class, () -> subject.validate(context));
        assertEquals(NOT_SUPPORTED, e.responseCode());
    }
}
