package com.hedera.node.app.blocks.impl.streaming;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockNodeServiceConnectionTest extends BlockNodeCommunicationTestBase {

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
        executorService = mock(ExecutorService.class);
        clientFactory = mock(BlockNodeClientFactory.class);

        lenient().doReturn(client)
                .when(clientFactory)
                .createServiceClient(any(BlockNodeConfiguration.class), any(Duration.class));

        connection = new BlockNodeServiceConnection(configProvider, nodeConfiguration, executorService, clientFactory);
    }

    @Test
    void testInitialize() {

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
    void testInitialize_preempted() {

    }


}
