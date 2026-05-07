# RFC-001: Post-Quantum TLS Transition for Consensus Gossip

|              |                   |
|--------------|-------------------|
| **Status**   | Proposal          |
| **Audience** | Consensus team    |
| **Date**     | 2026-05-07        |
| **Tags**     | tls, post-quantum |

---

# Abstract

This RFC proposes migrating gossip mTLS from classical cryptography to post-quantum cryptography using:

- Hybrid `X25519MLKEM768` key exchange
- ML-DSA authentication
- BCJSSE as the TLS provider

Event and state signing are explicitly out of scope.

---

# 1. Motivation

Current gossip TLS relies on RSA-3072, EC-384, x25519, which are not quantum resistant.

This proposal upgrades gossip transport to TLS 1.3 and its authentication and key exchange phases to post-quantum primitives, 
while minimizes impact on existing trust semantics.

This proposal focuses first on transport authentication and key establishment because those changes can be deployed independently from consensus-signature migration.

AES-256-GCM remains unchanged.

---

# 2. Scope

## In scope

- TLS version update to 1.3
- PQ TLS authentication via ML-DSA
- PQ key exchange via `X25519MLKEM768`
- JSSE provider replacement
- Roster changes required for PQ trust anchors
- Rollout and operational strategy

## Out of scope

- Event-signature migration
- State-signature migration
- Consensus protocol changes
- JDK upgrade strategy

---

# 3. Current model

Today each node has:

- `sigKey / sigCert`
  - Long-lived RSA identity
  - Published in roster as `gossip_ca_certificate`
  - Used for event/state signing
- `agrKey / agrCert`
  - Ephemeral TLS identity
  - Generated at startup
  - Signed by `sigKey`

The roster acts as the transport trust root.

TLS trust today:
```text
 sigCert (CA)
   →
    agrCert 
```


---

# 4. Proposed approach

## 4.1 TLS stack

|       Component       |                       Purpose                        |
|-----------------------|------------------------------------------------------|
| BCJSSE                | TLS implementation                                   |
| BouncyCastle provider | ML-KEM / ML-DSA support                              |
| ACCP                  | Classical crypto acceleration (optional)             |
| Custom `JcaJceHelper` | Routes ML-KEM to BC while allowing ACCP acceleration |

BCJSSE is required because current default JSSE providers do not support PQ TLS authentication.

ACCP is optional and provides acceleration for compatible classical primitives and, in some builds, ML-DSA operations.

BCJSSE interoperability constraints still require ML-KEM operations to remain pinned to BouncyCastle while ACCP accelerates compatible classical and ML-DSA operations.

---

## 4.2 Cryptography

|         Role         |            Algorithm             |
|----------------------|----------------------------------|
| Key exchange         | `X25519MLKEM768`                 |
| TLS authentication   | ML-DSA                           |
| Symmetric encryption | `TLS_AES_256_GCM_SHA384`         |
| Existing sigCert     | RSA-3072 retained                |
| Legacy agrCert       | EC-384 retained during migration |
| PQ agrCert           | ML-DSA self-signed cert          |

Regarding authentication, ML-DSA signatures have different flavors to select from. Experiments to date used ML-DSA-65 because it provides a balanced trade-off between security level (NIST category 3), signature size (~3.3 KB), and verification cost for transport authentication. ML-DSA-44 trades smaller artifacts for a lower security level (category 2). ML-DSA-87 increases computational and bandwidth cost (~4.6 KB signatures) without clear operational benefit at this layer.

For key exchange, we've measured the key exchange process mostly using the hybrid `X25519MLKEM768` which insurances against an ML-KEM-only break, but we can use pure ML-KEM.

ML-DSA-in-TLS is at draft-ietf-tls-mldsa-03 (Informational track, submitted to IESG as of May 2026). Hybrid X25519MLKEM768 is at draft-ietf-tls-ecdhe-mlkem-04 (Standards Track, in the RFC Editor Queue). Both are far enough through the IETF process that wire-format changes are unlikely in the near term, though neither is published as an RFC yet. 

---

# 5. Changes overview

## 5.1 TLS configuration

Main runtime changes:

- TLS version to 1.3
- JSSE provider changes from `SunJSSE` → `BCJSSE`
- KEY_MANAGER_FACTORY/TRUST_MANAGER_FACTORY changes from `SunX509` → `PKIX`
- Symmetric encryption value changes from `TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384` → `TLS_AES_256_GCM_SHA384`
- Named groups include:
  - `X25519MLKEM768`
  - `secp384r1`
- Signature schemes include both classical and ML-DSA during migration

---

## 5.2 Certificate handling

Key findings:

- Existing self-signed RSA `sigCert` can remain unchanged
- `agrCert` signing changes to RSA-PSS
- New ML-DSA self-signed certs are introduced for PQ authentication

