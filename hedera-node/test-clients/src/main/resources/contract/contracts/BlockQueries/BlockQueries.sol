// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract BlockQueries {

    event Info(uint n);

    // Emit log with blobbasefee
    function getBlobBaseFee() public {
        emit Info(block.blobbasefee);
    }

    // Recurse n-1 times then finally emit log with blobbasefee
    function getBlobBaseFeeR(int n) public {
        if (n <= 0) getBlobBaseFee();
        else {
            (bool success,) = address(this).call(abi.encodeWithSignature("getBlobBaseFee()"));
            require(success, "fail");
        }
    }
}
