package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeServiceConnectionTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle clientRefHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            clientRefHandle = MethodHandles.privateLookupIn(BlockNodeServiceConnection.class, lookup)
                    .findVarHandle(BlockNodeServiceConnection.class, "clientRef", AtomicReference.class);
        } catch (final Exception e) {
           if (e instanceof final RuntimeException re) {
               throw re;
           } else {
               throw new RuntimeException(e);
           }
        }
    }

    private BlockNodeServiceConnection connection;
    private ConfigProvider configProvider;
    private BlockNodeConfiguration nodeConfiguration;
    private ExecutorService executorService;
    private BlockNodeClientFactory clientFactory;
    private BlockNodeServiceClient client;

    @BeforeEach
    void beforeEach() {
        configProvider = createConfigProvider(createDefaultConfigProvider());
        nodeConfiguration = newBlockNodeConfig(8080, 1);
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        clientFactory = mock(BlockNodeClientFactory.class);

        lenient().doReturn(client)
                .when(clientFactory)
                .createServiceClient(any(BlockNodeConfiguration.class), any(Duration.class));

        connection = new BlockNodeServiceConnection(configProvider, nodeConfiguration, executorService, clientFactory);
    }

    @AfterEach
    void afterEach() {
        executorService.shutdownNow();
        connection.updateConnectionState(ConnectionState.CLOSED);
    }

    @Test
    void testInitialize() {
        assertThat(clientRef()).hasNullValue();

        connection.initialize();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(clientRef()).hasValue(client);

        verify(clientFactory).createServiceClient(eq(nodeConfiguration), any(Duration.class));


        verifyNoMoreInteractions(clientFactory);

    }

    @Test
    void testInitialize_alreadyInitialized() {

    }

    @Test
    void testInitialize_timeout() {

    }

    @Test
    void testInitialize_interrupted() {

    }

    @Test
    void testInitialize_error() {

    }

    @Test
    void testInitialize_concurrentInitializations() {

    }

    // Utils =======

    @SuppressWarnings("unchecked")
    private AtomicReference<BlockNodeServiceClient> clientRef() {
        return (AtomicReference<BlockNodeServiceClient>) clientRefHandle.get(connection);
    }
}
