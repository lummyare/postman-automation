# postman-automation

Java + Maven framework to execute Postman collections as automated API tests in IntelliJ using **RestAssured + JUnit 5**.

## Project Layout

```text
postman-automation/
├── pom.xml
├── src/main/java/com/automation/
│   ├── client/ApiClient.java
│   ├── config/EnvironmentConfig.java
│   ├── config/TestConfig.java
│   └── utils/
│       ├── PostmanParser.java
│       └── ResponseLogger.java
├── src/test/java/com/automation/tests/GetAccountTests.java
└── src/test/resources/
    ├── collections/
    ├── environments/
    ├── docs/
    └── test.properties
```

## Import in IntelliJ IDEA

1. Open IntelliJ IDEA.
2. Click **File → Open**.
3. Select folder: `/home/ubuntu/postman-automation`.
4. IntelliJ detects `pom.xml` and imports as Maven project.
5. If prompted, use Java 17 SDK.

## Run Tests in IntelliJ (Built-in Test Viewer)

### Option A: Run entire test class
1. Open `GetAccountTests.java`.
2. Right-click inside file.
3. Click **Run 'GetAccountTests'**.

### Option B: Run single test
1. Click green run icon next to specific `@Test` method.
2. IntelliJ opens detailed test results panel (pass/fail/skipped, stack traces, console logs).

## Run from Maven CLI

```bash
cd /home/ubuntu/postman-automation
mvn clean test
```

### Run tests with SSL handshake debugging enabled

Use this when diagnosing TLS/mTLS negotiation issues:

```bash
cd /home/ubuntu/postman-automation
mvn clean test \
  -Dautomation.ssl.debug.enabled=true \
  -Dautomation.ssl.debug.value=ssl,handshake \
  -Dautomation.tls.protocol=TLSv1.3 \
  -Dautomation.tls.client.protocols=TLSv1.3 \
  -Dautomation.tls.cipher.suites=TLS_AES_128_GCM_SHA256
```

This prints detailed JVM SSL logs (ClientHello, cert exchange, key selection, alerts) so you can compare Java behavior with the successful curl handshake.

## Switch Environment (dev/staging/prod)

`TestConfig` resolves environment in this order:
1. JVM system property `-Denv=<name>`
2. `default.environment` from `src/test/resources/test.properties`

### Examples

```bash
# Uses default.environment from test.properties (staging)
mvn test

# Force dev
mvn test -Denv=dev

# Force prod
mvn test -Denv=prod
```

Update file mappings in `test.properties`:

```properties
env.staging.file=environments/TC_Agent.postman_environment.json
env.dev.file=environments/TC_Agent_dev.postman_environment.json
env.prod.file=environments/TC_Agent_prod.postman_environment.json
```

## How this framework works

- `EnvironmentConfig` parses Postman environment JSON (`values[]`) and exposes getters like:
  - `getBaseUrl()`
  - `getApiKey()`
  - `getChannel()`
  - `getAuthorizationToken()`
- `PostmanParser` reads collection/environment JSON and resolves `{{variables}}`.
- `ApiClient` creates reusable RestAssured request specification with:
  - Base URI
  - JSON content type
  - Request/response logging filters
- `ResponseLogger` pretty-prints response payloads for debugging.
- `GetAccountTests` maps collection requests to JUnit test methods (one scenario per method).

## Add more collections

1. Copy collection JSON to `src/test/resources/collections/`.
2. Create a new test class in `src/test/java/com/automation/tests/`.
3. Reuse helper components:
   - `PostmanParser.findRequestByName(...)`
   - `EnvironmentConfig`
   - `ApiClient`
   - `ResponseLogger`
4. Add one `@Test` per request/scenario.
5. Run class from IntelliJ test viewer.

## mTLS Configuration (Required)

This API environment requires **mutual TLS (mTLS)**. The framework now loads a client certificate and private key and applies them to every RestAssured request via `ApiClient`.

### Certificate files location

Store these files under:

```text
src/test/resources/certificates/
├── secure-int-stg-stg2-mtls.pem
└── secure-int-mtls .key
```

> Note: the key filename includes a space before `.key` and must match exactly unless you override the path via system properties.

### How it works

- `com.automation.config.SSLConfig` loads:
  - PEM certificate chain (`.pem`)
  - PEM private key (`.key`)
- The files are converted into an in-memory PKCS12 keystore.
- A temporary `.p12` keystore file is created and applied to RestAssured (`RequestSpecBuilder.setKeyStore(...)`).
- `ApiClient` still enables relaxed HTTPS validation for non-production certificate-chain issues.
- If mTLS files are missing/invalid, a detailed error is logged and requests continue without client certificates (for troubleshooting visibility).

### Optional system properties

```bash
# Disable mTLS (debugging only)
-Dautomation.mtls.enabled=false

# Override cert/key paths (classpath resource or filesystem path)
-Dautomation.mtls.certificate.path=certificates/your-cert.pem
-Dautomation.mtls.privatekey.path=certificates/your-key.key

# TLS protocol and cipher suite preferences (defaults mirror successful curl handshake)
-Dautomation.tls.protocol=TLSv1.3
-Dautomation.tls.client.protocols=TLSv1.3
-Dautomation.tls.cipher.suites=TLS_AES_128_GCM_SHA256

# Enable/disable JVM SSL handshake debug output
-Dautomation.ssl.debug.enabled=true
-Dautomation.ssl.debug.value=ssl,handshake
```

### Replacing certificates

1. Replace files in `src/test/resources/certificates/`.
2. Keep existing names **or** pass override system properties above.
3. Run `mvn clean test` to verify the new cert/key pair loads successfully.

## Notes

- Uploaded files are already copied under `src/test/resources/collections` and `src/test/resources/environments`.
- PDF reference is under `src/test/resources/docs`.
- Some scenarios depend on live credentials/tokens from environment values; update those for stable execution in each environment.
