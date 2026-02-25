// SPDX-License-Identifier: Apache-2.0
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeServiceConnection.CloseClientTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeServiceConnection.CreateClientTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeServiceConnection.GetBlockNodeStatusTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeServiceConnection.ServiceClientHolder;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.ServerStatusRequest;
import org.hiero.block.api.ServerStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeServiceConnectionTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle connectionStateHandle;
    private static final VarHandle clientRefHandle;
    private static final VarHandle closeTaskClientHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            clientRefHandle = MethodHandles.privateLookupIn(BlockNodeServiceConnection.class, lookup)
                    .findVarHandle(BlockNodeServiceConnection.class, "clientRef", AtomicReference.class);
            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);
            closeTaskClientHandle = MethodHandles.privateLookupIn(CloseClientTask.class, lookup)
                    .findVarHandle(CloseClientTask.class, "clientHolder", ServiceClientHolder.class);
        } catch (final Exception e) {
            if (e instanceof final RuntimeException re) {
                throw re;
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private BlockNodeServiceConnection connection;
    private BlockNodeConfiguration nodeConfiguration;
    private ExecutorService executorService;
    private BlockNodeClientFactory clientFactory;
    private BlockNodeServiceClient client;

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider());
        nodeConfiguration = newBlockNodeConfig(8080, 1);
        executorService = mock(ExecutorService.class);
        clientFactory = mock(BlockNodeClientFactory.class);
        client = mock(BlockNodeServiceClient.class);

        lenient()
                .doReturn(client)
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
    void testCreateClientTask_success() {
        assertThat(stateRef()).hasValue(ConnectionState.UNINITIALIZED);
        assertThat(clientRef()).hasNullValue();

        connection.new CreateClientTask().run();

        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);
        final ServiceClientHolder holder = clientRef().get();
        assertThat(holder).isNotNull();
        assertThat(holder.client()).isEqualTo(client);

        verify(clientFactory).createServiceClient(eq(nodeConfiguration), any(Duration.class));

        verifyNoInteractions(executorService);
        verifyNoInteractions(client);
        verifyNoMoreInteractions(clientFactory);
    }

    @Test
    void testCreateClientTask_concurrentMiss() {
        // this test simulates competing threads trying to initialize the connection at the same time
        // the gate that prevents this is a CAS operation for setting the created client
        // if one thread fails the CAS operation, it will close the client and not set its client as the one to use
        final BlockNodeServiceClient clientA = mock(BlockNodeServiceClient.class);
        final ServiceClientHolder clientAHolder = new ServiceClientHolder(-10, clientA);
        clientRef().set(clientAHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final Future<?> closeFuture = CompletableFuture.completedFuture(null);
        doReturn(closeFuture).when(executorService).submit(any(CloseClientTask.class));

        // sanity check
        assertThat(clientRef()).hasValue(clientAHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        connection.new CreateClientTask().run();

        assertThat(clientRef()).hasValue(clientAHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        final ArgumentCaptor<? extends Runnable> execSvcCaptor = ArgumentCaptor.forClass(Runnable.class);

        verify(clientFactory).createServiceClient(eq(nodeConfiguration), any(Duration.class));
        verify(executorService).submit(execSvcCaptor.capture());

        assertThat(execSvcCaptor.getAllValues()).hasSize(1);
        final Runnable r = execSvcCaptor.getValue();
        assertThat(r).isNotNull().isInstanceOf(CloseClientTask.class);
        final CloseClientTask closeTask = (CloseClientTask) r;
        final ServiceClientHolder closedClientHolder = (ServiceClientHolder) closeTaskClientHandle.get(closeTask);
        assertThat(closedClientHolder.client()).isEqualTo(client);
        assertThat(clientRef()).hasValue(clientAHolder);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(client);
        verifyNoMoreInteractions(clientFactory);
    }

    @Test
    void testCreateClientTask_concurrentMiss_errorClosing() {
        // this test simulates competing threads trying to initialize the connection at the same time
        // the gate that prevents this is a CAS operation for setting the created client
        // if one thread fails the CAS operation, it will close the client and not set its client as the one to use
        // and in this case, when the close happens, a failure happens
        final BlockNodeServiceClient clientA = mock(BlockNodeServiceClient.class);
        final ServiceClientHolder clientAHolder = new ServiceClientHolder(-10, clientA);
        clientRef().set(clientAHolder);
        stateRef().set(ConnectionState.ACTIVE);

        doThrow(new RuntimeException("run boy run! the world is not made for you"))
                .when(executorService)
                .submit(any(CloseClientTask.class));

        // sanity check
        assertThat(clientRef()).hasValue(clientAHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        connection.new CreateClientTask().run();

        assertThat(clientRef()).hasValue(clientAHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        final ArgumentCaptor<? extends Runnable> execSvcCaptor = ArgumentCaptor.forClass(Runnable.class);

        verify(clientFactory).createServiceClient(eq(nodeConfiguration), any(Duration.class));
        verify(executorService).submit(execSvcCaptor.capture());

        assertThat(execSvcCaptor.getAllValues()).hasSize(1);
        final Runnable r = execSvcCaptor.getValue();
        assertThat(r).isNotNull().isInstanceOf(CloseClientTask.class);
        final CloseClientTask closeTask = (CloseClientTask) r;
        final ServiceClientHolder closedClientHolder = (ServiceClientHolder) closeTaskClientHandle.get(closeTask);
        assertThat(closedClientHolder.client()).isEqualTo(client);
        assertThat(clientRef()).hasValue(clientAHolder);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(client);
        verifyNoMoreInteractions(clientFactory);
    }

    @Test
    void testInitialize_success() {
        final Future<?> createFuture = CompletableFuture.completedFuture(null);
        doReturn(createFuture).when(executorService).submit(any(CreateClientTask.class));

        assertThat(clientRef()).hasNullValue();

        connection.initialize();

        verify(executorService).submit(any(CreateClientTask.class));

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
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
        doReturn(future).when(executorService).submit(any(CreateClientTask.class));
        doThrow(new TimeoutException()).when(future).get(anyLong(), any(TimeUnit.class));

        assertThatThrownBy(() -> connection.initialize())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Error initializing connection")
                .hasCauseInstanceOf(TimeoutException.class);

        verify(future).cancel(true);
        verify(executorService).submit(any(CreateClientTask.class));

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testInitialize_interrupted() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Future<?> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(CreateClientTask.class));
        doThrow(new InterruptedException()).when(future).get(anyLong(), any(TimeUnit.class));

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        final Thread thread = new Thread(() -> {
            try {
                connection.initialize();
            } catch (final Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(thread.isInterrupted()).isFalse();
        thread.start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(errorRef.get())
                .isNotNull()
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Error initializing connection")
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(thread.isInterrupted()).isTrue();

        verify(executorService).submit(any(CreateClientTask.class));
        verify(future).cancel(true);

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testInitialize_error() throws Exception {
        final Future<?> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(CreateClientTask.class));
        final RuntimeException error = new RuntimeException("oops, I did it again");
        doThrow(new ExecutionException(error)).when(future).get(anyLong(), any(TimeUnit.class));

        assertThatThrownBy(() -> connection.initialize())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Error initializing connection")
                .hasCause(error);

        verify(executorService).submit(any(CreateClientTask.class));
        verify(future).cancel(true);

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testCloseClientTask_success() {
        final ServiceClientHolder holder = new ServiceClientHolder(-10, client);

        connection.new CloseClientTask(holder).run();

        verify(client).close();
        verifyNoMoreInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoInteractions(executorService);
    }

    @Test
    void testCloseClientTask_nullClientHolder() {
        assertThatThrownBy(() -> connection.new CloseClientTask(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("client is required");
    }

    @Test
    void testClose_success() throws Exception {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);
        final Future<?> closeFuture = mock(Future.class);
        doReturn(closeFuture).when(executorService).submit(any(CloseClientTask.class));

        // sanity check
        assertThat(clientRef()).hasValue(clientHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        connection.close();

        assertThat(stateRef()).hasValue(ConnectionState.CLOSED);

        verify(executorService).submit(any(CloseClientTask.class));
        verify(closeFuture).get(anyLong(), eq(TimeUnit.MILLISECONDS));

        verifyNoMoreInteractions(closeFuture);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void testClose_notInitialized() {
        // don't initialize connection

        assertThat(stateRef()).hasValue(ConnectionState.UNINITIALIZED);

        connection.close();

        assertThat(stateRef()).hasValue(ConnectionState.UNINITIALIZED);

        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoInteractions(executorService);
    }

    @Test
    void testClose_error() throws Exception {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final Future<?> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(CloseClientTask.class));
        final RuntimeException error = new RuntimeException("well, this is awkward...");
        doThrow(new ExecutionException(error)).when(future).get(anyLong(), any(TimeUnit.class));

        // sanity check
        assertThat(clientRef()).hasValue(clientHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        connection.close();

        assertThat(stateRef()).hasValue(ConnectionState.CLOSED);
        assertThat(clientRef()).hasNullValue();

        verify(executorService).submit(any(CloseClientTask.class));
        verify(future).cancel(true);

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void testClose_interrupted() throws Exception {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final Future<?> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(CloseClientTask.class));
        doThrow(new InterruptedException()).when(future).get(anyLong(), any(TimeUnit.class));

        // sanity check
        assertThat(clientRef()).hasValue(clientHolder);
        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        final Thread thread = new Thread(() -> {
            try {
                connection.close();
            } catch (final Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(thread.isInterrupted()).isFalse();
        thread.start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.join(Duration.ofSeconds(2))).isTrue();

        assertThat(errorRef).hasNullValue(); // error should not be propagated
        assertThat(thread.isInterrupted()).isTrue();
        assertThat(stateRef()).hasValue(ConnectionState.CLOSED);
        assertThat(clientRef()).hasNullValue();

        verify(executorService).submit(any(CloseClientTask.class));
        verify(future).cancel(true);

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void testGetBlockNodeStatusTask_success() throws Exception {
        final ServerStatusResponse expectedResponse = new ServerStatusResponse(100, 200, false, null);
        doReturn(expectedResponse).when(client).serverStatus(any(ServerStatusRequest.class));

        final ServerStatusResponse actualResponse = connection.new GetBlockNodeStatusTask(client).call();

        assertThat(actualResponse).isEqualTo(expectedResponse);

        verify(client).serverStatus(any(ServerStatusRequest.class));
        verifyNoMoreInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoInteractions(executorService);
    }

    @Test
    void testGetBlockNodeStatusTask_nullClient() {
        assertThatThrownBy(() -> connection.new GetBlockNodeStatusTask(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("client is required");
    }

    @Test
    void testGetBlockNodeStatus() {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final Future<ServerStatusResponse> getFuture =
                CompletableFuture.completedFuture(new ServerStatusResponse(1234L, 2345L, false, null));
        doReturn(getFuture).when(executorService).submit(any(GetBlockNodeStatusTask.class));

        final BlockNodeStatus status = connection.getBlockNodeStatus();

        assertThat(status).isNotNull();
        assertThat(status.wasReachable()).isTrue();
        assertThat(status.latestBlockAvailable()).isEqualTo(2345L);
        assertThat(status.latencyMillis()).isGreaterThan(-1L);

        verify(executorService).submit(any(GetBlockNodeStatusTask.class));

        verifyNoInteractions(clientFactory);
        verifyNoInteractions(client);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testGetBlockNodeStatus_notActive() {
        stateRef().set(ConnectionState.UNINITIALIZED);

        final BlockNodeStatus status = connection.getBlockNodeStatus();

        assertThat(status).isNull();

        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
        verifyNoInteractions(executorService);
    }

    @Test
    void testGetBlockNodeStatus_timeout() throws Exception {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final Future<?> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(GetBlockNodeStatusTask.class));
        doThrow(new TimeoutException()).when(future).get(anyLong(), any(TimeUnit.class));

        final BlockNodeStatus status = connection.getBlockNodeStatus();

        assertThat(status).isNotNull();
        assertThat(status.wasReachable()).isFalse();
        assertThat(status.latestBlockAvailable()).isEqualTo(-1L);
        assertThat(status.latencyMillis()).isEqualTo(-1L);

        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE); // errors do not affect connection state

        verify(future).cancel(true);
        verify(executorService).submit(any(GetBlockNodeStatusTask.class));

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void testGetBlockNodeStatus_interrupted() throws Exception {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final CountDownLatch latch = new CountDownLatch(1);
        final Future<ServerStatusResponse> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(GetBlockNodeStatusTask.class));
        doThrow(new InterruptedException()).when(future).get(anyLong(), any(TimeUnit.class));

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final AtomicReference<BlockNodeStatus> statusRef = new AtomicReference<>();

        final Thread thread = new Thread(() -> {
            try {
                statusRef.set(connection.getBlockNodeStatus());
            } catch (final Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(thread.isInterrupted()).isFalse();
        thread.start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(errorRef.get()).isNull(); // error should not be propagated
        assertThat(thread.isInterrupted()).isTrue();

        final BlockNodeStatus status = statusRef.get();
        assertThat(status).isNotNull();
        assertThat(status.wasReachable()).isFalse();
        assertThat(status.latestBlockAvailable()).isEqualTo(-1L);
        assertThat(status.latencyMillis()).isEqualTo(-1L);

        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE); // errors do not affect connection state

        verify(executorService).submit(any(GetBlockNodeStatusTask.class));
        verify(future).cancel(true);
        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void testGetBlockNodeStatus_error() throws Exception {
        final ServiceClientHolder clientHolder = new ServiceClientHolder(1, client);
        clientRef().set(clientHolder);
        stateRef().set(ConnectionState.ACTIVE);

        final Future<?> future = mock(Future.class);
        doReturn(future).when(executorService).submit(any(GetBlockNodeStatusTask.class));
        final RuntimeException error = new RuntimeException("wtf, again!?");
        doThrow(new ExecutionException(error)).when(future).get(anyLong(), any(TimeUnit.class));

        final BlockNodeStatus status = connection.getBlockNodeStatus();

        assertThat(status).isNotNull();
        assertThat(status.wasReachable()).isFalse();
        assertThat(status.latestBlockAvailable()).isEqualTo(-1L);
        assertThat(status.latencyMillis()).isEqualTo(-1L);

        assertThat(stateRef()).hasValue(ConnectionState.ACTIVE); // errors do not affect connection state

        verify(executorService).submit(any(GetBlockNodeStatusTask.class));
        verify(future).cancel(true);

        verifyNoMoreInteractions(future);
        verifyNoInteractions(client);
        verifyNoInteractions(clientFactory);
    }

    // Utils =======

    @SuppressWarnings("unchecked")
    private AtomicReference<ServiceClientHolder> clientRef() {
        return (AtomicReference<ServiceClientHolder>) clientRefHandle.get(connection);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> stateRef() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }
}
