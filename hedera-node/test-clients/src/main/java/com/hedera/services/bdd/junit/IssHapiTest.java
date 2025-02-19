/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * A subprocess-only variant of {@link HapiTest} that intentionally starts node 0 with a different
 * configuration. For now, any test class annotated with {@link IssHapiTest} should expect node 0
 * to allow for one extra transaction to be submitted in a crypto transfer body in its test(s). A test
 * should then submit a crypto transfer <b>to node 0</b> that will result in successfully processing
 * an illegal number of transfers on node 0, but which transfer will be (correctly) rejected by the rest
 * of the nodes in the network. An ISS event will then occur on node 0, after which the test can then
 * verify particulars of the ISS node's diverging block stream, the block stream of the rest of the
 * network, or both.
 * <p>
 * This annotation is not designed for maximum flexibility at present, especially in being limited to
 * use as a class-level annotation; however, if this type of test is found to be useful in simulating
 * other ISS scenarios, the annotation's functionality may be enhanced later. For example, it may prove
 * useful to set any desired config value to a different property on a single node.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith({NetworkTargetingExtension.class, SpecNamingExtension.class})
@Isolated
public @interface IssHapiTest {}
