// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FileServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(FileServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.FileService");
    }

    @Test
    void methodsDefined() {
        final var methods = FileServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("createFile", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("updateFile", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("deleteFile", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("appendContent", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("getFileContent", Query.class, Response.class),
                        new RpcMethodDefinition<>("getFileInfo", Query.class, Response.class),
                        new RpcMethodDefinition<>("systemDelete", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("systemUndelete", Transaction.class, TransactionResponse.class));
    }
}
