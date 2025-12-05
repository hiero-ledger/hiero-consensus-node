package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeServiceConnectionTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle connectionStateHandle;
    private static final VarHandle clientRefHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            clientRefHandle = MethodHandles.privateLookupIn(BlockNodeServiceConnection.class, lookup)
                    .findVarHandle(BlockNodeServiceConnection.class, "clientRef", AtomicReference.class);
            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);
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
        executorService = spy(Executors.newVirtualThreadPerTaskExecutor());
        clientFactory = mock(BlockNodeClientFactory.class);
        client = mock(BlockNodeServiceClient.class);

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
        verify(executorService).submit(any(BlockNodeServiceConnection.CreateClientTask.class));

        verifyNoMoreInteractions(clientFactory);
        verifyNoInteractions(client);
        // Because we are using a real executor, we can't verify no more interactions
    }

    @Test
    void testInitialize_alreadyInitialized() {
        stateRef().set(ConnectionState.ACTIVE);

        connection.initialize();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);

        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoInteractions(executorService);
    }

    @Test
    void testInitialize_timeout() throws Exception {
        final Future<?> future = mock(Future.class);
        doReturn(future)
                .when(executorService)
                .submit(any(BlockNodeServiceConnection.CreateClientTask.class));
        doThrow(new TimeoutException())
                .when(future)
                .get(anyLong(), any(TimeUnit.class));

        assertThatThrownBy(() -> connection.initialize())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Error initializing client")
                .hasCauseInstanceOf(TimeoutException.class);

        verify(future).cancel(true);
        verify(executorService).submit(any(BlockNodeServiceConnection.CreateClientTask.class));

        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
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

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> stateRef() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }
}
