// SPDX-License-Identifier: MIT
pragma solidity >=0.5.0 <0.9.0;
import {HederaTokenService} from "./HederaTokenService.sol";
import {HederaResponseCodes} from "./HederaResponseCodes.sol";

contract UpdateTokenMetadata is HederaTokenService {
    constructor(){
    }

    function callUpdateNFTsMetadata(address nftToken, int64[] memory serialNumbers, bytes memory _newMetadata) public {
        (int64 responseCode) = this.updateNFTsMetadata(nftToken, serialNumbers, _newMetadata);
        require(responseCode == HederaResponseCodes.SUCCESS, "Failed to update metadata for NFTs");
    }
}
