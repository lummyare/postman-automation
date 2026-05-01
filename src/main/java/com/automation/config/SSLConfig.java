package com.automation.config;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads mTLS client certificate/key material and converts it to a PKCS12 keystore usable by RestAssured.
 */
public final class SSLConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SSLConfig.class);

    private static final String DEFAULT_CERTIFICATE_RESOURCE = "certificates/secure-int-stg-stg2-mtls.pem";
    private static final String DEFAULT_PRIVATE_KEY_RESOURCE = "certificates/secure-int-mtls .key";

    private static final String CERTIFICATE_PATH_PROPERTY = "automation.mtls.certificate.path";
    private static final String PRIVATE_KEY_PATH_PROPERTY = "automation.mtls.privatekey.path";
    private static final String MTLS_ENABLED_PROPERTY = "automation.mtls.enabled";
    private static final String TLS_PROTOCOL_PROPERTY = "automation.tls.protocol";
    private static final String TLS_CIPHER_SUITES_PROPERTY = "automation.tls.cipher.suites";
    private static final String TLS_CLIENT_PROTOCOLS_PROPERTY = "automation.tls.client.protocols";

    private static volatile Optional<MtlsMaterial> cachedMaterial;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private SSLConfig() {
        // Utility class
    }

    /**
     * Returns mTLS material when enabled and available. On any load/parsing error, logs and returns empty.
     */
    public static Optional<MtlsMaterial> getMtlsMaterial() {
        if (!isMtlsEnabled()) {
            LOGGER.info("mTLS is disabled via system property '{}=false'.", MTLS_ENABLED_PROPERTY);
            return Optional.empty();
        }

        Optional<MtlsMaterial> local = cachedMaterial;
        if (local != null) {
            return local;
        }

        synchronized (SSLConfig.class) {
            if (cachedMaterial == null) {
                cachedMaterial = loadMtlsMaterial();
            }
            return cachedMaterial;
        }
    }

    private static boolean isMtlsEnabled() {
        return Boolean.parseBoolean(System.getProperty(MTLS_ENABLED_PROPERTY, "true"));
    }

    private static Optional<MtlsMaterial> loadMtlsMaterial() {
        String certificatePath = System.getProperty(CERTIFICATE_PATH_PROPERTY, DEFAULT_CERTIFICATE_RESOURCE).trim();
        String privateKeyPath = System.getProperty(PRIVATE_KEY_PATH_PROPERTY, DEFAULT_PRIVATE_KEY_RESOURCE).trim();

        try {
            List<X509Certificate> certificateChain = normalizeAndValidateCertificateChain(loadCertificates(certificatePath));
            PrivateKey privateKey = loadPrivateKey(privateKeyPath);

            if (certificateChain.isEmpty()) {
                throw new IllegalStateException("No certificates found at " + certificatePath);
            }

            validatePrivateKeyMatchesCertificate(privateKey, certificateChain.get(0));

            if (certificateChain.size() == 1) {
                LOGGER.warn("Only one certificate was found in '{}'. If the server requires intermediates, include full chain (leaf -> intermediate(s) -> root).", certificatePath);
            }

            String keystorePassword = UUID.randomUUID().toString();
            KeyStore keyStore = buildKeyStore(privateKey, certificateChain, keystorePassword.toCharArray());
            Path keyStoreFile = persistAsTempPkcs12(keyStore, keystorePassword.toCharArray());
            SSLContext sslContext = createSslContext(keyStore, keystorePassword.toCharArray());

            LOGGER.info("Loaded mTLS certificate '{}' and private key '{}' successfully. Client cert subject: {}",
                    certificatePath,
                    privateKeyPath,
                    certificateChain.get(0).getSubjectX500Principal().getName());

            return Optional.of(new MtlsMaterial(keyStoreFile, keystorePassword, sslContext, keyStore));
        } catch (Exception exception) {
            LOGGER.error("Unable to initialize mTLS material (certificate='{}', key='{}'). Requests will continue without client cert.",
                    certificatePath,
                    privateKeyPath,
                    exception);
            return Optional.empty();
        }
    }

    private static List<X509Certificate> loadCertificates(String certificatePath) throws Exception {
        try (InputStream inputStream = openResource(certificatePath);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream)) {

            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(bufferedInputStream);

            List<X509Certificate> chain = new ArrayList<>();
            for (Certificate certificate : certificates) {
                chain.add((X509Certificate) certificate);
            }
            return chain;
        }
    }

    private static List<X509Certificate> normalizeAndValidateCertificateChain(List<X509Certificate> certificates) {
        if (certificates.isEmpty()) {
            return certificates;
        }

        // Expected order for TLS client chain is: leaf -> intermediate(s) -> root.
        X509Certificate leaf = findLikelyLeafCertificate(certificates);

        List<X509Certificate> ordered = new ArrayList<>();
        Set<X509Certificate> visited = new HashSet<>();

        ordered.add(leaf);
        visited.add(leaf);

        X509Certificate current = leaf;
        while (true) {
            X509Certificate issuer = findIssuerCertificate(current, certificates, visited);
            if (issuer == null) {
                break;
            }
            ordered.add(issuer);
            visited.add(issuer);
            current = issuer;
        }

        if (ordered.size() != certificates.size()) {
            for (X509Certificate certificate : certificates) {
                if (!visited.contains(certificate)) {
                    ordered.add(certificate);
                }
            }
            LOGGER.warn("Certificate chain could not be fully linked by issuer/subject. Appended remaining certificates as-is. Verify PEM order and completeness.");
        }

        logCertificateChain(ordered);
        validateChainAdjacency(ordered);

        return ordered;
    }

    private static X509Certificate findLikelyLeafCertificate(List<X509Certificate> certificates) {
        for (X509Certificate candidate : certificates) {
            boolean candidateIsIssuer = false;
            for (X509Certificate other : certificates) {
                if (candidate == other) {
                    continue;
                }
                if (other.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    candidateIsIssuer = true;
                    break;
                }
            }
            if (!candidateIsIssuer) {
                return candidate;
            }
        }

        // Fallback: preserve existing behavior if we cannot infer a better leaf.
        return certificates.get(0);
    }

    private static X509Certificate findIssuerCertificate(X509Certificate current,
                                                          List<X509Certificate> certificates,
                                                          Set<X509Certificate> visited) {
        for (X509Certificate candidate : certificates) {
            if (visited.contains(candidate) || candidate == current) {
                continue;
            }
            if (current.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                return candidate;
            }
        }
        return null;
    }

    private static void logCertificateChain(List<X509Certificate> chain) {
        for (int index = 0; index < chain.size(); index++) {
            X509Certificate cert = chain.get(index);
            LOGGER.info("mTLS certificate chain[{}]: subject='{}', issuer='{}'",
                    index,
                    cert.getSubjectX500Principal().getName(),
                    cert.getIssuerX500Principal().getName());
        }
    }

    private static void validateChainAdjacency(List<X509Certificate> chain) {
        if (chain.size() < 2) {
            return;
        }

        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate current = chain.get(i);
            X509Certificate next = chain.get(i + 1);
            if (!current.getIssuerX500Principal().equals(next.getSubjectX500Principal())) {
                LOGGER.warn("Certificate chain gap detected between entries {} and {}. issuer='{}' next-subject='{}'.",
                        i,
                        i + 1,
                        current.getIssuerX500Principal().getName(),
                        next.getSubjectX500Principal().getName());
            }
        }
    }

    private static void validatePrivateKeyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate) throws Exception {
        String algorithm = resolveSignatureAlgorithm(privateKey);
        byte[] sample = "mtls-key-match-check".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(privateKey);
        signer.update(sample);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(sample);

        if (!verifier.verify(signature)) {
            throw new IllegalStateException("Private key does not match the leaf certificate public key.");
        }

        LOGGER.info("Validated private key matches certificate using signature algorithm {}.", algorithm);
    }

    private static String resolveSignatureAlgorithm(PrivateKey privateKey) {
        String keyAlgorithm = privateKey.getAlgorithm();
        if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withRSA";
        }
        if ("EC".equalsIgnoreCase(keyAlgorithm) || "ECDSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withECDSA";
        }
        if ("DSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withDSA";
        }

        throw new IllegalStateException("Unsupported private key algorithm for key/cert verification: " + keyAlgorithm);
    }

    private static PrivateKey loadPrivateKey(String privateKeyPath) throws Exception {
        try (InputStream inputStream = openResource(privateKeyPath);
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             PEMParser parser = new PEMParser(reader)) {

            Object object = parser.readObject();
            if (object == null) {
                throw new IllegalStateException("Private key content is empty at " + privateKeyPath);
            }

            JcaPEMKeyConverter keyConverter = new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (object instanceof PEMKeyPair keyPair) {
                return keyConverter.getKeyPair(keyPair).getPrivate();
            }
            if (object instanceof PrivateKeyInfo privateKeyInfo) {
                return keyConverter.getPrivateKey(privateKeyInfo);
            }
            if (object instanceof PEMEncryptedKeyPair) {
                throw new IllegalStateException("Encrypted private keys are not supported. Provide an unencrypted key file.");
            }

            throw new IllegalStateException("Unsupported private key format: " + object.getClass().getSimpleName());
        }
    }

    private static KeyStore buildKeyStore(PrivateKey privateKey,
                                          List<X509Certificate> certificateChain,
                                          char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("mtls-client", privateKey, password, certificateChain.toArray(new Certificate[0]));
        return keyStore;
    }

    private static Path persistAsTempPkcs12(KeyStore keyStore, char[] password) throws Exception {
        Path tempFile = Files.createTempFile("mtls-client-", ".p12");
        tempFile.toFile().deleteOnExit();

        try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
            keyStore.store(outputStream, password);
        }

        return tempFile;
    }

    private static SSLContext createSslContext(KeyStore keyStore, char[] password) throws Exception {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);

        String preferredProtocol = System.getProperty(TLS_PROTOCOL_PROPERTY, "TLSv1.3");
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance(preferredProtocol);
        } catch (Exception ex) {
            LOGGER.warn("Unable to initialize SSLContext with protocol '{}'. Falling back to 'TLS'.", preferredProtocol, ex);
            sslContext = SSLContext.getInstance("TLS");
        }

        KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
        sslContext.init(keyManagers, trustManagerFactory.getTrustManagers(), new SecureRandom());

        logKeyManagers(keyManagers);
        logTlsCompatibility(sslContext);
        LOGGER.info("Initialized SSLContext with protocol '{}' for mTLS requests.", sslContext.getProtocol());
        return sslContext;
    }

    private static void logKeyManagers(KeyManager[] keyManagers) {
        String managerNames = Arrays.stream(keyManagers)
                .map(manager -> manager == null ? "null" : manager.getClass().getName())
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");

        boolean hasDummyManager = Arrays.stream(keyManagers)
                .filter(manager -> manager != null)
                .anyMatch(manager -> manager.getClass().getName().contains("DummyX509KeyManager"));

        LOGGER.info("SSLConfig KeyManager(s): [{}]", managerNames);
        if (hasDummyManager) {
            LOGGER.error("SSLConfig initialized with DummyX509KeyManager. Client certificate may not be presented.");
        } else {
            LOGGER.info("SSLConfig KeyManager initialization verified: no DummyX509KeyManager detected.");
        }
    }

    private static void logTlsCompatibility(SSLContext sslContext) {
        String configuredProtocols = System.getProperty(TLS_CLIENT_PROTOCOLS_PROPERTY, "TLSv1.3");
        String configuredCipherSuites = System.getProperty(TLS_CIPHER_SUITES_PROPERTY, "TLS_AES_128_GCM_SHA256");

        Set<String> supportedProtocols = new HashSet<>(Arrays.asList(sslContext.getSupportedSSLParameters().getProtocols()));
        Set<String> supportedCipherSuites = new HashSet<>(Arrays.asList(sslContext.getSupportedSSLParameters().getCipherSuites()));

        for (String protocol : splitCsv(configuredProtocols)) {
            if (supportedProtocols.contains(protocol)) {
                LOGGER.info("Configured TLS protocol '{}' is supported by current JVM/provider.", protocol);
            } else {
                LOGGER.warn("Configured TLS protocol '{}' is NOT supported by current JVM/provider.", protocol);
            }
        }

        for (String cipherSuite : splitCsv(configuredCipherSuites)) {
            if (supportedCipherSuites.contains(cipherSuite)) {
                LOGGER.info("Configured cipher suite '{}' is supported by current JVM/provider.", cipherSuite);
            } else {
                LOGGER.warn("Configured cipher suite '{}' is NOT supported by current JVM/provider.", cipherSuite);
            }
        }
    }

    private static List<String> splitCsv(String value) {
        List<String> values = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return values;
        }

        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }

        return values;
    }

    private static InputStream openResource(String resourcePath) throws IOException {
        Path fileSystemPath = Paths.get(resourcePath);
        if (fileSystemPath.isAbsolute() && Files.exists(fileSystemPath)) {
            return Files.newInputStream(fileSystemPath);
        }

        if (Files.exists(fileSystemPath)) {
            return Files.newInputStream(fileSystemPath);
        }

        InputStream classpathResource = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (classpathResource != null) {
            return classpathResource;
        }

        throw new IOException("Could not locate certificate resource: " + resourcePath);
    }

    /**
     * Wrapper for mTLS assets that ApiClient applies to RestAssured.
     */
    public static final class MtlsMaterial {
        private final Path keyStorePath;
        private final String keyStorePassword;
        private final SSLContext sslContext;
        private final KeyStore keyStore;

        private MtlsMaterial(Path keyStorePath, String keyStorePassword, SSLContext sslContext, KeyStore keyStore) {
            this.keyStorePath = keyStorePath;
            this.keyStorePassword = keyStorePassword;
            this.sslContext = sslContext;
            this.keyStore = keyStore;
        }

        public Path getKeyStorePath() {
            return keyStorePath;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public SSLContext getSslContext() {
            return sslContext;
        }

        public KeyStore getKeyStore() {
            return keyStore;
        }
    }
}
