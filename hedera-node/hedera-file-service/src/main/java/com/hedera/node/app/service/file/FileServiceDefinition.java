// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Transactions and queries for the file service.
 */
@SuppressWarnings("java:S6548")
public final class FileServiceDefinition implements RpcServiceDefinition {
    /**
     * The singleton instance of the file service definition.
     */
    public static final FileServiceDefinition INSTANCE = new FileServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("createFile", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("updateFile", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("deleteFile", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("appendContent", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("getFileContent", Query.class, Response.class),
            new RpcMethodDefinition<>("getFileInfo", Query.class, Response.class),
            new RpcMethodDefinition<>("systemDelete", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("systemUndelete", Transaction.class, TransactionResponse.class));

    private FileServiceDefinition() {
        // Just something to keep the Gradle build believing we have a non-transitive
        // "requires" and hence preserving our module-info.class in the compiled JAR
        requireNonNull(CommonUtils.class);
    }

    @Override
    @NonNull
    public String basePath() {
        return "proto.FileService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
