// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allows mapping the methods that will be used as an individual source for each parameter
 * <p><b>Example:</b>
 * <pre><code>
 * {@literal @}TestTemplate
 * {@literal @}ExtendWith(ParameterCombinationExtension.class)
 * {@literal @}UseParameterSources({
 *     {@literal @}ParamSource(param = "username", method = "usernameSource"),
 *     {@literal @}ParamSource(param = "age", method = "ageSource")
 * })
 * void testUser({@literal @}ParamName("username") String username, {@literal @}ParamName("age") int age) {
 *     // This method will be executed for all combinations of usernames and ages.
 * }
 * </code></pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UseParameterSources {
    ParamSource[] value();
}
