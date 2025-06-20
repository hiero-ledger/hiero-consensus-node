// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IHRC {
    function associate() external returns (uint256 responseCode);
    function dissociate() external returns (uint256 responseCode);
    function isAssociated() external returns (bool associated);
}

contract HRC is IHRC {
    function associate() public returns (uint256 responseCode) {
        return IHRC(this).associate();
    }

    function dissociate() public returns (uint256 responseCode) {
        return IHRC(this).dissociate();
    }

     function isAssociated() public returns (bool associated) {
        return IHRC(this).isAssociated();
    }
}
