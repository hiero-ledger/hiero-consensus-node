// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.9;

import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract Create2User is HederaTokenService {
    function associateTo(address token_type) public {
        int userAssociation = HederaTokenService.associateToken(
            address(this), token_type);
        if (userAssociation != HederaResponseCodes.SUCCESS) {
            revert("It's unheard of...");
        }
    }

    function dissociateFrom(address token_type) public {
        int userDissociation = HederaTokenService.dissociateToken(
            address(this), token_type);
        if (userDissociation != HederaResponseCodes.SUCCESS) {
            revert("So unfair");
        }
    }

    function mintNft(address token_type, bytes[] memory metadata) external {
        (int rc, uint64 newSupply, int256[] memory sns) = HederaTokenService.mintToken(token_type, 0, metadata);

        if (rc != HederaResponseCodes.SUCCESS) {
            revert("Can't!");
        }
    }

    function mintNftViaDelegate(address token_type, bytes[] memory metadata) external {
        Helper helper = new Helper();
        address(helper).delegatecall(
            abi.encodeWithSelector(Helper.mintNft.selector, token_type, metadata));
    }
}

contract Helper is HederaTokenService {
    function mintNft(address token_type, bytes[] memory metadata) external {
        (int rc, uint64 newSupply, int256[] memory sns) = HederaTokenService.mintToken(token_type, 0, metadata);

        if (rc != HederaResponseCodes.SUCCESS) {
            revert("Can't even!");
        }
    }
}
