// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related handlers regarding contract operations in Hedera.
 */
@Singleton
public class ContractHandlers {

    private final ContractCallHandler contractCallHandler;

    private final ContractCallLocalHandler contractCallLocalHandler;

    private final ContractCreateHandler contractCreateHandler;

    private final ContractDeleteHandler contractDeleteHandler;

    private final ContractGetBySolidityIDHandler contractGetBySolidityIDHandler;

    private final ContractGetBytecodeHandler contractGetBytecodeHandler;

    private final ContractGetInfoHandler contractGetInfoHandler;

    private final ContractGetRecordsHandler contractGetRecordsHandler;

    private final ContractSystemDeleteHandler contractSystemDeleteHandler;

    private final ContractSystemUndeleteHandler contractSystemUndeleteHandler;

    private final ContractUpdateHandler contractUpdateHandler;

    private final EthereumTransactionHandler ethereumTransactionHandler;

    /**
     * @param contractCallHandler the handler for contract call transactions
     * @param contractCallLocalHandler the handler for contract call local transactions
     * @param contractCreateHandler the handler for contract create transactions
     * @param contractDeleteHandler the handler for contract delete transactions
     * @param contractGetBySolidityIDHandler the handler for contract get by solidity id queries
     * @param contractGetBytecodeHandler the handler for contract get bytecode queries
     * @param contractGetInfoHandler the handler for contract get info queries
     * @param contractGetRecordsHandler the handler for contract get records queries
     * @param contractSystemDeleteHandler the handler for contract delete transactions
     * @param contractSystemUndeleteHandler the handler for contract undelete transactions
     * @param contractUpdateHandler the handler for contract update transactions
     * @param ethereumTransactionHandler the handler for ethereum transactions
     */
    @Inject
    public ContractHandlers(
            @NonNull final ContractCallHandler contractCallHandler,
            @NonNull final ContractCallLocalHandler contractCallLocalHandler,
            @NonNull final ContractCreateHandler contractCreateHandler,
            @NonNull final ContractDeleteHandler contractDeleteHandler,
            @NonNull final ContractGetBySolidityIDHandler contractGetBySolidityIDHandler,
            @NonNull final ContractGetBytecodeHandler contractGetBytecodeHandler,
            @NonNull final ContractGetInfoHandler contractGetInfoHandler,
            @NonNull final ContractGetRecordsHandler contractGetRecordsHandler,
            @NonNull final ContractSystemDeleteHandler contractSystemDeleteHandler,
            @NonNull final ContractSystemUndeleteHandler contractSystemUndeleteHandler,
            @NonNull final ContractUpdateHandler contractUpdateHandler,
            @NonNull final EthereumTransactionHandler ethereumTransactionHandler) {
        this.contractCallHandler = requireNonNull(contractCallHandler, "contractCallHandler must not be null");
        this.contractCallLocalHandler =
                requireNonNull(contractCallLocalHandler, "contractCallLocalHandler must not be null");
        this.contractCreateHandler = requireNonNull(contractCreateHandler, "contractCreateHandler must not be null");
        this.contractDeleteHandler = requireNonNull(contractDeleteHandler, "contractDeleteHandler must not be null");
        this.contractGetBySolidityIDHandler =
                requireNonNull(contractGetBySolidityIDHandler, "contractGetBySolidityIDHandler must not be null");
        this.contractGetBytecodeHandler =
                requireNonNull(contractGetBytecodeHandler, "contractGetBytecodeHandler must not be null");
        this.contractGetInfoHandler = requireNonNull(contractGetInfoHandler, "contractGetInfoHandler must not be null");
        this.contractGetRecordsHandler =
                requireNonNull(contractGetRecordsHandler, "contractGetRecordsHandler must not be null");
        this.contractSystemDeleteHandler =
                requireNonNull(contractSystemDeleteHandler, "contractSystemDeleteHandler must not be null");
        this.contractSystemUndeleteHandler =
                requireNonNull(contractSystemUndeleteHandler, "contractSystemUndeleteHandler must not be null");
        this.contractUpdateHandler = requireNonNull(contractUpdateHandler, "contractUpdateHandler must not be null");
        this.ethereumTransactionHandler =
                requireNonNull(ethereumTransactionHandler, "ethereumTransactionHandler must not be null");
    }

    /**
     * @return the handler for contract call transactions
     */
    public ContractCallHandler contractCallHandler() {
        return contractCallHandler;
    }

    /**
     * @return the handler for contract call local transactions
     */
    public ContractCallLocalHandler contractCallLocalHandler() {
        return contractCallLocalHandler;
    }

    /**
     * @return the handler for contract create transactions
     */
    public ContractCreateHandler contractCreateHandler() {
        return contractCreateHandler;
    }

    /**
     * @return the handler for contract delete transactions
     */
    public ContractDeleteHandler contractDeleteHandler() {
        return contractDeleteHandler;
    }

    /**
     * @return the handler for contract get by solidity id queries
     */
    public ContractGetBySolidityIDHandler contractGetBySolidityIDHandler() {
        return contractGetBySolidityIDHandler;
    }

    /**
     * @return the handler for contract get bytecode queries
     */
    public ContractGetBytecodeHandler contractGetBytecodeHandler() {
        return contractGetBytecodeHandler;
    }

    /**
     * @return the handler for contract get info queries
     */
    public ContractGetInfoHandler contractGetInfoHandler() {
        return contractGetInfoHandler;
    }

    /**
     * @return the handler for contract get records queries
     */
    public ContractGetRecordsHandler contractGetRecordsHandler() {
        return contractGetRecordsHandler;
    }

    /**
     * @return the handler for contract delete transactions
     */
    public ContractSystemDeleteHandler contractSystemDeleteHandler() {
        return contractSystemDeleteHandler;
    }

    /**
     * @return the handler for contract undelete transactions
     */
    public ContractSystemUndeleteHandler contractSystemUndeleteHandler() {
        return contractSystemUndeleteHandler;
    }

    /**
     * @return the handler for contract update transactions
     */
    public ContractUpdateHandler contractUpdateHandler() {
        return contractUpdateHandler;
    }

    /**
     * @return the handler for ethereum transactions
     */
    public EthereumTransactionHandler ethereumTransactionHandler() {
        return ethereumTransactionHandler;
    }
}
