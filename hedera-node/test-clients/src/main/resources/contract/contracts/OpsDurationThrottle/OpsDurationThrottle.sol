// SPDX-License-Identifier: MIT
pragma solidity ^0.8.9;

contract OpsDurationThrottle {
    function run() public returns (uint) {
        uint256[12] memory input;
        //(G1)x
        input[0] = uint256(0x2cf44499d5d27bb186308b7af7af02ac5bc9eeb6a3d147c186b21fb1b76e18da);
        //(G1)y
        input[1] = uint256(0x2c0f001f52110ccfe69108924926e45f0b0c868df0e7bde1fe16d3242dc715f6);
        //(G2)x_1
        input[2] = uint256(0x1fb19bb476f6b9e44e2a32234da8212f61cd63919354bc06aef31e3cfaff3ebc);
        //(G2)x_0
        input[3] = uint256(0x22606845ff186793914e03e21df544c34ffe2f2f3504de8a79d9159eca2d98d9);
        //(G2)y_1
        input[4] = uint256(0x2bd368e28381e8eccb5fa81fc26cf3f048eea9abfdd85d7ed3ab3698d63e4f90);
        //(G2)y_0
        input[5] = uint256(0x2fe02e47887507adf0ff1743cbac6ba291e66f59be6bd763950bb16041a0a85e);
        //(G1)x
        input[6] = uint256(0x0000000000000000000000000000000000000000000000000000000000000001);
        //(G1)y
        input[7] = uint256(0x30644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd45);
        //(G2)x_1
        input[8] = uint256(0x1971ff0471b09fa93caaf13cbf443c1aede09cc4328f5a62aad45f40ec133eb4);
        //(G2)x_0
        input[9] = uint256(0x091058a3141822985733cbdddfed0fd8d6c104e9e9eff40bf5abfef9ab163bc7);
        //(G2)y_1
        input[10] = uint256(0x2a23af9a5ce2ba2796c1f4e453a370eb0af8c212d9dc9acd8fc02c2e907baea2);
        //(G2)y_0
        input[11] = uint256(0x23a8eb0b0996252cb548a4487da97b02422ebc0e834613f954de6c7e0afdc1fc);
        //multiplies the pairings and stores a 1 in the first element of input
        assembly {
            if iszero(
                call(not(0), 0x08, 0, input, 0x0180, input, 0x20)
            ) {
                revert(0, 0)
            }
        }
        return input[0];
    }
}