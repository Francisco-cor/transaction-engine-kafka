package com.example.transactionengine.transaction.application;

import com.example.transactionengine.transaction.api.CreateTransactionRequest;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestHash {

  private RequestHash() {}

  public static String sha256(CreateTransactionRequest request) {
    var amount = request.amount().setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    var canonical = String.join("|", request.accountId(), amount, request.type().name(), request.currency());
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the runtime", exception);
    }
  }
}