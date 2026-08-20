package com.example.transactionengine.fraud.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PayloadHash {

  private PayloadHash() {}

  public static String sha256(String payload) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      var result = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
