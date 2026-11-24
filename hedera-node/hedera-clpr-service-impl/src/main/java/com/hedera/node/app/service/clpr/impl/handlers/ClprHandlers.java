// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Holds all CLPR service handlers for dependency injection.
 */
@Singleton
public class ClprHandlers {

    private final ClprUpdateLedgerConfigurationHandler clprUpdateLedgerConfigurationHandler;
    private final ClprGetLedgerConfigurationHandler clprGetLedgerConfigurationHandler;
    private final ClprCreateChannelHandler clprCreateChannelHandler;
    private final ClprDeleteChannelHandler clprDeleteChannelHandler;
    private final ClprEvictPeerEndpointHandler clprEvictPeerEndpointHandler;
    private final ClprSubmitBundleHandler clprSubmitBundleHandler;
    private final ClprCreateConnectorHandler clprCreateConnectorHandler;
    private final ClprDeleteConnectorHandler clprDeleteConnectorHandler;
    private final ClprEndpointPublicationHandler clprEndpointPublicationHandler;
    private final ClprGetEndpointManifestHandler clprGetEndpointManifestHandler;

    /**
     * Constructor for ClprHandlers.
     *
     * @param clprUpdateLedgerConfigurationHandler the handler for updating ledger configuration
     * @param clprGetLedgerConfigurationHandler the handler for getting ledger configuration
     * @param clprCreateChannelHandler the handler for opening channels
     * @param clprDeleteChannelHandler the handler for closing channels
     * @param clprSubmitBundleHandler the handler for submitting bundles from peer syncs
     * @param clprCreateConnectorHandler the handler for creating connectors
     * @param clprDeleteConnectorHandler the handler for deleting connectors
     * @param clprEndpointPublicationHandler the handler for node CLPR endpoint self-publications
     * @param clprGetEndpointManifestHandler the handler for the getEndpointManifest query (spec §6.5)
     * @param clprEvictPeerEndpointHandler the handler for evicting a peer endpoint from a channel's manifest
     */
    @Inject
    public ClprHandlers(
            @NonNull final ClprUpdateLedgerConfigurationHandler clprUpdateLedgerConfigurationHandler,
            @NonNull final ClprGetLedgerConfigurationHandler clprGetLedgerConfigurationHandler,
            @NonNull final ClprCreateChannelHandler clprCreateChannelHandler,
            @NonNull final ClprDeleteChannelHandler clprDeleteChannelHandler,
            @NonNull final ClprSubmitBundleHandler clprSubmitBundleHandler,
            @NonNull final ClprCreateConnectorHandler clprCreateConnectorHandler,
            @NonNull final ClprDeleteConnectorHandler clprDeleteConnectorHandler,
            @NonNull final ClprEndpointPublicationHandler clprEndpointPublicationHandler,
            @NonNull final ClprGetEndpointManifestHandler clprGetEndpointManifestHandler,
            @NonNull final ClprEvictPeerEndpointHandler clprEvictPeerEndpointHandler) {
        this.clprUpdateLedgerConfigurationHandler = Objects.requireNonNull(
                clprUpdateLedgerConfigurationHandler, "clprUpdateLedgerConfigurationHandler must not be null");
        this.clprGetLedgerConfigurationHandler = Objects.requireNonNull(
                clprGetLedgerConfigurationHandler, "clprGetLedgerConfigurationHandler must not be null");
        this.clprCreateChannelHandler =
                Objects.requireNonNull(clprCreateChannelHandler, "clprCreateChannelHandler must not be null");
        this.clprDeleteChannelHandler =
                Objects.requireNonNull(clprDeleteChannelHandler, "clprDeleteChannelHandler must not be null");
        this.clprSubmitBundleHandler =
                Objects.requireNonNull(clprSubmitBundleHandler, "clprSubmitBundleHandler must not be null");
        this.clprCreateConnectorHandler =
                Objects.requireNonNull(clprCreateConnectorHandler, "clprCreateConnectorHandler must not be null");
        this.clprDeleteConnectorHandler =
                Objects.requireNonNull(clprDeleteConnectorHandler, "clprDeleteConnectorHandler must not be null");
        this.clprEndpointPublicationHandler = Objects.requireNonNull(
                clprEndpointPublicationHandler, "clprEndpointPublicationHandler must not be null");
        this.clprGetEndpointManifestHandler = Objects.requireNonNull(
                clprGetEndpointManifestHandler, "clprGetEndpointManifestHandler must not be null");
        this.clprEvictPeerEndpointHandler =
                Objects.requireNonNull(clprEvictPeerEndpointHandler, "clprEvictPeerEndpointHandler must not be null");
    }

    /**
     * Gets the handler for updating ledger configuration.
     *
     * @return the update ledger configuration handler
     */
    public ClprUpdateLedgerConfigurationHandler clprUpdateLedgerConfigurationHandler() {
        return clprUpdateLedgerConfigurationHandler;
    }

    /**
     * Gets the handler for getting ledger configuration.
     *
     * @return the get ledger configuration handler
     */
    public ClprGetLedgerConfigurationHandler clprGetLedgerConfigurationHandler() {
        return clprGetLedgerConfigurationHandler;
    }

    /**
     * Gets the handler for opening channels.
     *
     * @return the open channel handler
     */
    public ClprCreateChannelHandler clprCreateChannelHandler() {
        return clprCreateChannelHandler;
    }

    /**
     * Gets the handler for closing channels.
     *
     * @return the close channel handler
     */
    public ClprDeleteChannelHandler clprDeleteChannelHandler() {
        return clprDeleteChannelHandler;
    }

    /**
     * Gets the handler for submitting bundles from peer syncs.
     *
     * @return the submit bundle handler
     */
    public ClprSubmitBundleHandler clprSubmitBundleHandler() {
        return clprSubmitBundleHandler;
    }

    /**
     * Gets the handler for creating connectors.
     *
     * @return the create connector handler
     */
    public ClprCreateConnectorHandler clprCreateConnectorHandler() {
        return clprCreateConnectorHandler;
    }

    /**
     * Gets the handler for deleting connectors.
     *
     * @return the delete connector handler
     */
    public ClprDeleteConnectorHandler clprDeleteConnectorHandler() {
        return clprDeleteConnectorHandler;
    }

    /**
     * Gets the handler for node CLPR endpoint self-publications (design doc §6).
     *
     * @return the endpoint publication handler
     */
    public ClprEndpointPublicationHandler clprEndpointPublicationHandler() {
        return clprEndpointPublicationHandler;
    }

    /**
     * Gets the handler for the {@code getEndpointManifest} query (spec §6.5).
     *
     * @return the get endpoint manifest handler
     */
    public ClprGetEndpointManifestHandler clprGetEndpointManifestHandler() {
        return clprGetEndpointManifestHandler;
    }

    /**
     * Gets the handler for evicting a peer endpoint from a channel's cached manifest.
     *
     * @return the evict peer endpoint handler
     */
    public ClprEvictPeerEndpointHandler clprEvictPeerEndpointHandler() {
        return clprEvictPeerEndpointHandler;
    }
}
