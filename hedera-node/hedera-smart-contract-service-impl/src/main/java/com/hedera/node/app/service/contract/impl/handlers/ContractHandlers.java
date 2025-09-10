// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class contains all workflow-related handlers regarding contract operations in Hedera.
 */
public record ContractHandlers(
        @NonNull ContractCallHandler contractCallHandler,
        @NonNull ContractCallLocalHandler contractCallLocalHandler,
        @NonNull ContractCreateHandler contractCreateHandler,
        @NonNull ContractDeleteHandler contractDeleteHandler,
        @NonNull ContractGetBySolidityIDHandler contractGetBySolidityIDHandler,
        @NonNull ContractGetBytecodeHandler contractGetBytecodeHandler,
        @NonNull ContractGetInfoHandler contractGetInfoHandler,
        @NonNull ContractGetRecordsHandler contractGetRecordsHandler,
        @NonNull ContractSystemDeleteHandler contractSystemDeleteHandler,
        @NonNull ContractSystemUndeleteHandler contractSystemUndeleteHandler,
        @NonNull ContractUpdateHandler contractUpdateHandler,
        @NonNull EthereumTransactionHandler ethereumTransactionHandler,
        @NonNull LambdaSStoreHandler lambdaSStoreHandler) {
    public ContractHandlers {
        requireNonNull(contractCallHandler);
        requireNonNull(contractCallLocalHandler);
        requireNonNull(contractCreateHandler);
        requireNonNull(contractDeleteHandler);
        requireNonNull(contractGetBySolidityIDHandler);
        requireNonNull(contractGetBytecodeHandler);
        requireNonNull(contractGetInfoHandler);
        requireNonNull(contractGetRecordsHandler);
        requireNonNull(contractSystemDeleteHandler);
        requireNonNull(contractSystemUndeleteHandler);
        requireNonNull(contractUpdateHandler);
        requireNonNull(ethereumTransactionHandler);
        requireNonNull(lambdaSStoreHandler);
    }
}
