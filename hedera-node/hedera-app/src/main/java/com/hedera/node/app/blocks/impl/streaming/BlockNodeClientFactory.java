package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.webclient.api.WebClient;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;

public class BlockNodeClientFactory {

    public BlockStreamPublishServiceClient createClient(@NonNull final WebClient webClient, @NonNull final PbjGrpcClientConfig config, @NonNull final ServiceInterface.RequestOptions requestOptions) {
        return new BlockStreamPublishServiceClient(new PbjGrpcClient(webClient, config), requestOptions);
    }

}
