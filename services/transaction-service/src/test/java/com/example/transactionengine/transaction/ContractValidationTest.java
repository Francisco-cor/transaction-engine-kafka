package com.example.transactionengine.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractValidationTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void transactionCreatedV1SchemaValidatesCorrectAndRejectsInvalid() throws Exception {
    JsonSchema schema = loadSchema("../../docs/contracts/transaction-created.v1.json", "docs/contracts/transaction-created.v1.json");

    JsonNode valid = mapper.valueToTree(validTransactionCreated());
    Set<ValidationMessage> validErrors = schema.validate(valid);
    assertThat(validErrors).isEmpty();

    // Missing required field amount should fail
    JsonNode invalid = mapper.readTree("""
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "TransactionCreated",
          "schemaVersion": 1,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "currency": "MXN",
          "type": "DEBIT",
          "metadata": {}
        }
        """);
    Set<ValidationMessage> invalidErrors = schema.validate(invalid);
    assertThat(invalidErrors).isNotEmpty();

    // Wrong enum should fail
    JsonNode wrongType = mapper.readTree("""
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "TransactionCreated",
          "schemaVersion": 1,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "amount": 10.00,
          "currency": "MXN",
          "type": "UNKNOWN",
          "metadata": {}
        }
        """);
    assertThat(schema.validate(wrongType)).isNotEmpty();
  }

  @Test
  void transactionCommittedV1SchemaValidates() throws Exception {
    JsonSchema schema = loadSchema("../../docs/contracts/transaction-committed.v1.json", "docs/contracts/transaction-committed.v1.json");
    JsonNode valid = mapper.readTree("""
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "TransactionCommitted",
          "schemaVersion": 1,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "amount": 10.00,
          "currency": "MXN",
          "type": "DEBIT",
          "balanceBefore": 100.00,
          "balanceAfter": 90.00,
          "metadata": {}
        }
        """);
    assertThat(schema.validate(valid)).isEmpty();
  }

  @Test
  void fraudDecisionV1SchemaValidates() throws Exception {
    JsonSchema schema = loadSchema("../../docs/contracts/fraud-decision.v1.json", "docs/contracts/fraud-decision.v1.json");
    JsonNode valid = mapper.readTree("""
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "FraudDecision",
          "schemaVersion": 1,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "amount": 5000.00,
          "currency": "MXN",
          "decision": "REVIEW",
          "reasonCode": "AMOUNT_THRESHOLD",
          "ruleCode": "AMOUNT_THRESHOLD",
          "riskScore": 75,
          "metadata": {}
        }
        """);
    assertThat(schema.validate(valid)).isEmpty();
  }

  @Test
  void openApiSpecIsValid() {
    Path spec = resolveSpec();
    assertThat(Files.exists(spec)).as("OpenAPI spec exists at %s", spec).isTrue();

    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setValidateExternalRefs(true);
    var result = new OpenAPIParser().readLocation(spec.toAbsolutePath().toString(), null, options);
    assertThat(result.getMessages()).as("OpenAPI validation messages").isEmpty();
    assertThat(result.getOpenAPI()).isNotNull();
    assertThat(result.getOpenAPI().getPaths()).containsKeys("/transactions", "/transactions/{transactionId}");
    assertThat(result.getOpenAPI().getPaths().get("/transactions").getPost()).isNotNull();
    assertThat(result.getOpenAPI().getPaths().get("/transactions").getPost().getParameters())
        .anyMatch(p -> "Idempotency-Key".equals(p.getName()) && Boolean.TRUE.equals(p.getRequired()));
  }

  private JsonSchema loadSchema(String... candidates) throws Exception {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    for (String candidate : candidates) {
      Path path = Path.of(candidate);
      if (!path.isAbsolute()) {
        // Try relative to project root
        Path working = Path.of(System.getProperty("user.dir"));
        Path resolved = working.resolve(candidate).normalize();
        if (Files.exists(resolved)) {
          path = resolved;
        } else {
          // Try transaction-service module root
          resolved = working.resolve("services/transaction-service").resolve(candidate).normalize();
          if (Files.exists(resolved)) {
            path = resolved;
          } else {
            // Try from parent of working dir (when Surefire forks)
            resolved = working.getParent() != null ? working.getParent().resolve(candidate).normalize() : null;
            if (resolved != null && Files.exists(resolved)) {
              path = resolved;
            }
          }
        }
      }
      if (Files.exists(path)) {
        try (InputStream is = Files.newInputStream(path)) {
          return factory.getSchema(is);
        }
      }
      // Fallback to classpath
      String resource = candidate.substring(candidate.lastIndexOf('/') + 1);
      try (InputStream is = getClass().getClassLoader().getResourceAsStream("contracts/" + resource)) {
        if (is != null) {
          return factory.getSchema(is);
        }
      }
    }
    throw new IllegalStateException("Schema not found, tried: " + String.join(", ", candidates));
  }

  private Path resolveSpec() {
    String[] candidates = {
      "docs/contracts/openapi-transaction-service.yaml",
      "../../docs/contracts/openapi-transaction-service.yaml",
      "services/transaction-service/docs/contracts/openapi-transaction-service.yaml"
    };
    for (String candidate : candidates) {
      Path p = Path.of(candidate);
      if (Files.exists(p)) return p;
      Path working = Path.of(System.getProperty("user.dir"));
      Path r = working.resolve(candidate).normalize();
      if (Files.exists(r)) return r;
      if (working.getParent() != null) {
        Path r2 = working.getParent().resolve(candidate).normalize();
        if (Files.exists(r2)) return r2;
      }
    }
    // Last resort: locate via classloader relative path search
    Path working = Path.of(System.getProperty("user.dir"));
    // Walk up 2 levels
    for (int i = 0; i < 3; i++) {
      Path tryPath = working.resolve("docs/contracts/openapi-transaction-service.yaml").normalize();
      if (Files.exists(tryPath)) return tryPath;
      working = working.getParent();
      if (working == null) break;
    }
    return Path.of("docs/contracts/openapi-transaction-service.yaml");
  }

  private Map<String, Object> validTransactionCreated() {
    return Map.of(
        "eventId", UUID.randomUUID().toString(),
        "eventType", "TransactionCreated",
        "schemaVersion", 1,
        "occurredAt", Instant.parse("2026-08-20T17:00:00Z").toString(),
        "transactionId", UUID.randomUUID().toString(),
        "accountId", "demo-acc-001",
        "amount", new BigDecimal("10.00"),
        "currency", "MXN",
        "type", "DEBIT",
        "metadata", Map.of());
  }
}
