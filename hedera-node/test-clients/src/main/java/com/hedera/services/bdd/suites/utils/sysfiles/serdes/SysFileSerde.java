// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import edu.umd.cs.findbugs.annotations.Nullable;

public interface SysFileSerde<T> {
    T fromRawFile(byte[] bytes);

    byte[] toRawFile(T styledFile, @Nullable String interpolatedSrcDir);

    String preferredFileName();

    default byte[] toValidatedRawFile(T styledFile, @Nullable String interpolatedSrcDir) {
        return toRawFile(styledFile, null);
    }
}
