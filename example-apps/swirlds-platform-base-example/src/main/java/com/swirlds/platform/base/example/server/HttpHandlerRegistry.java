// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.swirlds.platform.base.example.ext.BaseContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public interface HttpHandlerRegistry {

    @NonNull
    Set<HttpHandlerDefinition> handlers(@NonNull BaseContext context);
}
