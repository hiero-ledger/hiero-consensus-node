// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.components;

import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component
public interface UtilComponent {
    @Component.Factory
    interface Factory {
        UtilComponent create();
    }

    UtilPrngHandler prngHandler();
}
