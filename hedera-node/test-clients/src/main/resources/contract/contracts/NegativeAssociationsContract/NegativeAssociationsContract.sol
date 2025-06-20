// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract NegativeAssociationsContract is HederaTokenService {
    address private invalidAddress = 0xFFfFfFffFFfffFFfFFfFFFFFffFFFffffFfFFFfF;
    address private zeroAddress;
    address private zeroAccount;

    function associateTokensWithNonExistingAccountAddress(address[] memory tokens) external {
        int responseCode = super.associateTokens(invalidAddress, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokensWithEmptyTokensArray(address account) external {
        address[] memory tokens;
        int responseCode = super.associateTokens(account, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokensWithTokensArrayWithSomeNonExistingAddresses(address account, address[] memory tokens) external {
        address[] memory someInvalidTokens = new address[](tokens.length + 1);
        uint i = 0;
        for (; i < tokens.length; i++) {
            someInvalidTokens[i] = tokens[i];
        }
        someInvalidTokens[i] = invalidAddress;
        int responseCode = super.associateTokens(account, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokensWithNullAccount(address[] memory tokens) external {
        int responseCode = super.associateTokens(zeroAccount, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokensWithNullTokensArray(address account) external {
        address[] memory zeroTokens = new address[](1);
        zeroTokens[0] = zeroAddress;
        int responseCode = super.associateTokens(account, zeroTokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokensWithNonExistingTokensArray(address account) external {
        address[] memory zeroTokens = new address[](3);
        zeroTokens[0] = 0xFFfFfFffFFfffFFfFFfFFFFFffFFFffffFfFFFfF;
        zeroTokens[1] = 0xfFFFFFffFffFfFfFfFfFfFFFfffffFFffCCccCCc;
        zeroTokens[2] = 0xFffFfffFfFffffffFfFFFfFfFFfFFfFfBBBbbBbB;
        int responseCode = super.associateTokens(account, zeroTokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokenWithNonExistingAccount(address token) external {
        int responseCode = super.associateToken(invalidAddress, token);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokenWithNonExistingTokenAddress(address account) external {
        int responseCode = super.associateToken(account, invalidAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokenWithNullAccount(address token) external {
        int responseCode = super.associateToken(zeroAccount, token);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function associateTokenWithNullTokenAddress(address account) external {
        int responseCode = super.associateToken(account, zeroAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }
}
