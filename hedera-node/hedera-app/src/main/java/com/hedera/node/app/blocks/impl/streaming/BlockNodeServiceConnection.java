package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.ServerStatusRequest;
import org.hiero.block.api.ServerStatusResponse;

public class BlockNodeServiceConnection extends AbstractBlockNodeConnection {

    private static final Logger logger = LogManager.getLogger(BlockNodeServiceConnection.class);

    private final AtomicReference<BlockNodeServiceClient> clientRef = new AtomicReference<>();
    private final ExecutorService pipelineExecutor;
    private final BlockNodeClientFactory clientFactory;
    private final BlockNodeConnectionConfig bncConfig;

    public BlockNodeServiceConnection(@NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfiguration nodeConfig,
            @NonNull final ExecutorService pipelineExecutor,
            @NonNull final BlockNodeClientFactory clientFactory) {
        super(ConnectionType.SERVER_STATUS, nodeConfig, configProvider);
        this.pipelineExecutor = requireNonNull(pipelineExecutor, "pipeline executor is required");
        this.clientFactory = requireNonNull(clientFactory, "client factory is required");

        bncConfig = configProvider()
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class);
    }

    @Override
    void initialize() {
        if (clientRef.get() != null) {
            logger.debug("{} Client already initialized", this);
            return;
        }

        final Duration timeoutDuration = configProvider()
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .grpcOverallTimeout();

        final Future<?> future = pipelineExecutor.submit(() -> {
            final BlockNodeServiceClient client = clientFactory.createServiceClient(configuration(), timeoutDuration);
            if (clientRef.compareAndSet(null, client)) {
                logger.debug("{} Client initialized", this);
                updateConnectionState(ConnectionState.READY);
            } else {
                client.close();
            }
        });

        try {
            future.get(bncConfig.pipelineOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            logger.warn("{} Client initialization timed out (timeout={})", this, bncConfig.pipelineOperationTimeout());
            future.cancel(true);
            throw new RuntimeException("Error initializing client", e);
        } catch (final InterruptedException e) {
            logger.warn("{} Client initialization interrupted", this);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Error initializing client", e);
        } catch (final ExecutionException e) {
            logger.warn("{} Error initializing client", this, e.getCause());
            throw new RuntimeException("Error initializing client", e);
        }
    }

    @Override
    void close() {
        final BlockNodeServiceClient client = clientRef.get();

        if (client == null || currentState().isTerminal()) {
            // either close has already been called or close was called while the connection wasn't initialized
            return;
        }

        logger.info("{} Closing connection", this);
        updateConnectionState(ConnectionState.CLOSING);

        if (!clientRef.compareAndSet(client, null)) {
            logger.debug("{} Another thread has closed the connection", this);
        }

        final Future<?> future = pipelineExecutor.submit(client::close);

        try {
            future.get(bncConfig.pipelineOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            // the connection is being closed... don't propagate the exception
            logger.warn("{} Error occurred while closing connection; it will be suppressed", this, e);

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            future.cancel(true);
        } finally {
            // regardless of outcome, mark this connection as closed
            updateConnectionState(ConnectionState.CLOSED);
        }
    }

    public BlockNodeStatus getBlockNodeStatus() {
        final BlockNodeServiceClient client = clientRef.get();

        if (client == null || currentState() != ConnectionState.ACTIVE) {
            logger.debug("{} Tried to retrieve block node status, but this connection is not active", this);
            return null;
        }

        final long startMillis = System.currentTimeMillis();
        final Future<ServerStatusResponse> future = pipelineExecutor.submit(() -> client.serverStatus(new ServerStatusRequest()));
        final ServerStatusResponse response;
        final long durationMillis;

        try {
            response = future.get(bncConfig.pipelineOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            durationMillis = System.currentTimeMillis() - startMillis;
        } catch (final TimeoutException e) {
            logger.warn("{} Timed out trying to retrieve server status (timeout={})", this, bncConfig.pipelineOperationTimeout());
            future.cancel(true);
            return BlockNodeStatus.notReachable();
        } catch (final InterruptedException e) {
            logger.warn("{} Interrupted while retrieving server status", this);
            Thread.currentThread().interrupt();
            return BlockNodeStatus.notReachable();
        } catch (final ExecutionException e) {
            logger.warn("{} Error occurred while retrieving server status", this, e);
            return BlockNodeStatus.notReachable();
        }

        logger.debug("{} Received the following block node server status: lastAvailableBlock={} (latency={}ms)",
                this, response.lastAvailableBlock(), durationMillis);

        return BlockNodeStatus.reachable(durationMillis, response.lastAvailableBlock());
    }
}
