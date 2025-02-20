// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a fungible token that may exist on one or more target networks and be
 * registered with more than one {@link com.hedera.services.bdd.spec.HapiSpec} if desired.
 */
public class SpecFungibleToken extends SpecToken {
    /**
     * Create a new instance of {@link SpecFungibleToken} from a {@link FungibleToken} annotation.
     * @param annotation The annotation to create the instance from.
     * @param defaultName The default name to use if the annotation does not specify one.
     * @return The new instance of {@link SpecFungibleToken}.
     */
    public static SpecFungibleToken from(@NonNull final FungibleToken annotation, @NonNull final String defaultName) {
        final var token = new SpecFungibleToken(annotation.name().isBlank() ? defaultName : annotation.name());
        customizeToken(token, annotation.keys(), annotation.useAutoRenewAccount());
        token.builder().maxSupply(annotation.maxSupply()).totalSupply(annotation.initialSupply());
        return token;
    }

    public SpecFungibleToken(@NonNull final String name) {
        super(name, FUNGIBLE_COMMON);
    }
}
