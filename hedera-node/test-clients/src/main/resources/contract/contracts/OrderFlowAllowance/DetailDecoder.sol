// SPDX-License-Identifier: WTFPL
pragma solidity >=0.8.4;

// Library to decode prefix key and order detail info

library DetailDecoder {

    uint256 constant MASK63 = (uint256(1) << 63) - 1;
    uint256 constant MASK24 = (uint256(1) << 24) - 1;

    // Detail layout
	// •	[193..255] quantity (63 bits)
	// •	[130..192] numerator (63 bits)
	// •	[ 67..129] denominator (63 bits)
	// •	[ 43.. 66] deviation (24 bits, 0..1e6)
	// •	[ 19.. 42] minFillBps (24 bits, 0..1e6; 1e6 = FOK, 0 = no min)
	// •	[  0.. 18] unused (19 bits reserved)

    function quantity(uint256 d) internal pure returns (uint256)   { return (d >> 193) & MASK63; }
    function numerator(uint256 d) internal pure returns (uint256)  { return (d >> 130) & MASK63; }
    function denominator(uint256 d) internal pure returns (uint256){ return (d >>  67) & MASK63; }
    function deviation(uint256 d) internal pure returns (uint256)   { return (d >>  43) & MASK24; }
    function minFill(uint256 d) internal pure returns (uint256)   { return (d >>  19) & MASK24; }

    function encode(
        uint256 qty,        // 63-bit
        uint256 num,        // 63-bit
        uint256 den,        // 63-bit
        uint256 devBps,    // 24-bit (0..1e6)
        uint256 minFillBps  // 24-bit (0..1e6)
    ) internal pure returns (bytes32 out) {
        unchecked {
            uint256 v =
                  ((qty      & MASK63) << 193)
                | ((num      & MASK63) << 130)
                | ((den      & MASK63) <<  67)
                | ((devBps  & MASK24) <<  43)
                | ((minFillBps & MASK24) << 19);
            out = bytes32(v);
        }
    }
}
