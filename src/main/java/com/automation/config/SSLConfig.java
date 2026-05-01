package com.automation.config;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
            List<X509Certificate> certificateChain = loadCertificates(certificatePath);
            PrivateKey privateKey = loadPrivateKey(privateKeyPath);

            if (certificateChain.isEmpty()) {
                throw new IllegalStateException("No certificates found at " + certificatePath);
            }

            String keystorePassword = UUID.randomUUID().toString();
            KeyStore keyStore = buildKeyStore(privateKey, certificateChain, keystorePassword.toCharArray());
            Path keyStoreFile = persistAsTempPkcs12(keyStore, keystorePassword.toCharArray());
            SSLContext sslContext = createSslContext(keyStore, keystorePassword.toCharArray());

            LOGGER.info("Loaded mTLS certificate '{}' and private key '{}' successfully. Client cert subject: {}",
                    certificatePath,
                    privateKeyPath,
                    certificateChain.get(0).getSubjectX500Principal().getName());

            return Optional.of(new MtlsMaterial(keyStoreFile, keystorePassword, sslContext));
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

        String preferredProtocol = System.getProperty("automation.tls.protocol", "TLSv1.3");
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance(preferredProtocol);
        } catch (Exception ex) {
            LOGGER.warn("Unable to initialize SSLContext with protocol '{}'. Falling back to 'TLS'.", preferredProtocol, ex);
            sslContext = SSLContext.getInstance("TLS");
        }

        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());
        LOGGER.info("Initialized SSLContext with protocol '{}' for mTLS requests.", sslContext.getProtocol());
        return sslContext;
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

        private MtlsMaterial(Path keyStorePath, String keyStorePassword, SSLContext sslContext) {
            this.keyStorePath = keyStorePath;
            this.keyStorePassword = keyStorePassword;
            this.sslContext = sslContext;
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
    }
}
