// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.annotations.NodeSelfId;
import com.hedera.node.app.spi.info.NodeInfo;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;

/** A Dagger module for facilities in the {@link com.hedera.node.app.info} package. */
@Module
public abstract class InfoInjectionModule {
    @Provides
    @NodeSelfId
    static AccountID selfAccountID(@NonNull final NodeInfo info) {
        return info.accountId();
    }
}
