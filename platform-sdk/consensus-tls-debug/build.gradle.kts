// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

description = "mTLS TLS 1.3 Debug Tool"

application { mainClass = "org.hiero.consensus.tls.debug.MtlsDebug" }