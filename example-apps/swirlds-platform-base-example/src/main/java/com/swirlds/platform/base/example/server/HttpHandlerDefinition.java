// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.sun.net.httpserver.HttpHandler;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface HttpHandlerDefinition extends HttpHandler {

    @NonNull
    String path();
}
