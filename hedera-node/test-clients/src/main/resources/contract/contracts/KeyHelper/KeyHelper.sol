// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract KeyHelper is HederaTokenService {

    using Bits for uint;
    address supplyContract;

    function getDefaultKeys() internal view returns (IHederaTokenService.TokenKey[] memory keys) {
        keys = new IHederaTokenService.TokenKey[](2);
        keys[0] = getSingleKey(1, 1, "");
        keys[1] = IHederaTokenService.TokenKey (getDuplexKeyType(4, 6), getKeyValueType(2, ""));
    }

    function getAllTypeKeys(uint8 keyValueType, bytes memory key) internal view returns (IHederaTokenService.TokenKey[] memory keys) {
        keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = IHederaTokenService.TokenKey (getAllKeyTypes(), getKeyValueType(keyValueType, key));
    }

    function getCustomSingleTypeKeys(uint8 keyType, uint8 keyValueType, bytes memory key) internal view returns (IHederaTokenService.TokenKey[] memory keys) {
        keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = IHederaTokenService.TokenKey (getKeyType(keyType), getKeyValueType(keyValueType, key));
    }

    function getCustomDuplexTypeKeys(uint8 firstType, uint8 secondType, uint8 keyValueType, bytes memory key) internal view returns (IHederaTokenService.TokenKey[] memory keys) {
        keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = IHederaTokenService.TokenKey (getDuplexKeyType(firstType, secondType), getKeyValueType(keyValueType, key));
    }

    function getSingleKey(uint8 keyType, uint8 keyValueType, bytes memory key) internal view returns (IHederaTokenService.TokenKey memory tokenKey) {
        tokenKey =  IHederaTokenService.TokenKey (getKeyType(keyType), getKeyValueType(keyValueType, key));
    }

    function getSingleKey(uint8 keyType, uint8 keyValueType, address key) internal view returns (IHederaTokenService.TokenKey memory tokenKey) {
        tokenKey =  IHederaTokenService.TokenKey (getKeyType(keyType), getKeyValueType(keyValueType, key));
    }

    function getSingleKey(uint8 firstType, uint8 secondType, uint8 keyValueType, bytes memory key) internal view returns (IHederaTokenService.TokenKey memory tokenKey) {
        tokenKey =  IHederaTokenService.TokenKey (getDuplexKeyType(firstType, secondType), getKeyValueType(keyValueType, key));
    }

    function getDuplexKeyType(uint8 firstType, uint8 secondType) internal pure returns (uint keyType) {
        keyType = keyType.setBit(firstType);
        keyType = keyType.setBit(secondType);
    }

    function getAllKeyTypes() internal pure returns (uint keyType) {
        keyType = keyType.setBit(0);
        keyType = keyType.setBit(1);
        keyType = keyType.setBit(2);
        keyType = keyType.setBit(3);
        keyType = keyType.setBit(4);
        keyType = keyType.setBit(5);
        keyType = keyType.setBit(6);
    }

    function getKeyType(uint8 keyType) internal pure returns (uint) {
        if (keyType == 0) {
            return HederaTokenService.ADMIN_KEY_TYPE;
        } else if(keyType == 1) {
            return HederaTokenService.KYC_KEY_TYPE;
        } else if(keyType == 2) {
            return HederaTokenService.FREEZE_KEY_TYPE;
        } else if(keyType == 3) {
            return HederaTokenService.WIPE_KEY_TYPE;
        } else if(keyType == 4) {
            return HederaTokenService.SUPPLY_KEY_TYPE;
        } else if(keyType == 5) {
            return HederaTokenService.FEE_SCHEDULE_KEY_TYPE;
        } else if(keyType == 6) {
            return HederaTokenService.PAUSE_KEY_TYPE;
        }

        return 0;
    }

    function getKeyValueType(uint8 keyValueType, bytes memory key) internal view returns (IHederaTokenService.KeyValue memory keyValue) {
        if(keyValueType == 1) {
            keyValue.inheritAccountKey = true;
        } else if(keyValueType == 2) {
            keyValue.contractId = supplyContract;
        } else if(keyValueType == 3) {
            keyValue.ed25519 = key;
        } else if(keyValueType == 4) {
            keyValue.ECDSA_secp256k1 = key;
        } else if(keyValueType == 5) {
            keyValue.delegatableContractId = supplyContract;
        }
    }

    function getKeyValueType(uint8 keyValueType, address keyAddress) internal view returns (IHederaTokenService.KeyValue memory keyValue) {
        if (keyValueType == 2) {
            keyValue.contractId = keyAddress;
        } else if(keyValueType == 5) {
            keyValue.delegatableContractId = keyAddress;
        }
    }
}

library Bits {

    uint constant internal ONE = uint(1);

    // Sets the bit at the given 'index' in 'self' to '1'.
    // Returns the modified value.
    function setBit(uint self, uint8 index) internal pure returns (uint) {
        return self | ONE << index;
    }
}
