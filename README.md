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

## Notes

- Uploaded files are already copied under `src/test/resources/collections` and `src/test/resources/environments`.
- PDF reference is under `src/test/resources/docs`.
- Some scenarios depend on live credentials/tokens from environment values; update those for stable execution in each environment.
