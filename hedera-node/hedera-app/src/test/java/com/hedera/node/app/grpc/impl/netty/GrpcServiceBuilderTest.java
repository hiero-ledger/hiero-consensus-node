// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for the {@link GrpcServiceBuilder}. Since the gRPC system deals in bytes, these tests use simple strings
 * as the input and output values, doing simple conversion to and from byte arrays.
 */
@ExtendWith(MockitoExtension.class)
final class GrpcServiceBuilderTest {
    private static final String SERVICE_NAME = "TestService";
    // These are simple no-op workflows
    private final QueryWorkflow queryWorkflow = (requestBuffer, responseBuffer) -> {};
    private final IngestWorkflow ingestWorkflow = (requestBuffer, responseBuffer) -> {};

    private GrpcServiceBuilder builder;
    private final Metrics metrics = TestUtils.metrics();

    private final VersionedConfiguration configuration =
            new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
    private final ConfigProvider configProvider = () -> configuration;
    private static final int MAX_MESSAGE_SIZE = 6144;
    private static final int MAX_JUMBO_TXN_SIZE = 133120;
    private static final int BUFFER_CAPACITY = 133120;
    private final DataBufferMarshaller MARSHALLER = new DataBufferMarshaller(BUFFER_CAPACITY, MAX_MESSAGE_SIZE);
    private final DataBufferMarshaller JUMBO_MARSHALLER = new DataBufferMarshaller(BUFFER_CAPACITY, MAX_JUMBO_TXN_SIZE);

    @BeforeEach
    void setUp() {
        builder = new GrpcServiceBuilder(SERVICE_NAME, ingestWorkflow, queryWorkflow, MARSHALLER, JUMBO_MARSHALLER);
    }

    @Test
    @DisplayName("The queryWorkflow cannot be null")
    void queryWorkflowIsNull() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new GrpcServiceBuilder(SERVICE_NAME, ingestWorkflow, null, MARSHALLER, JUMBO_MARSHALLER));
    }

    @Test
    @DisplayName("The 'service' cannot be null")
    void serviceIsNull() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new GrpcServiceBuilder(null, ingestWorkflow, queryWorkflow, MARSHALLER, JUMBO_MARSHALLER));
    }

    @ParameterizedTest()
    @ValueSource(strings = {"", " ", "\t", "\n", "\r", "\r\n", "  \n  "})
    @DisplayName("The 'service' cannot be blank")
    void serviceIsBlank(final String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GrpcServiceBuilder(value, ingestWorkflow, queryWorkflow, MARSHALLER, JUMBO_MARSHALLER));
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
        assertNotNull(builder.build(metrics, configProvider));
    }

    /**
     * A {@link GrpcServiceBuilder} may define transactions which will be created on the
     * {@link io.grpc.ServerServiceDefinition}.
     */
    @Test
    @DisplayName("The built ServiceDescriptor includes a method with the name of the defined" + " transaction")
    void singleTransaction() {
        final var sd = builder.transaction("txA").build(metrics, configProvider);
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
                .build(metrics, configProvider);

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
        final var sd = builder.transaction("txA").transaction("txA").build(metrics, configProvider);

        assertNotNull(sd.getMethod(SERVICE_NAME + "/txA"));
    }

    @Test
    @DisplayName("Calling `query` with the same name twice is idempotent")
    void duplicateQuery() {
        final var sd = builder.query("qA").query("qA").build(metrics, configProvider);

        assertNotNull(sd.getMethod(SERVICE_NAME + "/qA"));
    }

    @Test
    @DisplayName("Building a service with a jumbo transaction")
    void buildDefinitionWithJumboSizedMethod() {
        final var config = enableJumboTransactions();
        // add one regular and one jumbo transactions
        final var sd = builder.transaction("txnA").transaction("callEthereum").build(metrics, () -> config);

        final var arr = TestUtils.randomBytes(1024 * 1024);
        final var stream = new ByteArrayInputStream(arr);

        // parse normal transaction
        final var marshaller = (DataBufferMarshaller)
                sd.getMethod(SERVICE_NAME + "/txnA").getMethodDescriptor().getRequestMarshaller();
        final var buff = marshaller.parse(stream);
        // assert the buffer size limit
        assertThat(buff.length()).isEqualTo(MAX_MESSAGE_SIZE + 1);

        // parse jumbo transaction
        final var jumboMarshaller = (DataBufferMarshaller) sd.getMethod(SERVICE_NAME + "/callEthereum")
                .getMethodDescriptor()
                .getRequestMarshaller();
        final var jumboBuff = jumboMarshaller.parse(stream);
        // assert the buffer size limits
        assertThat(jumboBuff.length()).isEqualTo(MAX_JUMBO_TXN_SIZE + 1);
    }

    private VersionedConfiguration enableJumboTransactions() {
        final var spyConfig = spy(configuration);
        final var jumboConfig = configuration.getConfigData(JumboTransactionsConfig.class);
        final var spyJumboConfig = spy(jumboConfig);
        when(spyConfig.getConfigData(JumboTransactionsConfig.class)).thenReturn(spyJumboConfig);
        when(spyJumboConfig.isEnabled()).thenReturn(true);
        return spyConfig;
    }
}
