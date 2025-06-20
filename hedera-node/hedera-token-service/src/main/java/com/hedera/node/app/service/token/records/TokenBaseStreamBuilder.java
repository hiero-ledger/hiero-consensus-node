// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The base interface for Token Service record builders that record operations on Tokens.
 */
public interface TokenBaseStreamBuilder extends StreamBuilder {
    /**
     * Sets the {@link TokenType} of the token the recorded transaction created or modified.
     * @param tokenType the token type
     * @return this builder
     */
    TokenBaseStreamBuilder tokenType(@NonNull TokenType tokenType);
}
