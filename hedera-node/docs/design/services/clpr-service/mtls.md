# CLPR mTLS (Two-Tier Cert Model)

> Prereq: `sync-workflow.md` (class roles), `clpr-hiero-spec.md` §4.4 (TLS cert model).
> This doc covers the high-level design of the mTLS two-tier cert model used for
> peer-to-peer CLPR sync calls (clpr-spec PR #46 §3.4), and how it behaves under the
> Endpoint Manifest feature (clpr-spec ADRs `2026-07-03-clpr-endpoint-manifests.md` and
> `2026-07-14-endpoint-sync-streaming-and-mtls.md`), gated by `clpr.endpointManifestEnabled`
> (see [Endpoint Manifest interaction](#endpoint-manifest-interaction)).

## Trust model

CLPR sync is **mutually authenticated**: both the connecting client and the serving node
present a certificate, and each validates the other against a CA it already holds — never
against the system PKI and never by hostname.

The model is two-tier:

```
On-chain (ClprEndpoint.tls_certificate)
  └─ ECDSA P-384 CA cert         ← stable, published; the trust anchor peers pin
In-memory only
  └─ Ed25519 ephemeral leaf cert  ← generated at startup; presented at TLS handshake
       signed by ↑ CA key
```

- Each node owns a long-lived **CA keypair** whose certificate it publishes on-chain in
  `ClprEndpoint.tls_certificate`. This is the anchor other nodes pin.
- Each node generates an **ephemeral leaf** at startup, signed by its CA. The leaf is what
  it actually presents in the handshake. It lives only in memory — regenerated on restart,
  never persisted, never published.

Both directions of the handshake apply the same rule: accept the presented leaf only if it
was signed by a trusted CA and is not itself a CA cert. They differ only in *which* CA(s)
they trust:

- **Outbound** (client validating the server): the single CA pinned for that peer, taken
  from the peer's on-chain `tls_certificate`.
- **Inbound** (server validating the connecting client): the *set* of all peer CAs this
  node currently knows. There is no on-chain peer roster, so this set is assembled from the
  node's local knowledge of peers (see below).

## Listener topology

CLPR `sync` requires mutual auth, so when mTLS is enabled it runs on its own listener,
bound to `clpr.mtlsPort` and requiring a client certificate. It cannot share the HAPI TLS
port: that port serves ordinary HAPI clients that must not be forced to present a CLPR
certificate, and a listener's TLS configuration cannot be varied per-service.

|   listener    |          port           |   client auth    |                            serves                            |
|---------------|-------------------------|------------------|--------------------------------------------------------------|
| plain         | `grpc.port`             | none             | HAPI + CLPR `discoverEndpoints` (+ `sync` when mTLS **off**) |
| TLS           | `grpc.tlsPort`          | none             | HAPI                                                         |
| node-operator | `grpc.nodeOperatorPort` | none (localhost) | queries only                                                 |
| **CLPR mTLS** | `clpr.mtlsPort`         | **required**     | CLPR `sync` only                                             |

The advertised `ClprEndpoint.service_endpoint.port` must point at `clpr.mtlsPort` so peers
dial the sync listener. When mTLS is **disabled**, the dedicated listener is not started,
`sync` stays on the plain port, and behaviour is exactly as before — this is the path used
in local dev and tests.

## Classes

- **`ClprCaCertManager`** — loads the node's CA cert and key from operator-provisioned files
  (`clpr.caCrtPath` / `clpr.caKeyPath`); see [CA key and cert encodings](#ca-key-and-cert-encodings)
  for the accepted formats. Empty paths mean mTLS is not configured; a missing or unreadable file disables
  mTLS with a warning. This is the single source of the `isMtlsEnabled()` switch.
- **`ClprLeafCertManager`** — on top of the CA, generates the ephemeral leaf keypair at
  startup and signs it with the CA key. Exposes the leaf cert/key used by both the outbound
  client and the inbound listener; yields nothing when mTLS is disabled.
- **`ClprMtlsTrust`** — the shared leaf-validation logic for both handshake directions,
  exposed as trust managers for the outbound (pin one CA) and inbound (pin the known-peer-CA
  set) cases. Hostname verification is intentionally skipped — endpoints are identified by
  their pinned CA, not a DNS name.
- **`ClprEndpointClient`** — outbound dialer. When mTLS is enabled it presents this node's
  leaf and pins the peer's on-chain CA; when disabled it connects in plaintext.
- **`ClprEndpointClientCache`** — caches one long-lived client per peer (`host:port`), keyed
  additionally by the peer's pinned CA cert, so repeated syncs to the same peer reuse the
  underlying HTTP/2 connection instead of a fresh TCP+mTLS handshake every tick. A cache hit
  requires the peer's *current* on-chain `tls_certificate` to match what the cached client was
  built with; on mismatch the stale client's channel is shut down and a new one is built
  pinning the new CA — see [Outbound client caching](#outbound-client-caching).
- **`NettyGrpcServerManager`** — owns the server-side listeners. When mTLS is enabled it
  moves `sync` off the shared ports onto the dedicated `clpr.mtlsPort` listener configured
  for mutual auth, validating connecting clients against the known-peer-CA set. Listener
  startup failure is **fatal by design**: the operator explicitly configured mTLS, so a
  listener that cannot start is a hard failure that aborts node startup rather than silently
  downgrading to plaintext.
- **`ClprChannelManager`** — supplies the inbound trust anchor set: the CA certificates
  of all peers it currently knows. The set is read live on each handshake, so peers learned
  after startup are trusted without restarting the listener. Also resolves *outbound* dial
  targets per Channel, per the `clpr.endpointManifestEnabled` flag — see
  [Endpoint Manifest interaction](#endpoint-manifest-interaction) for how the two paths differ.

## TLS provider (Ed25519 leaf)

The ephemeral leaf is **Ed25519**, but Ed25519 is not usable as a *local* TLS authentication
identity through the usual gRPC/Netty providers: the JDK's SunJSSE reports "no available
authentication scheme", and `netty-tcnative`/BoringSSL rejects Ed25519 private-key material. The CLPR
mTLS listener and outbound client therefore run their TLS on the **BouncyCastle JSSE provider**
(`BCJSSE`, from `bctls`), which does support Ed25519 leaf authentication. This is encapsulated in
`ClprMtlsContexts`, which builds a Netty `JdkSslContext` around a `BCJSSE` `SSLContext` (with explicit
`h2` ALPN for gRPC). Two BouncyCastle specifics are required and handled there:

- `BouncyCastleProvider` and `BouncyCastleJsseProvider` are **registered** in `java.security.Security`
  (appended, so the JVM's default TLS is unaffected) — Netty's ALPN bridge resolves the provider by
  name.
- the leaf key is generated with, and fed to BCJSSE through, BouncyCastle (a BC `PKCS12` keystore) so
  it stays a BC key; a SunEC `EdDSA` key is rejected by BC's TLS crypto.

Only the dedicated CLPR mTLS channels use `BCJSSE`; all other node TLS is unchanged.

## Outbound client caching

Outbound clients are **not** rebuilt on every sync call. `ClprEndpointClientCache` keeps one
long-lived `ClprEndpointClient` per peer address, keyed by `host:port` plus the peer's pinned
CA cert (normalized to `null` in plaintext mode, since the cert is unused there — this lets a
plaintext sync and a plaintext discovery call to the same peer share one client). This node's
own leaf identity is a per-process singleton that never rotates for the life of the process,
so it isn't part of the cache key.

A lookup (`clientFor(host, port, peerTlsCertificate, leafCredentials)`) is a cache hit only if
the peer's cert passed in matches the cert the cached client was built with. On a mismatch —
the peer rotated its CA, whether observed via the legacy config/discovery path or via an
Endpoint Manifest update (see below) — the stale client's gRPC channel is shut down and a new
one is built pinning the new CA. This is what preserves the "always pins the *current* CA"
guarantee while still amortizing the TCP + mTLS handshake cost across sync ticks: correctness
comes from the cache-key comparison on each call, not from rebuilding unconditionally.
`ClprChannelManager.stop()` shuts down every cached channel via `shutdownAll()`.

## How peers and CAs are learned

The inbound trust set is only as complete as the node's knowledge of its peers. A peer whose
CA has not yet been learned is rejected at the handshake and simply retries once the node
catches up — there is no persistent failure state. *How* peers/CAs are learned depends on
`clpr.endpointManifestEnabled` (default `false`, until every peer verifier has migrated):

- **Flag off (legacy)** — peer endpoints (and thus their published CA certs) are learned
  from `ClprLedgerConfiguration.endpoints` (seeded into a node-local cache on first
  observation of a Channel), `completeChannel`, discovery (`discoverEndpoints`, while mTLS
  is disabled — see [Discovery under mTLS](#discovery-under-mtls)), and node-local
  rehydration after a restart.
- **Flag on** — see [Endpoint Manifest interaction](#endpoint-manifest-interaction) below;
  discovery plays no part.

Both modes populate the same node-local peer-endpoint cache (`ClprChannelManager`), which is
what the inbound mTLS trust set (peer CA certificates, keyed by subject DN) is derived from.

## Endpoint Manifest interaction

The Endpoint Manifest feature (`clpr.endpointManifestEnabled`) replaces the ad hoc
config/discovery-seeded peer list with `Channel.endpoint_manifest` — a `ClprEndpointManifest`
(the same `ClprEndpoint` shape, `service_endpoint` + `tls_certificate`, that carries the CA
cert discussed above) cached directly on the `Channel` record in state, alongside
`Channel.endpoint_manifest_version`. It has two entry points, both already implemented:

- **Initial population, at `completeChannel`** — the extended `verifyConfig` call returns a
  proven `ClprEndpointManifest`; it's truncated to this ledger's `max_peer_endpoints` and
  stored on the Channel (`Channel.endpoint_manifest`/`endpoint_manifest_version`). The same
  truncated list is also pushed into the node-local peer-endpoint cache, so the CA(s) it
  carries immediately join the inbound mTLS trust set.
- **Mid-life updates, via bundles** — a `submitBundle` verifier response can carry a
  `new_endpoint_manifest`. When its `version` strictly advances the Channel's stored version,
  the CLPR Service atomically replaces `Channel.endpoint_manifest`/`endpoint_manifest_version`
  as part of accepting the bundle (spec §4.2 Step 1b) — this is the propagation path for a
  peer rotating its CA cert mid-Channel-life.

## Self-identification

A node must not try to sync with itself. Beyond the gRPC/TLS ports, `NodeIdentity` also
recognises the node's own `clpr.mtlsPort`, so its own advertised CLPR endpoint is never
selected as a sync target.

## Discovery under mTLS

`sync` and `discoverEndpoints` currently share a single advertised endpoint port. Once that
port is the mutual-auth sync listener, an ordinary (non-mTLS) discovery call dialed there
would fail. Until discovery has its own non-mTLS address, it is **suppressed while mTLS is
enabled** (peers/CAs are still learned by the other means above). Giving discovery a
dedicated `ClprEndpoint.discovery_endpoint` is tracked as follow-up (beyond spec PR #46).

Independently of mTLS, discovery is also suppressed whenever `clpr.endpointManifestEnabled`
is on — the Endpoint Manifest (see
[Endpoint Manifest interaction](#endpoint-manifest-interaction)) is the sole source of peer
endpoints/CAs in that mode, so `discoverEndpoints` is never called regardless of the mTLS
setting.

## Config summary

```
clpr.caCrtPath               = ""     # CA cert (X.509 PEM or DER); empty = mTLS disabled
clpr.caKeyPath               = ""     # CA key (unencrypted; see encodings below); empty = mTLS disabled
clpr.mtlsPort                = 50214  # dedicated mutual-auth listener (per-node)
clpr.endpointManifestEnabled = false  # network-governed; see Endpoint Manifest interaction
```

`clpr.endpointManifestEnabled` is independent of mTLS configuration — either can be on or off
regardless of the other — but it changes *how* peer endpoints/CAs feeding the mTLS trust model
above are learned; see [Endpoint Manifest interaction](#endpoint-manifest-interaction).

The CA cert/key are operator-provisioned rather than auto-generated — creating a proper CA
certificate is a deployment concern. The operator advertises `clpr.mtlsPort` as the CLPR
endpoint's service port in the ledger-configuration transaction.

### CA key and cert encodings

`ClprCaCertManager` accepts the operator-provisioned files in these encodings:

- **`clpr.caKeyPath`** — an **unencrypted** private key, as either:
  - **PEM** — PKCS#8 (`-----BEGIN PRIVATE KEY-----`) or the traditional SEC1/PKCS#1 form
    (`-----BEGIN EC PRIVATE KEY-----`); or
  - **binary DER** — PKCS#8.

  PEM vs DER is detected by the leading byte (DER starts with the ASN.1 `SEQUENCE` tag `0x30`; PEM
  never does). The key algorithm is inferred from the material, so the loader itself does not reject
  non-EC keys — but per spec the CA is **ECDSA P-384**, and leaf signing (`SHA384withECDSA`) requires
  an EC key. **Encrypted (passphrase-protected) keys are not supported.**

  > Note: `openssl ecparam -genkey` emits SEC1 (`EC PRIVATE KEY`), which is accepted. If you have an
  > encrypted or otherwise unsupported key, convert it first, e.g.
  > `openssl pkcs8 -topk8 -nocrypt -in ca.key -out ca-pk8.key`.

- **`clpr.caCrtPath`** — an X.509 certificate in PEM or DER (loaded via `CertificateFactory`).
