// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract NegativeDissociationsContract is HederaTokenService {
    address private invalidAddress = 0xFFfFfFffFFfffFFfFFfFFFFFffFFFffffFfFFFfF;
    address private zeroAddress;
    address private zeroAccount;

    function dissociateTokensWithNonExistingAccountAddress(address[] memory tokens) external {
        int responseCode = super.dissociateTokens(invalidAddress, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokensWithEmptyTokensArray(address account) external {
        address[] memory tokens;
        int responseCode = super.dissociateTokens(account, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokensWithTokensArrayWithSomeNonExistingAddresses(address account, address[] memory tokens) external {
        address[] memory someInvalidTokens = new address[](tokens.length + 1);
        uint i = 0;
        for (; i < tokens.length; i++) {
            someInvalidTokens[i] = tokens[i];
        }
        someInvalidTokens[i] = invalidAddress;
        int responseCode = super.dissociateTokens(account, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokensWithNullAccount(address[] memory tokens) external {
        int responseCode = super.dissociateTokens(zeroAccount, tokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokensWithNullTokensArray(address account) external {
        address[] memory zeroTokens = new address[](1);
        zeroTokens[0] = zeroAddress;
        int responseCode = super.dissociateTokens(account, zeroTokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokensWithNonExistingTokensArray(address account) external {
        address[] memory zeroTokens = new address[](3);
        zeroTokens[0] = 0xFFfFfFffFFfffFFfFFfFFFFFffFFFffffFfFFFfF;
        zeroTokens[1] = 0xfFFFFFffFffFfFfFfFfFfFFFfffffFFffCCccCCc;
        zeroTokens[2] = 0xFffFfffFfFffffffFfFFFfFfFFfFFfFfBBBbbBbB;
        int responseCode = super.dissociateTokens(account, zeroTokens);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokenWithNonExistingAccount(address token) external {
        int responseCode = super.associateToken(invalidAddress, token);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokenWithNonExistingTokenAddress(address account) external {
        int responseCode = super.dissociateToken(account, invalidAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokenWithNullAccount(address token) external {
        int responseCode = super.dissociateToken(zeroAccount, token);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function dissociateTokenWithNullTokenAddress(address account) external {
        int responseCode = super.dissociateToken(account, zeroAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }
}