No migration of existing roster certificates is required.

---

## 5.3 Roster changes

Add a new optional field:

```protobuf
bytes pq_gossip_ca_certificate = N;
```

Stored in:

- `RosterEntry`
- `Node`
- `NodeCreate`
- `NodeUpdate`

The field contains a DER-encoded ML-DSA self-signed cert (~5 KB).

During migration:

- Optional in mixed mode
- Required once PQ-only transport is enabled

---

## 5.4 Trust model

Trust stores consume both:

- `gossip_ca_certificate`
- `pq_gossip_ca_certificate`

During migration both classical and PQ authentication are accepted.

After migration only ML-DSA remains.

Trust derivation remains PKIX-based throughout. The migration adds algorithms; it does not change the trust mechanism.

---

## 5.5 Tooling and ecosystem

Required updates:

- PQ key generation tooling
- DAB handlers
- Schema migration
- SDK protobuf regeneration
- Other Roster consumers (BlockNode/MirrorNode) can ignore this key while used as TLS auth mechanism

Proto compatibility allows phased rollout.

---

# 6. Migration plan

## Phase 1 — New TLS stack, classical authentication

- Add ACCP (optional, also can be its own independent phase)
- Move to BCJSSE
- Migrate to tls 1.3
- Add hybrid helper (if ACCP is included)
- Switch to PKIX
- Add `X25519MLKEM768` to named groups; add `rsa_pkcs1_sha384` to signatureSchemes for cert-chain compatibility
- Keep classical authentication

No roster or HAPI changes.

---

## Phase 2 — PQ-capable transport

- Add `pq_gossip_ca_certificate` (new field, default to null, no migration necessary)
- Add tooling support
- Nodes begin publishing PQ certs

Requires HAPI coordination.

---

## Phase 3 — Mixed authentication

- Enable ML-DSA signature schemes
- Trust stores consume both cert types
- Nodes authenticate using either classical or PQ certs

Network remains mixed-mode until all nodes publish PQ certs.

---

## Phase 4 — PQ-required transport

- Remove classical TLS authentication schemes
- Require `pq_gossip_ca_certificate`
- Retain hybrid key exchange

---

## Phase 5 — Event/state signing migration

Out of scope for this RFC, but the new key added in the roster can help gradually adopting the new scheme for these signatures.

---

# 7. Operational considerations

## Native dependencies

ACCP introduces native dependencies and architecture-specific artifacts (all included in the upstream dependency).
Run time requires (not-mandatory for now) native-access JVM flags (jvm must run with: `--enable-native-access=com.amazon.corretto.crypto.provider`)

---

## Provider ordering

Recommended order:

1. ACCP
2. BC
3. BCJSSE

Incorrect ordering can silently disable PQ key exchange.

---

## Standards tracking

`mldsa65` and `X25519MLKEM768` currently depends on an IETF draft.

Moving forward would imply:
- Draft tracking
- BC/BCJSSE upgrade coordination

---

### Node key provisioning

The migration introduces a second transport private key on every node.
Provisioning and backup procedures need to expand to include PQ key lifecycle management.

---

## Rollback

Each migration phase is independently rollback-able:

- Phase 1: highly rollback-able at any point (even after successfully release and deploy on maintain)
- Phase 2: this includes api changes and state changes, its rollback-able before releasing to main. New content can be ignored if rollback is needed after releasing.
- Phases 3 & 4: highly rollback-able at any point (even after successfully release and deploy on maintain)

---

# 8. Pending decisions

| # | Decision                                                              |
|---|-----------------------------------------------------------------------|
| 1 | Endorse overall direction                                             |
| 2 | Should we ship ACCP independently?                                    |
| 3 | Select ML-DSA parameter set (44/65/87)                                |
| 4 | Define whether to use hybrid kx or pure pq                            |
| 5 | Accept that initial deployment are based on ietf drafts (not yet RFC) |
| 6 | About unifiying the agreement and signing roles of the new cert       |
| 7 | Phase implementation cadence                                          |

---

# 9. Detailed System changes

## 9.1 TLS factory and configuration

`TlsFactory` and the surrounding TLS-construction code are parameterized so the JSSE provider, KMF/TMF type, keystore type, named groups, and signature schemes are no longer hardcoded. The runtime configuration is:

