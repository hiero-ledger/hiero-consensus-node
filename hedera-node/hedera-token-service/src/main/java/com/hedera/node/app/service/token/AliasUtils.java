// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HexFormat;

/**
 * A collection of static utility methods for working with aliases on {@link Account}s.
 */
public final class AliasUtils {
    /** The first 12 bytes of an "entity num alias". See {@link #isEntityNumAlias(Bytes)}. */
    private static final byte[] ENTITY_NUM_ALIAS_PREFIX = new byte[12];
    /** All EVM addresses are 20 bytes long, and key-encoded keys are not. */
    private static final int EVM_ADDRESS_SIZE = 20;
    /** All valid ECDSA protobuf encoded keys have this prefix. */
    private static final Bytes ECDSA_KEY_ALIAS_PREFIX =
            Bytes.wrap(HexFormat.of().parseHex("3a21"));
    /** All valid ECDSA protobuf encoded keys are 33 bytes long. */
    private static final int ECDSA_SECP256K1_ALIAS_SIZE = 33;
    /** All valid ED25519 protobuf encoded keys are 32 bytes long. */
    private static final int ED25519_ALIAS_SIZE = 32;

    private AliasUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Gets whether the given alias is the right length to be an EVM address. Today, this number is 20 bytes.
     *
     * @param alias The alias to check
     * @return {@code true} if the alias is the right length to be an EVM address
     */
    public static boolean isOfEvmAddressSize(@NonNull final Bytes alias) {
        return alias.length() == EVM_ADDRESS_SIZE;
    }

    /**
     * Given an alias, attempts to extract from it an EVM address. If the alias is already an EVM address, simply return
     * it. If the alias is a key alias, and the key is an ECDSA_SECP256K1 key, return the EVM address derived from the
     * public key. Otherwise, return null.
     *
     * @param alias The alias to extract an EVM address from.
     * @return The EVM address, or null if the alias is not an EVM address or an ECDSA_SECP256K1 key alias
     */
    @Nullable
    public static Bytes extractEvmAddress(@NonNull final Bytes alias) {
        requireNonNull(alias);
        if (isOfEvmAddressSize(alias)) {
            return alias;
        }
        final var key = asKeyFromAliasOrElse(alias, null);
        return (key != null && key.hasEcdsaSecp256k1()) ? recoverAddressFromPubKey(key.ecdsaSecp256k1OrThrow()) : null;
    }

    /**
     * Given a key, attempts to extract from it an EVM address. If the key is an ECDSA_SECP256K1 key, return the EVM
     * address derived from the public key. Otherwise, return null.
     * @param key The key to extract an EVM address from.
     * @return The EVM address, or null if the key is not an ECDSA_SECP256K1 key
     */
    @Nullable
    public static Bytes extractEvmAddress(@Nullable final Key key) {
        return key != null && key.hasEcdsaSecp256k1() ? recoverAddressFromPubKey(key.ecdsaSecp256k1OrThrow()) : null;
    }

    /**
     * Given some alias, determine whether it is an "entity num alias". If the alias is exactly 20 bytes long, and
     * if its initial bytes match the {@link #ENTITY_NUM_ALIAS_PREFIX}, then it is an entity num alias.
     *
     * <p>Every entity in the system (accounts, tokens, etc.) may be represented within ethereum with a 20-byte EVM
     * address. This address can be explicit (as part of the alias), or it can be based on the entity ID number. When
     * based on the entity number, the first 12 bytes will be defined to be zero which indicates the current networks
     * shard and realm, while the last 8 bytes represent the entity number. In the case where such an address is detected,
     * the resultant entity id extract from such an address will be {@literal <local shard>.<local realm>.<entity number>}.
     * Because the shard and realm are zero, this prefix is all zeros, which is why it is sometimes known as the "long-zero" alias.
     *
     * @param alias The alias to check
     * @return True if the alias is an entity num alias
     */
    public static boolean isEntityNumAlias(final Bytes alias) {
        return isOfEvmAddressSize(alias) && alias.matchesPrefix(ENTITY_NUM_ALIAS_PREFIX);
    }

    /**
     * Given some alias, determine whether it is a key alias. If the alias is a valid protobuf-encoded key, then it is a
     * key alias. This method does not check whether the key is valid, only whether the alias is a valid
     * protobuf-encoded key.
     * @param alias The alias to check
     * @return True if the alias is a key alias
     */
    public static boolean isKeyAlias(@NonNull final Bytes alias) {
        final var key = asKeyFromAliasOrElse(alias, null);
        if (key == null) return false;
        if (!isValid(key)) return false;
        if (key.hasEcdsaSecp256k1()) {
            final var ecdsa = key.ecdsaSecp256k1OrThrow();
            return ecdsa.length() == ECDSA_SECP256K1_ALIAS_SIZE && alias.matchesPrefix(ECDSA_KEY_ALIAS_PREFIX);
        } else if (key.hasEd25519()) {
            return key.ed25519OrThrow().length() == ED25519_ALIAS_SIZE;
        }
        return false;
    }

