// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.metrics.api.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link GrpcServiceBuilder}. Since the gRPC system deals in bytes, these tests use simple strings
 * as the input and output values, doing simple conversion to and from byte arrays.
 */
final class GrpcServiceBuilderTest {
    private static final String SERVICE_NAME = "TestService";
    private static final ThreadLocal<BufferedData> BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> BufferedData.allocate(6145));
    private static final DataBufferMarshaller MARSHALLER = new DataBufferMarshaller(6144, BUFFER_THREAD_LOCAL::get);

    // These are simple no-op workflows
    private final QueryWorkflow queryWorkflow = (requestBuffer, responseBuffer) -> {};
    private final IngestWorkflow ingestWorkflow = (requestBuffer, responseBuffer) -> {};

    private GrpcServiceBuilder builder;
    private final Metrics metrics = TestUtils.metrics();

    @BeforeEach
    void setUp() {
        builder = new GrpcServiceBuilder(SERVICE_NAME, ingestWorkflow, queryWorkflow, MARSHALLER);
    }

    @Test
    @DisplayName("The queryWorkflow cannot be null")
    void queryWorkflowIsNull() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new GrpcServiceBuilder(SERVICE_NAME, ingestWorkflow, null, MARSHALLER));
    }

    @Test
    @DisplayName("The 'service' cannot be null")
    void serviceIsNull() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new GrpcServiceBuilder(null, ingestWorkflow, queryWorkflow, MARSHALLER));
    }

    @ParameterizedTest()
    @ValueSource(strings = {"", " ", "\t", "\n", "\r", "\r\n", "  \n  "})
    @DisplayName("The 'service' cannot be blank")
    void serviceIsBlank(final String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GrpcServiceBuilder(value, ingestWorkflow, queryWorkflow, MARSHALLER));
    }

    @Test
    @DisplayName("Cannot call 'transaction' with null")
    void transactionIsNull() {
        //noinspection ConstantConditions
        assertThrows(NullPointerException.class, () -> builder.transaction(null));
    }

    @ParameterizedTest()
    @ValueSource(strings = {"", " ", "\t", "\n", "\r", "\r\n", "  \n  "})
    @DisplayName("Cannot call 'transaction' with blank")
    void transactionIsBlank(final String value) {
        assertThrows(IllegalArgumentException.class, () -> builder.transaction(value));
    }

    @Test
    @DisplayName("Cannot call 'query' with null")
    void queryIsNull() {
        //noinspection ConstantConditions
        assertThrows(NullPointerException.class, () -> builder.query(null));
    }

    @ParameterizedTest()
    @ValueSource(strings = {"", " ", "\t", "\n", "\r", "\r\n", "  \n  "})
    @DisplayName("Cannot call 'query' with blank")
    void queryIsBlank(final String value) {
        assertThrows(IllegalArgumentException.class, () -> builder.query(value));
    }

    /**
     * A builder with no transactions and queries still creates and returns a {@link io.grpc.ServerServiceDefinition}.
     */
    @Test
    @DisplayName("The build method will return a ServiceDescriptor")
    void serviceDescriptorIsNotNullOnNoopBuilder() {
        assertNotNull(builder.build(metrics, 6144));
    }

    /**
     * A {@link GrpcServiceBuilder} may define transactions which will be created on the
     * {@link io.grpc.ServerServiceDefinition}.
     */
    @Test
    @DisplayName("The built ServiceDescriptor includes a method with the name of the defined" + " transaction")
    void singleTransaction() {
        final var sd = builder.transaction("txA").build(metrics, 6144);
        assertNotNull(sd.getMethod(SERVICE_NAME + "/txA"));
    }

    /**
     * A {@link GrpcServiceBuilder} may define transactions which will be created on the
     * {@link io.grpc.ServerServiceDefinition}.
     */
    @Test
    @DisplayName("The built ServiceDescriptor includes all methods defined by the builder")
    void multipleTransactionsAndQueries() {
        final var sd = builder.transaction("txA")
                .transaction("txB")
                .query("qA")
                .query("qB")
                .transaction("txC")
                .query("qC")
                .transaction("txD")
                .build(metrics, 6144);

        assertNotNull(sd.getMethod(SERVICE_NAME + "/txA"));
        assertNotNull(sd.getMethod(SERVICE_NAME + "/txB"));
        assertNotNull(sd.getMethod(SERVICE_NAME + "/txC"));
        assertNotNull(sd.getMethod(SERVICE_NAME + "/txD"));
        assertNotNull(sd.getMethod(SERVICE_NAME + "/qA"));
        assertNotNull(sd.getMethod(SERVICE_NAME + "/qB"));
        assertNotNull(sd.getMethod(SERVICE_NAME + "/qC"));
    }

    @Test
    @DisplayName("Calling `transaction` with the same name twice is idempotent")
    void duplicateTransaction() {
        final var sd = builder.transaction("txA").transaction("txA").build(metrics, 6144);

        assertNotNull(sd.getMethod(SERVICE_NAME + "/txA"));
    }

    @Test
    @DisplayName("Calling `query` with the same name twice is idempotent")
    void duplicateQuery() {
        final var sd = builder.query("qA").query("qA").build(metrics, 6144);

        assertNotNull(sd.getMethod(SERVICE_NAME + "/qA"));
    }
}
