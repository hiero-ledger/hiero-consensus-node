// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hiero.consensus.model.notification.Notification;

/**
 * Allows per {@link Listener} configuration of {@link DispatchMode} and {@link DispatchOrder}. Default configuration is
 * {@link DispatchMode#SYNC} and {@link DispatchOrder#UNORDERED}.
 */
@Inherited
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DispatchModel {
    /**
     * Specifies the {@link DispatchMode} to be used for all {@link Notification} implementations supported by this
     * listener.
     *
     * The default is {@link DispatchMode#SYNC}.
     *
     * @return the configured {@link DispatchMode}
     */
    DispatchMode mode() default DispatchMode.SYNC;

    /**
     * Specifies the {@link DispatchOrder} to be used for all {@link Notification} implementations supported by this
     * listener.
     *
     * The default is {@link DispatchOrder#UNORDERED}.
     *
     * @return the configured {@link DispatchOrder}
     */
    DispatchOrder order() default DispatchOrder.UNORDERED;
}
