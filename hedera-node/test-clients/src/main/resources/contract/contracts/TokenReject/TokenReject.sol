// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract TokenReject is HederaTokenService {

    function rejectTokens(address rejectingAddress, address[] memory ftAddresses, address[] memory nftAddresses) public returns(int64 responseCode) {
        IHederaTokenService.NftID[] memory nftIDs = new IHederaTokenService.NftID[](nftAddresses.length);
        for (uint i; i < nftAddresses.length; i++)
        {
            IHederaTokenService.NftID memory nftId;
            nftId.nft = nftAddresses[i];
            nftId.serial = 1;
            nftIDs[i] = nftId;
        }
        responseCode = rejectTokens(rejectingAddress, ftAddresses, nftIDs);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }
}
