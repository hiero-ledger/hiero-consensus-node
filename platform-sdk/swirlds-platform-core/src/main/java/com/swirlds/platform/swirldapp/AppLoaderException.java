// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.swirldapp;

public class AppLoaderException extends Exception {
    public AppLoaderException(String message) {
        super(message);
    }

    public AppLoaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
