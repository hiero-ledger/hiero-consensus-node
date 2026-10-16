// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.cryptography.hints.AggregationAndVerificationKeys;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsLibraryInitializationTest {
    private static final int CAPACITY = 4;
    private static final Bytes HINT = Bytes.wrap("hint");
    private static final Bytes PRIVATE_KEY = Bytes.wrap("private-key");
    private static final Bytes MESSAGE = Bytes.wrap("sign without preparation");
    private static final AggregationAndVerificationKeys KEYS =
            new AggregationAndVerificationKeys(new byte[] {1}, new byte[] {2});

    @Mock
    private Supplier<IsolatedHintsLibrary> keyLibrarySupplier;

    @Mock
    private Supplier<IsolatedHintsLibrary> preprocessingLibrarySupplier;

    @Mock
    private IsolatedHintsLibrary keyLibrary;

    @Mock
    private IsolatedHintsLibrary preprocessingLibrary;

    private HintsLibraryImpl subject;
    private Bytes crs;

    @BeforeEach
    void setUp() {
        subject = new HintsLibraryImpl(keyLibrarySupplier, preprocessingLibrarySupplier);
        crs = subject.newCrs((short) CAPACITY);
    }

    @Test
    void publicConstructorDefersPreparationEvenWhenHoldersCannotLoad() throws Exception {
        // A fresh copy avoids depending on which preparation holders other tests already initialized.
        // Its dependencies, including the original signing native image, remain in the parent loader.
        final var loader = withoutPreparationHolders();
        final var type = Class.forName(HintsLibraryImpl.class.getName(), true, loader);
        assertNotSame(HintsLibraryImpl.class, type);
        final var library = (HintsLibrary) type.getConstructor().newInstance();
        final var freshCrs = library.newCrs((short) CAPACITY);
        final var privateKey = library.newBlsPrivateKey();
        assertNotNull(library.signBls(MESSAGE, privateKey));

        // Prove the guard is effective and is reached only when preparation is actually requested.
        final var failure =
                assertThrows(AssertionError.class, () -> library.computeHints(freshCrs, privateKey, 0, CAPACITY));
        assertTrue(failure.getMessage().startsWith("Preparation holder loaded: "));
        final var another = (HintsLibrary) type.getConstructor().newInstance();
        assertNotNull(another.signBls(MESSAGE, another.newBlsPrivateKey()));
    }

    @Test
    void constructionCrsCreationAndSigningDoNotInitializePreparation() {
        assertNotNull(subject.signBls(MESSAGE, subject.newBlsPrivateKey()));
        verifyNoInteractions(keyLibrarySupplier, preprocessingLibrarySupplier, keyLibrary, preprocessingLibrary);
    }

    @Test
    void computingHintsDoesNotInitializePreprocessing() {
        when(keyLibrarySupplier.get()).thenReturn(keyLibrary);
        when(keyLibrary.computeHints(any(), any(), eq(0), eq(CAPACITY))).thenReturn(HINT.toByteArray());

        assertEquals(HINT, subject.computeHints(crs, PRIVATE_KEY, 0, CAPACITY));

        verifyNoInteractions(preprocessingLibrarySupplier, preprocessingLibrary);
    }

    @Test
    void validatingHintsDoesNotInitializePreprocessing() {
        when(keyLibrarySupplier.get()).thenReturn(keyLibrary);
        when(keyLibrary.validateHintsKey(any(), any(), eq(0), eq(CAPACITY))).thenReturn(true);

        assertTrue(subject.validateHintsKey(crs, HINT, 0, CAPACITY));

        verifyNoInteractions(preprocessingLibrarySupplier, preprocessingLibrary);
    }

    @Test
    void preprocessingDoesNotInitializeKeyPreparation() {
        when(preprocessingLibrarySupplier.get()).thenReturn(preprocessingLibrary);
        when(preprocessingLibrary.preprocess(any(), any(), any(), any(), eq(CAPACITY)))
                .thenReturn(KEYS);

        assertSame(KEYS, preprocess(subject));

        verifyNoInteractions(keyLibrarySupplier, keyLibrary);
    }

    @Test
    void failedPreprocessingInitializationDoesNotPreventKeyWorkOrConstructionOrSigning() {
        final var failure = new ExceptionInInitializerError("preprocessing native image unavailable");
        when(preprocessingLibrarySupplier.get()).thenThrow(failure);
        when(keyLibrarySupplier.get()).thenReturn(keyLibrary);
        when(keyLibrary.computeHints(any(), any(), eq(0), eq(CAPACITY))).thenReturn(HINT.toByteArray());

        assertSame(failure, assertThrows(ExceptionInInitializerError.class, () -> preprocess(subject)));
        assertEquals(HINT, subject.computeHints(crs, PRIVATE_KEY, 0, CAPACITY));
        final var another = new HintsLibraryImpl(keyLibrarySupplier, preprocessingLibrarySupplier);
        assertNotNull(another.signBls(MESSAGE, another.newBlsPrivateKey()));

        verifyNoInteractions(preprocessingLibrary);
    }

    @Test
    void failedKeyInitializationDoesNotPreventPreprocessingOrConstructionOrSigning() {
        final var failure = new ExceptionInInitializerError("key native image unavailable");
        when(keyLibrarySupplier.get()).thenThrow(failure);
        when(preprocessingLibrarySupplier.get()).thenReturn(preprocessingLibrary);
        when(preprocessingLibrary.preprocess(any(), any(), any(), any(), eq(CAPACITY)))
                .thenReturn(KEYS);

        assertSame(
                failure,
                assertThrows(
                        ExceptionInInitializerError.class, () -> subject.computeHints(crs, PRIVATE_KEY, 0, CAPACITY)));
        assertSame(KEYS, preprocess(subject));
        final var another = new HintsLibraryImpl(keyLibrarySupplier, preprocessingLibrarySupplier);
        assertNotNull(another.signBls(MESSAGE, another.newBlsPrivateKey()));

        verifyNoInteractions(keyLibrary);
    }

    private static ClassLoader withoutPreparationHolders() {
        final var implementationName = HintsLibraryImpl.class.getName();
        return new ClassLoader(HintsLibraryImpl.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(implementationName + "$")) {
                    throw new AssertionError("Preparation holder loaded: " + name);
                }
                if (!name.equals(implementationName) && !name.equals(IsolatedHintsLibrary.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                // Keep the package-private preparation type in the same runtime package as the fresh
                // implementation, without constructing it or loading another native image.
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null) {
                        try (final var bytes =
                                HintsLibraryImpl.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                            if (bytes == null) {
                                throw new ClassNotFoundException(name);
                            }
                            final var bytecode = bytes.readAllBytes();
                            type = defineClass(name, bytecode, 0, bytecode.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
    }

    private AggregationAndVerificationKeys preprocess(final HintsLibraryImpl library) {
        return library.preprocess(crs, new TreeMap<>(Map.of(0, HINT)), new TreeMap<>(Map.of(0, 1L)), CAPACITY);
    }
}
