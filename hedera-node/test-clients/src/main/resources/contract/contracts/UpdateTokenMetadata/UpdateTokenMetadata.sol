// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {HederaTokenService} from "./HederaTokenService.sol";
import {HederaResponseCodes} from "./HederaResponseCodes.sol";

contract UpdateTokenMetadata is HederaTokenService {
    constructor(){
    }

    function callUpdateNFTsMetadata(address nftToken, int64[] memory serialNumbers, bytes memory _newMetadata) public {
        (int64 responseCode) = HederaTokenService.updateNFTsMetadata(nftToken, serialNumbers, _newMetadata);
        require(responseCode == HederaResponseCodes.SUCCESS, "Failed to update metadata for NFTs");
    }
}
