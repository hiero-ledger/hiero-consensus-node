// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract ClaimAirdrop is HederaTokenService {

    function claim(address sender, address receiver, address token) public returns(int64 responseCode){
        IHederaTokenService.PendingAirdrop[] memory pendingAirdrops = new IHederaTokenService.PendingAirdrop[](1);

        IHederaTokenService.PendingAirdrop memory pendingAirdrop;
        pendingAirdrop.sender = sender;
        pendingAirdrop.receiver = receiver;
        pendingAirdrop.token = token;

        pendingAirdrops[0] = pendingAirdrop;

        responseCode = claimAirdrops(pendingAirdrops);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function claimNFTAirdrop(address sender, address receiver, address token, int64 serial) public returns(int64 responseCode){
        IHederaTokenService.PendingAirdrop[] memory pendingAirdrops = new IHederaTokenService.PendingAirdrop[](1);

        IHederaTokenService.PendingAirdrop memory pendingAirdrop;
        pendingAirdrop.sender = sender;
        pendingAirdrop.receiver = receiver;
        pendingAirdrop.token = token;
        pendingAirdrop.serial = serial;

        pendingAirdrops[0] = pendingAirdrop;

        responseCode = claimAirdrops(pendingAirdrops);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function claimAirdrops(address[] memory senders, address[] memory receivers, address[] memory tokens, int64[] memory serials) public returns (int64 responseCode) {
        uint length = senders.length;
        IHederaTokenService.PendingAirdrop[] memory pendingAirdrops = new IHederaTokenService.PendingAirdrop[](length);
        for (uint i = 0; i < length; i++) {
            IHederaTokenService.PendingAirdrop memory pendingAirdrop;
            pendingAirdrop.sender = senders[i];
            pendingAirdrop.receiver = receivers[i];
            pendingAirdrop.token = tokens[i];
            pendingAirdrop.serial = serials[i];

            pendingAirdrops[i] = pendingAirdrop;
        }

        responseCode = claimAirdrops(pendingAirdrops);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }
}
