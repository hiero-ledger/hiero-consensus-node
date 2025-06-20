// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract NegativeBurnContract is HederaTokenService{

    function burnFungibleWithExtremeAmounts(address token) external {
        (int responseCode, int64 newTotalSupply) = super.burnToken(token, 9223372036854775810, new int64[](0));
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnFungibleNegativeLong(address token) external {
        (int responseCode, int64 newTotalSupply) = super.burnToken(token, -1, new int64[](0));
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnFungibleInvalidAddress() external {
        address invalidAddress = 0xFFfFfFffFFfffFFfFFfFFFFFffFFFffffFfFFFfF;
        (int responseCode, int64 newTotalSupply) = super.burnToken(invalidAddress, 1, new int64[](0));
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnFungibleZeroAddress() external {
        address zeroAddress;
        (int responseCode, int64 newTotalSupply) = super.burnToken(zeroAddress, 1, new int64[](0));
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnFungibleWithInvalidSerials(address token) external {
        int64[] memory serials = new int64[](2);
        serials[1] = 123123123123;
        serials[2] = 321321321321;
        (int responseCode, int64 newTotalSupply) = super.burnToken(token, 1, serials);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnNFTWithExtremeAmounts(address token, int64[] memory serials) external {
        (int responseCode, int64 newTotalSupply) = super.burnToken(token, 9223372036854775810, serials);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnNFTNegativeLong(address token, int64[] memory serials) external {
        (int responseCode, int64 newTotalSupply) = super.burnToken(token, -1, serials);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnNFTInvalidAddress(int64[] memory serials) external {
        address invalidAddress = 0xFFfFfFffFFfffFFfFFfFFFFFffFFFffffFfFFFfF;
        (int responseCode, int64 newTotalSupply) = super.burnToken(invalidAddress, 0, serials);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnNFTZeroAddress(int64[] memory serials) external {
        address zeroAddress;
        (int responseCode, int64 newTotalSupply) = super.burnToken(zeroAddress, 0, serials);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function burnNFTWithInvalidSerials(address token) external {
        int64[] memory serials = new int64[](2);
        serials[1] = 123123123123;
        serials[2] = 321321321321;
        (int responseCode, int64 newTotalSupply) = super.burnToken(token, 0, serials);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }
}