- JSSE provider: `BCJSSE` (was `SunJSSE`).
- SSL_VERSION: `TLSv1.3` (was `TLSv1.2`).
- Switch KEY_MANAGER_FACTORY/TRUST_MANAGER_FACTORY type: `PKIX` (was `SunX509`). PKIX requires explicit revocation-checking configuration via `PKIXBuilderParameters.setRevocationEnabled(false)` because the network does not operate CRL/OCSP infrastructure.
- Keystore type: `PKCS12` is the working assumption pending verification under BCJSSE; the prototype uses `BKS` but this has not been proven required (Open Decision 3).
- Named groups: `["X25519MLKEM768", "secp384r1"]`. BCJSSE couples some signature-scheme activations to named-group membership, so `secp384r1` is structurally required even though it is not used as the active key-exchange group.
- Signature schemes during transition: `["mldsa65", "ecdsa_secp384r1_sha384", "ecdsa_secp256r1_sha256", "ed25519", "rsa_pss_rsae_sha384", "rsa_pss_rsae_sha256", "rsa_pkcs1_sha384"]`. (rsa_pkcs1_sha384 is added for cert-chain compatibility.)
- client and server socket factories to adapt to tls 1.3 parameters.
---

## 9.2 Certificate generation

BCJSSE applies the connection's permitted signature-scheme list to every certificate in the chain, including the trust anchor's self-signature. By default this rejects `SHA384withRSA` (PKCS#1 v1.5) self-signed CAs.

RFC 8446 §4.2.3 explicitly allows `rsa_pkcs1_*` schemes in the connection's signature-scheme list for cert-chain backward compatibility, while forbidding them for handshake `CertificateVerify` signatures. Advertising `rsa_pkcs1_sha384` in the connection's `signatureSchemes` therefore lets BCJSSE accept a PKCS#1 v1.5 self-signed CA in the chain without weakening handshake security — the protocol mandates PSS or ECDSA / Ed25519 / ML-DSA for the actual handshake signature regardless.

This finding has been verified end-to-end against BCJSSE TLS 1.3 with the production gossip key types (RSA-3072 sigKey, EC-384 agrKey).

The implications for the migration are:

- **The self-signed sigCert stays on PKCS#1 v1.5.** No DER change. No roster update for the cert-format migration.
- **The agrCert signing algorithm changes from `SHA384withRSA` to `SHA384withRSAandMGF1`.** This is a code-only change in the agrCert generation path. agrCert is regenerated at every node startup, so there is no on-disk artifact to migrate.
- **A second cert generation path produces ML-DSA keypairs and self-signed certs** for the new chain.

---

## 9.3 Trust path

Trust-store generation extends to consume two roster fields:

- `gossip_ca_certificate` (existing): DER bytes of the classical RSA self-signed cert.
- `pq_gossip_ca_certificate` (new): DER bytes of the ML-DSA-65 self-signed cert. Same shape as `gossip_ca_certificate`, just a different signature algorithm.

Both contribute trust anchors to the JSSE trust store. The TLS handshake configuration accepts both classical and ML-DSA signature schemes during the transition window. After classical retirement (Phase 4), only ML-DSA remains.


---

## 9.4 Roster schema

A new field is added to `RosterEntry` and to its mirrors in DAB transactions and the `Node` state structure:

```
RosterEntry {
    ...existing fields...
    bytes pq_gossip_ca_certificate = N;  // DER-encoded ML-DSA self-signed cert, ~5 KB
}
```

Mirrored in:

- `Node` (state proto)
- `NodeCreateTransactionBody` (DAB transaction)
- `NodeUpdateTransactionBody` (DAB transaction; `BytesValue` wrapper for optional update)

Validation rules:

- The field is **optional** during Phases 2 and 3.
- The field is **required** for nodes participating in Phase 4 PQ-only authentication.

A full self-signed cert is approximately 5 KB at the ML-DSA-65 parameter set: ~200–400 B of TBS metadata, ~2 KB of `SubjectPublicKeyInfo` (algorithm OID + 1952-byte raw key), and ~3.3 KB for the ML-DSA-65 self-signature blob.

---

# 10. Risks and pending work
Configuration of the providers and how they are selected is tricky.
Key interoperability might depend on the providers and how they are configured.
We should invest time confirming the interoperability of the existing keys with the target stack.

# Appendix — Key terms

|            Term            |                                    Meaning                                    |
|----------------------------|-------------------------------------------------------------------------------|
| BCJSSE                     | BouncyCastle JSSE provider                                                    |
| ML-KEM                     | Post-quantum key exchange primitive                                           |
| ML-DSA                     | Post-quantum signature scheme                                                 |
| `X25519MLKEM768`           | Hybrid classical + PQ TLS key exchange                                        |
| `sigCert`                  | Long-lived roster trust anchor                                                |
| `agrCert`                  | Ephemeral TLS identity certificate                                            |
| `pq_gossip_ca_certificate` | PQ trust anchor stored in roster                                              |
| PKIX                       | X.509 trust validation model                                                  |
| ACCP                       | Amazon Corretto Crypto Provider — a JCA provider backed by AWS-LC native code |
| PKCS#1 v1.5                | legacy RSA padding                                                            |
| PSS                        | the RSA padding mode required for handshake signatures in TLS 1.3.            |
