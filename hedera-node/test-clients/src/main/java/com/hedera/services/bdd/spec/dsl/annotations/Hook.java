// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.annotations;

import com.hedera.hapi.node.hooks.HookExtensionPoint;
import com.hedera.services.bdd.junit.extensions.SpecEntityExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@ExtendWith(SpecEntityExtension.class)
public @interface Hook {
    /**
     * The hook id to use.
     */
    long hookId();

    /**
     * The contract whose bytecode should be used for the hook.
     */
    String contract();

    /**
     * The extension point the hook should be created at.
     */
    HookExtensionPoint extensionPoint();
}
