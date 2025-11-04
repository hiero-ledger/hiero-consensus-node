package org.hiero.consensus.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import org.junit.jupiter.api.Test;

class SignerFactoryTest {

    @Test
    void test() throws NoSuchAlgorithmException, NoSuchProviderException {
//        for (var provider : Security.getProviders()) {
//            System.out.println(provider.getName());
//        }

//        String keyType = "Ed25519"; // Change to desired key type
//        for (Provider provider : Security.getProviders()) {
//            for (Provider.Service service : provider.getServices()) {
//                if ("KeyPairGenerator".equals(service.getType()) && keyType.equalsIgnoreCase(service.getAlgorithm())) {
//                    System.out.println(provider.getName() + " provides KeyPairGenerator for " + keyType);
//                }
//            }
//        }

        for (final SigningType signingType : SigningType.values()) {
            if(signingType != SigningType.ED25519_SODIUM) {
                continue;
            }
            System.out.println("Creating signer for type: " + signingType);
            SignerFactory.createSigner(signingType, new SecureRandom());
        }

    }
}