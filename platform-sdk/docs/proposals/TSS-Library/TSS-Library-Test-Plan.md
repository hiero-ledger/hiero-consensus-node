# TSS-Library Testing plan

## Pairings API

API Implementing Pairings, Groups, and Field Operations

### Technical Tests

The aim of these tests is to validate the JNI wrapping is performed correctly
Serialization Test Cases
Deserialization Test Cases
Operations against know results
Exceptions and failure scenarios
Points not in the curve

### Functionality Tests

The aim of these tests is to validate the correctness of the operations performed with the Java wrapping of the library.

#### Test Cases for GroupElements Addition

Identity element: G \+ 0 \= G
Inverse element: G \+ (-G) \= 0
Associativity: (G1 \+ G2) \+ G3 \= G1 \+ (G2 \+ G3)
Commutativity: G1 \+ G2 \= G2 \+ G1

##### Negation

Double negation: \-(-G) \= G
Identity element: G \+ (-G) \= 0

##### Scalar Multiplication

Identity element: 1 \* G \= G
Zero element: 0 \* G \= 0
Distributivity: (a \+ b) \* G \= a \* G \+ b \* G
Associativity: a \* (b \* G) \= (a \* b) \* G
Negation: (-a) \* G \= \-(a \* G)

##### Point Doubling

Doubling identity: 2 \* 0 \= 0
Relation to scalar multiplication: 2 \* G \= G \+ G

#### Test Cases for FieldElements

##### Addition

Identity element: a \+ 0 \= a
Inverse element: a \+ (-a) \= 0
Associativity: (a \+ b) \+ c \= a \+ (b \+ c)
Commutativity: a \+ b \= b \+ a

##### Subtraction

Definition: a \- b \= a \+ (-b)
Identity element: a \- 0 \= a
Inverse element: a \- a \= 0

##### Multiplication

Identity element: a \* 1 \= a
Zero element: a \* 0 \= 0
Associativity: (a \* b) \* c \= a \* (b \* c)
Commutativity: a \* b \= b \* a
Distributivity: a \* (b \+ c) \= a \* b \+ a \* c
Inverse property: a \* Inverse(a) \= 1

#### Test Cases for Pairings

##### Bilinearity

Generate random group elements g1, g2, h1, h2.
verify::  e(g1 , g2) \= e(g2 , g1)?
Verify the equation:“a”, “b” member of “Fq” (Finite Field), “P” member of “G₁”, and “Q” member of “G₂”,
\*        then e(a×P, b×Q) \= e(ab×P, Q) \= e(P, ab×Q) \= e(P, Q)^(ab)
Scalar values k1 and k2: Verify the equation: e(k1\*g1, g2\*k2) \= e(g1, g2) ^k1\*k2
Test with different combinations of group elements, including identity elements (point at infinity).

#### Input selection

**Test with extreme values** (e.g., very large or small scalars, specific points on the curve). **Ensure all inputs are validated**, e.g., checking if points lie on the curve.**Uniformly distributed**: Generate random points within the group's defined range.
**Edge cases**: Include points near the boundaries of the group's definition.

### Performance Tests

