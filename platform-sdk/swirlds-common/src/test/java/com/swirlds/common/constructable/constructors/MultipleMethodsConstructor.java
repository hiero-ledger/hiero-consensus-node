// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.constructors;

import com.swirlds.common.constructable.RuntimeConstructable;

public interface MultipleMethodsConstructor {
    RuntimeConstructable foo();

    RuntimeConstructable bar();
}
