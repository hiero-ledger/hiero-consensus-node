// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.test.cache;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.util.impl.cache.InnerTxnCache;
import com.hedera.node.app.service.util.impl.cache.TransactionParser;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class InnerTxnCacheTest {

    @Mock
    private TransactionParser transactionParser;

    @Mock
    private Configuration configuration;

    @Mock
    private TransactionBody mockTransactionBody;

    private InnerTxnCache innerTxnCache;
    private Bytes testBytes;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        innerTxnCache = new InnerTxnCache(transactionParser, configuration);
        testBytes = Bytes.wrap(new byte[] {1, 2, 3, 4});
    }

    @Test
    void computeIfAbsent_happyPath() throws PreCheckException {
        // Arrange
        when(transactionParser.parse(testBytes, configuration)).thenReturn(mockTransactionBody);

        // Act
        TransactionBody result = innerTxnCache.computeIfAbsent(testBytes);

        // Assert
        assertSame(mockTransactionBody, result);
        verify(transactionParser).parse(testBytes, configuration);
    }

    @Test
    void computeIfAbsent_propagatesPreCheckException() throws PreCheckException {
        // Arrange
        PreCheckException expectedException = new PreCheckException(INVALID_TRANSACTION_BODY);
        when(transactionParser.parse(testBytes, configuration)).thenThrow(expectedException);

        // Act & Assert
        PreCheckException thrownException =
                assertThrows(PreCheckException.class, () -> innerTxnCache.computeIfAbsent(testBytes));

        // Verify it's the same exception instance
        assertSame(expectedException, thrownException);
    }

    @Test
    void computeIfAbsentUnchecked_wrapsPreCheckException() throws PreCheckException {
        // Arrange
        when(transactionParser.parse(testBytes, configuration))
                .thenThrow(new PreCheckException(INVALID_TRANSACTION_BODY));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> innerTxnCache.computeIfAbsentUnchecked(testBytes));
    }

    @Test
    void cachingBehavior_parsesOnlyOnce() throws PreCheckException {
        // Arrange
        when(transactionParser.parse(testBytes, configuration)).thenReturn(mockTransactionBody);

        // Act
        TransactionBody result1 = innerTxnCache.computeIfAbsent(testBytes);
        TransactionBody result2 = innerTxnCache.computeIfAbsent(testBytes);

        // Assert
        assertSame(mockTransactionBody, result1);
        assertSame(mockTransactionBody, result2);
        // Verify parser was called only once
        verify(transactionParser, times(1)).parse(testBytes, configuration);
    }

    @Test
    void computeIfAbsent_rethrowsOtherExceptions() throws PreCheckException {
        // Arrange
        RuntimeException expectedException = new RuntimeException("Other exception");
        when(transactionParser.parse(testBytes, configuration)).thenThrow(expectedException);

        // Verify exception chain contains our original exception
        Throwable cause = assertThrows(RuntimeException.class, () -> innerTxnCache.computeIfAbsent(testBytes));
        boolean foundOriginalException = false;
        while (cause != null) {
            if (cause == expectedException) {
                foundOriginalException = true;
                break;
            }
            cause = cause.getCause();
        }

        assertTrue(foundOriginalException, "Original exception should be in the exception chain");
    }
}
