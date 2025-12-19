// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;

public interface WritableClprMessageStore extends ReadableClprMessageStore {
    void put(@NonNull ClprMessageKey messageKey, @NonNull ClprMessageValue clprMessageValue);
}
