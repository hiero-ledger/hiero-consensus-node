// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Defines how to translate some state read into the result of a {@code ContractCall} record.
 *
 * <p>Note that if the requested state could not be read, the result is always {@code null}
 * and there would be nothing to translate.
 *
 * @param <T> the type of state read
 */
public interface ResultTranslator<T> {
    /**
     * Translates the given state read into the result of a {@code ContractCall} record.
     *
     * @param entity the state read
     * @return the result of a {@code ContractCall} record
     */
    @NonNull
    Bytes computeResult(@NonNull T entity);
}
