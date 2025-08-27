// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.remote.RemoteNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.PrometheusClient;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A network of Hedera nodes.
 */
public interface HederaNetwork {
    /**
     * Returns the best known representation of the properties in use when the network was started; which is
     * an important detail when trying to temporarily override configuration for a test.
     * <p>
     * <b>NOTE:</b> The current {@link RemoteNetwork} implementation does not try to guarantee an accurate
     * response here, and just uses the node software defaults. This doesn't matter for any of the few known
     * use cases of a {@link HapiSpec} against remote network.
     *
     * @return a best-effort picture of the properties in use when the network was started
     */
    @NonNull
    HapiPropertySource startupProperties();

    /**
     * Sends the given query to the network node with the given account id as if it
     * was the given functionality. Blocks until the response is available.
     *
     * <p>For valid queries, the functionality can be inferred; but for invalid queries,
     * the functionality must be provided.
     *
     * @param query the query
     * @param functionality the functionality to use
     * @param nodeAccountId the account id of the node to send the query to
     * @return the network's response
     */
    @NonNull
    default Response send(
            @NonNull Query query, @NonNull HederaFunctionality functionality, @NonNull AccountID nodeAccountId) {
        return send(query, functionality, nodeAccountId, false);
    }

    /**
     * Sends the given query to the network node with the given account id as if it
     * was the given functionality. Blocks until the response is available.
     *
     * <p>For valid queries, the functionality can be inferred; but for invalid queries,
     * the functionality must be provided.
     *
     * @param query the query
     * @param functionality the functionality to use
     * @param nodeAccountId the account id of the node to send the query to
     * @param asNodeOperator whether to send the query to the node operator port
     * @return the network's response
     */
    @NonNull
    Response send(
            @NonNull Query query,
            @NonNull HederaFunctionality functionality,
            @NonNull AccountID nodeAccountId,
            boolean asNodeOperator);

    /**
     * Submits the given transaction to the network node with the given account id as if it
     * was the given functionality. Blocks until the response is available.
     *
     * <p>For valid transactions, the functionality can be inferred; but for invalid transactions,
     * the functionality must be provided.
     *
     * @param transaction the transaction
     * @param functionality the functionality to use
     * @param target the target to use, given a system functionality
     * @param nodeAccountId the account id of the node to submit the transaction to
     * @return the network's response
     */
    TransactionResponse submit(
            @NonNull Transaction transaction,
            @NonNull HederaFunctionality functionality,
            @NonNull SystemFunctionalityTarget target,
            @NonNull AccountID nodeAccountId);

    /**
     * Returns the network type.
     *
     * @return the network type
     */
    TargetNetworkType type();

    /**
     * Returns the nodes of the network.
     *
     * @return the nodes of the network
     */
    List<HederaNode> nodes();

    /**
     * Returns the nodes of the network that match the given selector.
     *
     * @param selector the selector
     * @return the nodes that match the selector
     */
    default List<HederaNode> nodesFor(@NonNull final NodeSelector selector) {
        requireNonNull(selector);
        return nodes().stream().filter(selector).toList();
    }

    /**
     * Returns the node of the network that matches the given selector.
     *
     * @param selector the selector
     * @return the nodes that match the selector
     */
    default HederaNode getRequiredNode(@NonNull final NodeSelector selector) {
        requireNonNull(selector);
        return nodes().stream().filter(selector).findAny().orElseThrow();
    }

    /**
     * Returns the name of the network.
     *
     * @return the name of the network
     */
    String name();

    /**
     * Starts all nodes in the network.
     */
    void start();

    /**
     * Starts all nodes in the network with the given customizations.
     *
     * @param bootstrapOverrides the overrides
     */
    default void startWith(@NonNull final Map<String, String> bootstrapOverrides) {
        throw new UnsupportedOperationException();
    }

    /**
     * Forcibly stops all nodes in the network.
     */
    void terminate();

    /**
     * Waits for all nodes in the network to be ready within the given timeout.
     */
    void awaitReady(@NonNull Duration timeout);

    /**
     * Returns the shard of the network.
     * <p>
     * (FUTURE) Implement sensibly for non-{@link RemoteNetwork} implementations.
     */
    default long shard() {
        return 0;
    }

    /**
     * Returns the realm of the network.
     * <p>
     * (FUTURE) Implement sensibly for non-{@link RemoteNetwork} implementations.
     */
    default long realm() {
        return 0;
    }

    /**
     * Returns the Prometheus client for the network.
     * <p>
     * (FUTURE) Implement sensibly for non-{@link RemoteNetwork} implementations.
     *
     * @return the Prometheus client
     */
    PrometheusClient prometheusClient();
}