Measure the time taken for pairings, group operations, and field operations. → what are the performance parameters to compare against? What do we do with the results?
Compare performance from executing rust code directly and through JNI → it can be done using Criterion crate (​​https://crates.io/crates/criterion)

## Signatures Library

Library Producing EC Private-Public Keys and Signatures

### Technical Tests

#### Key Generation Test

##### Randomness

The aim of these tests is to: detect incorrectly used pseudorandom number generators for exploitable errors and or detecting Key biases.

* Test N randomly generated keys and check their distribution is normal. → (maybe Apache Commons Math?) (frequency, serial, poker, run, gap)
* Test a single key byte’s distribution → (Chi Square test ?)Measure the entropy of the generated keys.

#### Signature Tests

* Test N randomly generated signatures and check their distribution is normal. → (maybe Apache Commons Math?) (frequency, serial, poker, run, gap)
* ensure signatures vary given the same input message and different signing keys (by randomness in the algorithm). → which tool to use to determine how much change is good?

### Functional Tests

#### Private Key Generation

##### Key Generation Error Test

Attempt to generate keys with invalid parameters and expect proper error handling.

##### Valid Key Test

Generate a key pair and verify that the public key is a valid point on the elliptic curve.

##### Randomness Test

Generate multiple key pairs and ensure that keys are not repeated and appear random.

##### Private Key Range Test

Ensure that the private key is within the valid range (1 to n-1, where n is the order of the curve).

#### Public Key Derivation

##### Correctness Test

For a known private key, check that the derived public key matches the expected value.

##### Consistency Test

Derive the public key multiple times from the same private key and verify that the results are consistently the same.

#### Signature Generation

##### Signature Validity Test

Sign a known message and verify the signature using the corresponding public key.

##### Deterministic Signatures Test

sign the same message multiple times and ensure the signature is identical.

##### Signature Error Test

Try signing with an invalid private key or malformed data to verify that errors are handled gracefully.

#### Signature Verification

##### Valid Signature Test

Verify a correctly generated signature and ensure it is accepted. → should we provide the key externally to the test?

##### Invalid Signature Test

Modify a valid signature slightly (e.g., change a single byte) and check that it is rejected.

##### Edge Case Signature Test

Test signatures where parameters hit boundary values, such as the low or high ends of their ranges. → should we use max and min in the range of Fp to get these values?

### Performance Tests

Measure the max, min avg time taken for key generation, to sign and verify messages. → Do we want to define acceptance parameters? Reports?

## TSS Library

### Functional Tests

#### Genesis and Key Generation

##### Valid Parameters Test

Check that the setup accepts valid curve parameters and threshold settings.

##### Participant Keys Validity Test

Ensure that each participant's key is correctly checked against invalid inputs.

#### Shares Generation

Tests to verify that key shares are distributed and handled securely and correctly.

* Invalid polynomial commitment
* Different PaticipantDictionary
* Detection of invalid proof

##### Share protection validation

Each participant can decrypt their intended parts and no others.

##### Share reconstruction

* An aggregated private share has the same length of its original source
* An aggregated private share is different from its original source
* Threshold number of messages can produce a valid public key
* Meny Low entropy keys plus one enough entropy key still produces a valid share
* Public key does not change when rehashing

##### Graceful Degradation

Test how the library handles corrupted shared inputs (low or no entropy, same input).

##### Ciphertext Length Check Test

Check that each share of share is a valid elgamal ciphertext

##### Public Key Aggregation Test

Verify that the aggregated public key correctly represents the combination of individual shares.

##### Randomness Quality Test

Under the same conditions, and non-deterministic RNG, different TssMessages are produced for the same input.

#### Signature Generation Tests

Validate the functionality and correctness of the signature generation process.

##### Correct Signature Test

Check that signatures generated by the threshold number of participants are valid.

##### Invalid Signature Test

Ensure that signatures generated with less than the threshold number of participants are invalid.

##### Fixed length of threshold signature

Our scheme ensures that the size of a threshold signature is fixed (i.e., not depending on the number of signers)

##### Verification Accuracy

Ensure that valid signatures are always accepted and invalid signatures (or tampered messages) are rejected.

##### Consistency Test

Sign the same message multiple times with the same subset of participants and verify that signatures are consistent (if the scheme is deterministic).

##### Boundary Messages

Test with minimum and maximum length messages, or messages consisting of unusual or special characters.

#### Signature Verification Tests

Ensure that the signature verification process is reliable and accurate.

##### Valid Signature Verification Test

Verify that valid signatures are accepted by the verification algorithm.

##### Invalid Signature Verification Test

Modify a signature slightly and check that it is rejected.

##### Edge Case Verification Test

Test signatures with boundary values and special cases, such as zero, near-zero, and maximum possible values.

##### Key Compromise Simulation Test

Simulate the compromise of one or more key shares and ensure that the system remains secure if below the threshold.

### Performance Tests

JMH performance test at the top level.
generate shares, aggregate signatures, sign and rekey.
Adding the results  to the class as a comment.
Add MH results as comments to the class as a comment.

### Notes

We prefer to do high level test first and start going down the implementation layers

## Resources

* Eth: [https://github.com/ethereum/py\_pairing/blob/master/py\_ecc/bn128/bn128\_curve.py](https://github.com/ethereum/py_pairing/blob/master/py_ecc/bn128/bn128_curve.py)
* Elliptic curve calculator: [http://www.christelbach.com/ECCalculator.aspx](http://www.christelbach.com/ECCalculator.aspx)
* https://csrc.nist.gov/projects/random-bit-generation/
* [https://github.com/google/paranoid\_crypto/blob/main/docs/randomness\_tests.md](https://github.com/google/paranoid_crypto/blob/main/docs/randomness_tests.md)
