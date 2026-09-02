package com.example.transactionengine.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

/**
 * F1 Avro wire compat — validates BACKWARD between V1 and V2 avsc.
 * V2 adds customerNote ["null","string"] default null → BACKWARD should pass.
 */
class AvroWireCompatibilityTest {

  @Test
  void v2IsBackwardCompatibleWithV1() throws Exception {
    File v1File = new File("src/main/avro/TransactionCreatedV1.avsc");
    File v2File = new File("src/main/avro/TransactionCreatedV2.avsc");
    // Also try libs path when running from root
    if (!v1File.exists()) v1File = new File("libs/event-contracts/src/main/avro/TransactionCreatedV1.avsc");
    if (!v2File.exists()) v2File = new File("libs/event-contracts/src/main/avro/TransactionCreatedV2.avsc");
    assertThat(v1File).exists();
    assertThat(v2File).exists();

    Schema v1 = new Schema.Parser().parse(v1File);
    Schema v2 = new Schema.Parser().parse(v2File);

    // BACKWARD: new schema can read old data → check reader=v2 writer=v1
    var backward = SchemaCompatibility.checkReaderWriterCompatibility(v2, v1);
    assertThat(backward.getResult().getCompatibility())
        .as("V2 reader should be backward compatible with V1 writer, got %s", backward)
        .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);

    // Also ensure V2 has customerNote with default null
    var field = v2.getField("customerNote");
    assertThat(field).isNotNull();
    assertThat(field.defaultVal()).isNull();
    assertThat(field.schema().getType()).isEqualTo(Schema.Type.UNION);
  }

  @Test
  void v1FieldsPresentInV2() throws Exception {
    File v1File = new File("src/main/avro/TransactionCreatedV1.avsc");
    File v2File = new File("src/main/avro/TransactionCreatedV2.avsc");
    if (!v1File.exists()) v1File = new File("libs/event-contracts/src/main/avro/TransactionCreatedV1.avsc");
    if (!v2File.exists()) v2File = new File("libs/event-contracts/src/main/avro/TransactionCreatedV2.avsc");
    Schema v1 = new Schema.Parser().parse(v1File);
    Schema v2 = new Schema.Parser().parse(v2File);
    // All V1 fields must exist in V2
    for (var f : v1.getFields()) {
      assertThat(v2.getField(f.name())).as("V2 missing field %s", f.name()).isNotNull();
    }
  }
}
