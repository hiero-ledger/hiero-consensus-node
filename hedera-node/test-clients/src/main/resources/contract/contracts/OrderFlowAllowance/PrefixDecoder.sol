// SPDX-License-Identifier: WTFPL
pragma solidity >=0.8.4;

// Library to decode prefix key and order detail info

library PrefixDecoder {

    // The order prefix has the following format
    //     uint64 outToken;     // 8 bytes
    //     uint64 inToken;      // 8 bytes
    //     uint56 salt;         // 7 bytes
    //     uint32 expiration;   // 4 bytes
    //     uint24 feeBips;      // 3 bytes - note: out of 1e6 (1000 = 0.1% fee)
    //     uint8 orderType;     // 1 byte
    //     uint8 flags;         // 1 byte


    function orderType(uint256 prefix) internal pure returns (uint8) {
        return uint8((prefix >> 248) & 0xFF);
    }

    function flags(uint256 prefix) internal pure returns (uint8) {
        return uint8((prefix >> 240) & 0xFF);
    }

    function outToken(uint256 prefix) internal pure returns (address) {
        return address(uint160((prefix >> 176) & ((1 << 64) - 1)));
    }

    function inToken(uint256 prefix) internal pure returns (address) {
        return address(uint160((prefix >> 112) & ((1 << 64) - 1)));
    }

    function expiration(uint256 prefix) internal pure returns (uint32) {
        return uint32((prefix >> 80) & ((1 << 32) - 1));
    }

    function feeBips(uint256 prefix) internal pure returns (uint24) {
        return uint24((prefix >> 56) & ((1 << 24) - 1));
    }

    function salt(uint256 prefix) internal pure returns (uint56) {
        return uint56(prefix & ((1 << 56) - 1));
    }

    // flags

    function isFOK(uint256 prefix) internal pure returns (bool) {
        return flags(prefix) & 0x02 != 0;
    }
}
