// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IHRC904 {
    function cancelAirdropFT(address receiverAddress) external returns (int64 responseCode);
    function cancelAirdropNFT(address receiverAddress, int64 serialNumber) external returns (int64 responseCode);
    function claimAirdropFT(address senderAddress) external returns (int64 responseCode);
    function claimAirdropNFT(address senderAddress, int64 serialNumber) external returns (int64 responseCode);
    function rejectTokenFT() external returns (int64 responseCode);
    function rejectTokenNFTs(int64[] memory serialNumbers) external returns (int64 responseCode);
    function setUnlimitedAutomaticAssociations(bool enableAutoAssociations) external returns (int64 responseCode);
}

contract HRC904 is IHRC904 {
    function cancelAirdropFT(address receiver) public returns (int64 responseCode) {
        return IHRC904(this).cancelAirdropFT(receiver);
    }

    function cancelAirdropNFT(address receiver, int64 serial) public returns (int64 responseCode) {
        return IHRC904(this).cancelAirdropNFT(receiver, serial);
    }

    function claimAirdropFT(address sender) public returns (int64 responseCode) {
        return IHRC904(this).claimAirdropFT(sender);
    }

    function claimAirdropNFT(address sender, int64 serial) public returns (int64 responseCode) {
        return IHRC904(this).claimAirdropNFT(sender, serial);
    }

    function rejectTokenFT() public returns (int64 responseCode) {
        return IHRC904(this).rejectTokenFT();
    }

    function rejectTokenNFTs(int64[] memory serialNumbers) public returns (int64 responseCode) {
        return IHRC904(this).rejectTokenNFTs(serialNumbers);
    }

    function setUnlimitedAutomaticAssociations(bool enableAutoAssociations) public returns (int64 responseCode) {
        return IHRC904(this).setUnlimitedAutomaticAssociations(enableAutoAssociations);
    }
}
