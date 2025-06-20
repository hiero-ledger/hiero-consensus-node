// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Singleton that provides access to the various handlers for the Network Admin Service.
 */
@Singleton
public class NetworkAdminHandlers {

    private final FreezeHandler freezeHandler;

    private final NetworkGetAccountDetailsHandler networkGetAccountDetailsHandler;

    private final NetworkGetByKeyHandler networkGetByKeyHandler;

    private final NetworkGetExecutionTimeHandler networkGetExecutionTimeHandler;

    private final NetworkGetVersionInfoHandler networkGetVersionInfoHandler;

    private final NetworkTransactionGetReceiptHandler networkTransactionGetReceiptHandler;

    private final NetworkTransactionGetRecordHandler networkTransactionGetRecordHandler;

    private final NetworkTransactionGetFastRecordHandler networkTransactionGetFastRecordHandler;

    private final NetworkUncheckedSubmitHandler networkUncheckedSubmitHandler;

    /**
     * Creates a new AdminHandlers.
     */
    @Inject
    public NetworkAdminHandlers(
            @NonNull final FreezeHandler freezeHandler,
            @NonNull final NetworkGetAccountDetailsHandler networkGetAccountDetailsHandler,
            @NonNull final NetworkGetByKeyHandler networkGetByKeyHandler,
            @NonNull final NetworkGetExecutionTimeHandler networkGetExecutionTimeHandler,
            @NonNull final NetworkGetVersionInfoHandler networkGetVersionInfoHandler,
            @NonNull final NetworkTransactionGetReceiptHandler networkTransactionGetReceiptHandler,
            @NonNull final NetworkTransactionGetRecordHandler networkTransactionGetRecordHandler,
            @NonNull final NetworkTransactionGetFastRecordHandler networkTransactionGetFastRecordHandler,
            @NonNull final NetworkUncheckedSubmitHandler networkUncheckedSubmitHandler) {
        this.freezeHandler = requireNonNull(freezeHandler, "freezeHandler must not be null");
        this.networkGetAccountDetailsHandler =
                requireNonNull(networkGetAccountDetailsHandler, "networkGetAccountDetailsHandler must not be null");
        this.networkGetByKeyHandler = requireNonNull(networkGetByKeyHandler, "networkGetByKeyHandler must not be null");
        this.networkGetExecutionTimeHandler =
                requireNonNull(networkGetExecutionTimeHandler, "networkGetExecutionTimeHandler must not be null");
        this.networkGetVersionInfoHandler =
                requireNonNull(networkGetVersionInfoHandler, "networkGetVersionInfoHandler must not be null");
        this.networkTransactionGetReceiptHandler = requireNonNull(
                networkTransactionGetReceiptHandler, "networkTransactionGetReceiptHandler must not be null");
        this.networkTransactionGetRecordHandler = requireNonNull(
                networkTransactionGetRecordHandler, "networkTransactionGetRecordHandler must not be null");
        this.networkTransactionGetFastRecordHandler = requireNonNull(
                networkTransactionGetFastRecordHandler, "networkTransactionGetFastRecordHandler must not be null");
        this.networkUncheckedSubmitHandler =
                requireNonNull(networkUncheckedSubmitHandler, "networkUncheckedSubmitHandler must not be null");
    }

    /**
     * Returns the freeze handler.
     *
     * @return the freeze handler
     */
    public FreezeHandler freezeHandler() {
        return freezeHandler;
    }

    /**
     * Returns the network get account details handler.
     *
     * @return the network get account details handler
     */
    public NetworkGetAccountDetailsHandler networkGetAccountDetailsHandler() {
        return networkGetAccountDetailsHandler;
    }

    /**
     * Returns the network get by key handler.
     * @return the network get by key handler
     */
    public NetworkGetByKeyHandler networkGetByKeyHandler() {
        return networkGetByKeyHandler;
    }

    /**
     * Returns the network get execution time handler.
     * @return the network get execution time handler
     */
    public NetworkGetExecutionTimeHandler networkGetExecutionTimeHandler() {
        return networkGetExecutionTimeHandler;
    }

    /**
     * Returns the network get version info handler.
     * @return the network get version info handler
     */
    public NetworkGetVersionInfoHandler networkGetVersionInfoHandler() {
        return networkGetVersionInfoHandler;
    }

    /**
     * Returns the network transaction get receipt handler.
     * @return the network transaction get receipt handler
     */
    public NetworkTransactionGetReceiptHandler networkTransactionGetReceiptHandler() {
        return networkTransactionGetReceiptHandler;
    }

    /**
     * Returns the network transaction get record handler.
     * @return the network transaction get record handler
     */
    public NetworkTransactionGetRecordHandler networkTransactionGetRecordHandler() {
        return networkTransactionGetRecordHandler;
    }

    /**
     * Returns the network unchecked submit handler.
     * @return the network unchecked submit handler
     */
    public NetworkUncheckedSubmitHandler networkUncheckedSubmitHandler() {
        return networkUncheckedSubmitHandler;
    }

    /**
     * Returns the network transaction get fast record handler.
     * @return the network transaction get fast record handler
     */
    public NetworkTransactionGetFastRecordHandler networkTransactionGetFastRecordHandler() {
        return networkTransactionGetFastRecordHandler;
    }
}
