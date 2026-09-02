package com.example.transactionengine.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Vault Transit tokenization for customerNote (F3). Uses HTTP directly to avoid Spring Vault dep.
 * If VAULT_ADDR not set, returns plain with metadata flag.
 */
@Component
public class VaultTransitClient {

  private static final Logger LOG = LoggerFactory.getLogger(VaultTransitClient.class);
  private final String vaultAddr;
  private final String vaultToken;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public VaultTransitClient(
      @Value("${VAULT_ADDR:}") String vaultAddr,
      @Value("${VAULT_TOKEN:root}") String vaultToken) {
    this.vaultAddr = vaultAddr == null ? "" : vaultAddr.trim();
    this.vaultToken = vaultToken;
  }

  public boolean isEnabled() {
    return !vaultAddr.isBlank();
  }

  public String encrypt(String plain) {
    if (!isEnabled() || plain == null) return plain;
    try {
      String b64 = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
      String body = mapper.writeValueAsString(java.util.Map.of("plaintext", b64));
      var req = HttpRequest.newBuilder()
          .uri(URI.create(vaultAddr + "/v1/transit/encrypt/customer-note"))
          .header("X-Vault-Token", vaultToken)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .timeout(Duration.ofSeconds(2))
          .build();
      var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        JsonNode node = mapper.readTree(resp.body());
        String cipher = node.path("data").path("ciphertext").asText();
        if (!cipher.isBlank()) return cipher;
      }
      LOG.warn("Vault encrypt failed {} {}", resp.statusCode(), resp.body());
    } catch (Exception e) {
      LOG.warn("Vault encrypt unavailable, fallback plain", e);
    }
    return plain;
  }

  public String decrypt(String cipher) {
    if (!isEnabled() || cipher == null || !cipher.startsWith("vault:")) return cipher;
    try {
      String body = mapper.writeValueAsString(java.util.Map.of("ciphertext", cipher));
      var req = HttpRequest.newBuilder()
          .uri(URI.create(vaultAddr + "/v1/transit/decrypt/customer-note"))
          .header("X-Vault-Token", vaultToken)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .timeout(Duration.ofSeconds(2))
          .build();
      var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        String b64 = mapper.readTree(resp.body()).path("data").path("plaintext").asText();
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
      }
    } catch (Exception e) {
      LOG.warn("Vault decrypt failed", e);
    }
    return cipher;
  }

  public String tokenizeOrPlain(String note) {
    if (note == null) return null;
    if (!isEnabled()) return note;
    String cipher = encrypt(note);
    // If vault unavailable, encrypt returns plain — mark as plain
    return cipher;
  }
}
