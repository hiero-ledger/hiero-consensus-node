// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

/**
 * Forwards an arbitrary caller-supplied TokenKey[] to the HTS updateTokenKeys function at 0x167, so tests can
 * exercise input shapes (empty list, zero or undefined keyType bits, duplicate keyTypes) as a contract that may hold
 * a token's keys. Self-contained on purpose; it does not use the shared HederaTokenService/KeyHelper sources.
 */
contract UpdateTokenKeysContract {
    address constant precompileAddress = address(0x167);
    int64 constant SUCCESS = 22;

    struct KeyValue {
        bool inheritAccountKey;
        address contractId;
        bytes ed25519;
        bytes ECDSA_secp256k1;
        address delegatableContractId;
    }

    struct TokenKey {
        uint256 keyType;
        KeyValue key;
    }

    function updateTokenKeys(address token, TokenKey[] memory keys) external {
        (bool success, bytes memory result) = precompileAddress.call(
            abi.encodeWithSignature("updateTokenKeys(address,(uint256,(bool,address,bytes,bytes,address))[])", token, keys));
        int64 responseCode = success ? abi.decode(result, (int64)) : int64(-1);
        if (responseCode != SUCCESS) {
            revert("Update of token keys failed!");
        }
    }
}
