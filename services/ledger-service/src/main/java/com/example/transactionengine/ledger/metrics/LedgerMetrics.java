package com.example.transactionengine.ledger.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class LedgerMetrics {

  private final Counter committed;
  private final Counter rejected;
  private final Counter duplicate;
  private final Counter alreadyFinal;
  private final Timer lockWait;
  private final AtomicLong outboxBacklog = new AtomicLong(0);

  public LedgerMetrics(MeterRegistry registry) {
    this.committed = Counter.builder("ledger_committed_total").description("Ledger committed").register(registry);
    this.rejected = Counter.builder("ledger_rejected_total").description("Ledger rejected").register(registry);
    this.duplicate = Counter.builder("ledger_duplicate_events_total").description("Duplicate events").register(registry);
    this.alreadyFinal = Counter.builder("ledger_already_final_total").register(registry);
    this.lockWait = Timer.builder("ledger_lock_wait_seconds").description("Lock wait").register(registry);
    Gauge.builder("outbox_pending_events", outboxBacklog, AtomicLong::get).description("Outbox backlog").register(registry);
  }

  public void incrementCommitted() { committed.increment(); }
  public void incrementRejected() { rejected.increment(); }
  public void incrementDuplicate() { duplicate.increment(); }
  public void incrementAlreadyFinal() { alreadyFinal.increment(); }
  public Timer.Sample startLockWait() { return Timer.start(); }
  public void stopLockWait(Timer.Sample sample) { sample.stop(lockWait); }
  public void setOutboxBacklog(long n) { outboxBacklog.set(n); }
}