    /**
     * Given a public key, recover the address from it. This method is used to extract an EVM address from an ECDSA
     * public key.
     * @param alias The public key to recover the address from
     * @return The address recovered from the public key
     */
    @NonNull
    public static Bytes recoverAddressFromPubKey(@NonNull final Bytes alias) {
        return EthSigsUtils.recoverAddressFromPubKey(alias);
    }

    /**
     * Attempts to parse a {@code Key} from given alias {@code ByteString}. If the Key is of type
     * Ed25519 or ECDSA(secp256k1), returns true if it is a valid key; and false otherwise.
     *
     * @param alias given alias byte string
     * @return whether it parses to a valid primitive key
     */
    public static boolean isSerializedProtoKey(@NonNull final Bytes alias) {
        requireNonNull(alias);

        // If the alias is an evmAddress we don't need to parse with Key.PROTOBUF.
        // This will cause BufferUnderflowException
        if (!isAliasSizeGreaterThanEvmAddress(alias)) {
            return false;
        }

        // Determine whether these bytes represent a serialized Key (as protobuf bytes).
        // FUTURE: Rather than parsing and catching an error, we could have PBJ provide a method that simply returns
        // a boolean instead of throwing an exception. Or maybe we can make sure the alias is a valid ECDSA key length
        // or ED25519 key length as a short circuit in case of very long aliases (no point parsing those).
        try {
            final var key = Key.PROTOBUF.parseStrict(alias.toReadableSequentialData());
            return (key.hasEcdsaSecp256k1() || key.hasEd25519()) /* && isValid(key)*/;
        } catch (final Exception e) {
            // There are many possible exceptions thrown here, both checked (IOException) and unchecked. See the
            // documentation for ReadableStreamingData as well as the parse method for all the various exceptions.
            return false;
        }
    }

    /**
     * A utility method that, given an address alias, extracts the account or contract ID number (skipping shard
     * and realm).
     * @param addressAlias The address alias, where the 0.0.1234 style address has been encoded into 20 bytes
     * @return The ID number of the account or contract
     */
    public static Long extractIdFromAddressAlias(final Bytes addressAlias) {
        return addressAlias.getLong(12);
    }

    /**
     * A utility method that checks if account is in aliased form.
     * @param idOrAlias account id or alias
     * @return true if account is in aliased form
     */
    public static boolean isAlias(@NonNull final AccountID idOrAlias) {
        requireNonNull(idOrAlias);
        return !idOrAlias.hasAccountNum() && idOrAlias.hasAlias();
    }

    /**
     * Parse a {@code Key} from given alias {@code Bytes}. If there is a parse error, throws a
     * {@code HandleException} with {@code INVALID_ALIAS_KEY} response code.
     * @param alias given alias bytes
     * @return the parsed key
     */
    @NonNull
    public static Key asKeyFromAlias(@NonNull final Bytes alias) {
        requireNonNull(alias);
        final var key = asKeyFromAliasOrElse(alias, null);
        if (key == null) throw new HandleException(ResponseCodeEnum.INVALID_ALIAS_KEY);
        return key;
    }

    /**
     * Parse a {@code Key} from given alias {@code Bytes}. If there is a parse error, throws a
     * {@code HandleException} with {@code INVALID_ALIAS_KEY} response code.
     * @param alias given alias bytes
     * @return the parsed key
     * @throws PreCheckException if the alias is not a valid key
     */
    @NonNull
    public static Key asKeyFromAliasPreCheck(@NonNull final Bytes alias) throws PreCheckException {
        requireNonNull(alias);
        final var key = asKeyFromAliasOrElse(alias, null);
        if (key == null) throw new PreCheckException(ResponseCodeEnum.INVALID_ALIAS_KEY);
        return key;
    }

    /**
     * Parse a {@code Key} from given alias {@code Bytes}. If there is a parse error, returns the given default key.
     * @param alias given alias bytes. If the alias is an evmAddress we don't need to parse with Key.PROTOBUF.
     *              This will cause BufferUnderflowException. So, we return the default key.
     * @param def default key
     * @return the parsed key or the default key if there is a parse error
     */
    @Nullable
    public static Key asKeyFromAliasOrElse(@NonNull final Bytes alias, @Nullable final Key def) {
        requireNonNull(alias);
        // If the alias is an evmAddress we don't need to parse with Key.PROTOBUF.
        // This will cause BufferUnderflowException
        if (!isAliasSizeGreaterThanEvmAddress(alias)) {
            return def;
        }
        try {
            return Key.PROTOBUF.parseStrict(alias.toReadableSequentialData());
        } catch (final Exception e) {
            // There are many possible exceptions, not just IOException. We want to catch all of them.
            return def;
        }
    }

    /**
     * Check if the given alias is greater than the size of an EVM address.
     * @param alias The alias to check
     * @return True if the alias is greater than the size of an EVM address
     */
    public static boolean isAliasSizeGreaterThanEvmAddress(@NonNull final Bytes alias) {
        requireNonNull(alias);
        return alias.length() > EVM_ADDRESS_SIZE;
    }
}
