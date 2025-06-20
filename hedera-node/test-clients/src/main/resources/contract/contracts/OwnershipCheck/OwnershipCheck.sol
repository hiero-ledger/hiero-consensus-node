pragma solidity =0.8.15;

import "./HederaTokenService.sol";
import "./IHederaTokenService.sol";

// SPDX-License-Identifier: MIT
contract OwnershipCheck is HederaTokenService {
    function isOwner(address token, int64 serialNumber) external view returns (address, address, int256) {
        (int256 responseCode, IHederaTokenService.NonFungibleTokenInfo memory tokenInfo) = HederaTokenService.getNonFungibleTokenInfo(token, serialNumber);

        return (tokenInfo.ownerId, msg.sender, responseCode);
    }
}
