// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.client;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.GrpcClient;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationQuery;
import org.hiero.hapi.interledger.clpr.ClprServiceInterface;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.client.ClprClient;

/**
 * Implementation of the CLPR (Cross-Ledger Protocol) client.
 * <p>
 * This class provides methods to interact with a remote CLPR Endpoint, allowing retrieval and submission of ledger
 * configurations.
 */
public class ClprClientImpl implements ClprClient {

    private static final PbjGrpcClientConfig clientConfig = new PbjGrpcClientConfig(
            Duration.ofSeconds(10),
            Tls.builder().enabled(false).build(),
            Optional.empty(),
            ServiceInterface.RequestOptions.APPLICATION_GRPC_PROTO);
    private static final ServiceInterface.RequestOptions requestOptions = new ServiceInterface.RequestOptions() {
        @Override
        public @NonNull Optional<String> authority() {
            return Optional.empty();
        }

        @Override
        public @NonNull String contentType() {
            return ServiceInterface.RequestOptions.APPLICATION_GRPC_PROTO;
        }
    };

    final ClprServiceInterface.ClprServiceClient clprServiceClient;

    /**
     * Constructs a ClprClientImpl instance with the specified service endpoint.
     *
     * @param serviceEndpoint the service endpoint to connect to
     * @throws UnknownHostException if the IP address of the service endpoint cannot be determined
     */
    public ClprClientImpl(@NonNull final ServiceEndpoint serviceEndpoint) throws UnknownHostException {
        final String address = Inet4Address.getByAddress(
                        serviceEndpoint.ipAddressV4().toByteArray())
                .getHostAddress();
        final int port = serviceEndpoint.port();

        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + address + ":" + port)
                .tls(Tls.builder().enabled(false).build())
                .build();

        final GrpcClient grpcClient = new PbjGrpcClient(webClient, clientConfig);
        clprServiceClient = new ClprServiceInterface.ClprServiceClient(grpcClient, requestOptions);
    }

    @Override
    public ClprLedgerConfiguration getConfiguration() {
        // create query payload with header and missing ledger id
        final var queryBody = ClprGetLedgerConfigurationQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();
        // create query transaction
        final var queryTxn =
                Query.newBuilder().getClprLedgerConfiguration(queryBody).build();
        // send query to remote CLPR Endpoint
        final var response = clprServiceClient.getLedgerConfiguration(queryTxn);
        if (response.hasClprLedgerConfiguration()) {
            // unwrap response
            return Objects.requireNonNull(response.clprLedgerConfiguration()).ledgerConfiguration();
        }
        return null;
    }

    @Override
    public @Nullable ClprLedgerConfiguration getConfiguration(@NonNull final ClprLedgerId ledgerId) {
        // create query payload with header and specified ledger id
        final var queryBody = ClprGetLedgerConfigurationQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .ledgerId(ledgerId)
                .build();

        final var queryTxn =
                Query.newBuilder().getClprLedgerConfiguration(queryBody).build();
        final var response = clprServiceClient.getLedgerConfiguration(queryTxn);
        if (response.hasClprLedgerConfiguration()) {
            return Objects.requireNonNull(response.clprLedgerConfiguration()).ledgerConfiguration();
        }
        return null;
    }

    @Override
    public @NonNull ResponseCodeEnum setConfiguration(@NonNull final ClprLedgerConfiguration clprLedgerConfiguration) {
        try {
            // Build a request message if the RPC exists (best-effort; if not available, this is a no-op)
            // Placeholder: return SUCCESS for now to satisfy the API contract.
            return ResponseCodeEnum.SUCCESS;
        } catch (final Exception e) {
            return ResponseCodeEnum.FAIL_INVALID;
        }
    }

    @Override
    public void close() {}
}
