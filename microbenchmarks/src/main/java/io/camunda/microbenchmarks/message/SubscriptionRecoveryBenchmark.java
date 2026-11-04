/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.microbenchmarks.message;

import static io.camunda.zeebe.engine.state.immutable.PendingSubscriptionRecovery.NO_ELEMENT_INSTANCE;
import static io.camunda.zeebe.util.buffer.BufferUtil.wrapString;

import io.camunda.zeebe.db.AccessMetricsConfiguration;
import io.camunda.zeebe.db.AccessMetricsConfiguration.Kind;
import io.camunda.zeebe.db.ConsistencyChecksSettings;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.db.impl.rocksdb.RocksDbConfiguration;
import io.camunda.zeebe.db.impl.rocksdb.ZeebeRocksDbFactory;
import io.camunda.zeebe.engine.processing.message.PendingSubscriptionRecoveryScheduler;
import io.camunda.zeebe.engine.state.message.DbMessageSubscriptionState;
import io.camunda.zeebe.engine.state.message.DbProcessMessageSubscriptionState;
import io.camunda.zeebe.engine.state.message.TransientPendingSubscriptionState;
import io.camunda.zeebe.protocol.Protocol;
import io.camunda.zeebe.protocol.ZbColumnFamilies;
import io.camunda.zeebe.protocol.impl.record.value.message.MessageSubscriptionRecord;
import io.camunda.zeebe.protocol.impl.record.value.message.ProcessMessageSubscriptionRecord;
import io.camunda.zeebe.stream.api.ReadonlyStreamProcessorContext;
import io.camunda.zeebe.stream.api.scheduling.ProcessingScheduleService;
import io.camunda.zeebe.stream.api.scheduling.Task;
import io.camunda.zeebe.stream.api.scheduling.TaskResultBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Compares how long processing waits after a leader change while the transient pending (process)
 * message subscriptions are rebuilt. See camunda/camunda#64467.
 *
 * <p>The stream processor actor and the async actor are emulated by two single-threaded executors.
 * Each invocation starts the recovery like a new leader would, then queues a "first command" probe
 * on the processing thread; the score is the time until that probe runs.
 *
 * <ul>
 *   <li>{@code before}: one pass over both column families on the processing thread, as the former
 *       {@code onRecovered} did
 *   <li>{@code chunked}: the same scan in chunks of {@code batchLimit}, still on the processing
 *       thread
 *   <li>{@code chunkedAsync}: the real {@link PendingSubscriptionRecoveryScheduler}, whose chunks
 *       run on the async thread
 * </ul>
 *
 * <p>After each iteration it prints the total scan time and the longest delay of a 10ms probe on
 * the async thread, i.e. what timers and TTL checks on the async actor would notice.
 *
 * <p>The state is generated once per subscription count under {@code
 * java.io.tmpdir/subscription-recovery-benchmark-<count>} and reused; delete these folders when
 * done. The DB is reopened before every iteration, so RocksDB's caches are cold; the OS page cache
 * stays warm.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms4G", "-Xmx4G", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"})
@State(Scope.Benchmark)
public class SubscriptionRecoveryBenchmark {

  private static final int PARTITION_ID = 1;
  private static final int SUBSCRIPTIONS_PER_ELEMENT_INSTANCE = 12;
  private static final int PENDING_EVERY_NTH = 10_000;
  private static final int BATCH_SIZE = 10_000;
  private static final long STALL_PROBE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  @Param({"1000000", "5000000", "10000000"})
  private int subscriptionCount;

  @Param({"before", "chunked", "chunkedAsync"})
  private String variant;

  // subscriptions per chunk (default of the engine); only used by the chunked variants
  @Param({"10000"})
  private int batchLimit;

  private Path dbDirectory;
  private ZeebeDb<ZbColumnFamilies> zeebeDb;
  private ExecutorService processingThread;
  private ScheduledExecutorService asyncThread;
  private CountDownLatch scanDone;
  private long recoveryStartedAt;
  private final AtomicLong scanFinishedAt = new AtomicLong();
  private final AtomicInteger outstandingRecoveryTasks = new AtomicInteger();
  private final AtomicLong maxAsyncStallNanos = new AtomicLong();
  private volatile boolean stallProbeRunning;

  public static void main(final String[] args) throws RunnerException {
    new Runner(
            new OptionsBuilder()
                .include(SubscriptionRecoveryBenchmark.class.getSimpleName())
                .build())
        .run();
  }

  @Setup(Level.Trial)
  public void setupState() throws Exception {
    dbDirectory =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "subscription-recovery-benchmark-" + subscriptionCount);
    final var marker = dbDirectory.resolve(".complete");
    if (!Files.exists(marker)) {
      Files.createDirectories(dbDirectory);
      zeebeDb = openDb();
      populate();
      zeebeDb.close();
      Files.createFile(marker);
    }
  }

  @Setup(Level.Iteration)
  public void setupIteration() {
    zeebeDb = openDb();
    processingThread = Executors.newSingleThreadExecutor();
    asyncThread = Executors.newSingleThreadScheduledExecutor();
    scanDone = new CountDownLatch(1);
    scanFinishedAt.set(0);
    maxAsyncStallNanos.set(0);
    stallProbeRunning = true;
    scheduleStallProbe(System.nanoTime() + STALL_PROBE_INTERVAL_NANOS);
  }

  @TearDown(Level.Iteration)
  public void tearDownIteration() throws Exception {
    scanDone.await();
    stallProbeRunning = false;
    System.out.printf(
        "%n[%s/%d] total scan %d ms, longest async stall %.1f ms%n",
        variant,
        batchLimit,
        TimeUnit.NANOSECONDS.toMillis(scanFinishedAt.get() - recoveryStartedAt),
        maxAsyncStallNanos.get() / 1_000_000.0);
    processingThread.shutdown();
    asyncThread.shutdown();
    processingThread.awaitTermination(1, TimeUnit.MINUTES);
    asyncThread.awaitTermination(1, TimeUnit.MINUTES);
    zeebeDb.close();
  }

  /** Time from the start of the recovery until the first command could be processed. */
  @Benchmark
  public long firstCommandLatency() throws Exception {
    final var transientState = new TransientPendingSubscriptionState();
    final var context = zeebeDb.createContext();
    final var processState =
        new DbProcessMessageSubscriptionState(
            zeebeDb, context, transientState, InstantSource.system());
    final var messageState =
        new DbMessageSubscriptionState(zeebeDb, context, transientState, InstantSource.system());

    recoveryStartedAt = System.nanoTime();
    switch (variant) {
      case "before" ->
          processingThread.execute(
              () -> {
                processState.recoverPending(NO_ELEMENT_INSTANCE, Integer.MAX_VALUE);
                messageState.recoverPending(NO_ELEMENT_INSTANCE, Integer.MAX_VALUE);
                markScanDone();
              });
      case "chunked" ->
          processingThread.execute(
              () -> {
                long next = NO_ELEMENT_INSTANCE;
                do {
                  next = processState.recoverPending(next, batchLimit);
                } while (next != NO_ELEMENT_INSTANCE);
                do {
                  next = messageState.recoverPending(next, batchLimit);
                } while (next != NO_ELEMENT_INSTANCE);
                markScanDone();
              });
      case "chunkedAsync" -> {
        final var scheduler =
            new PendingSubscriptionRecoveryScheduler(processState, messageState, batchLimit);
        // holds the count above zero until both recovery tasks are scheduled
        outstandingRecoveryTasks.set(1);
        processingThread.execute(
            () -> {
              scheduler.onRecovered(asyncContext());
              if (outstandingRecoveryTasks.decrementAndGet() == 0) {
                markScanDone();
              }
            });
      }
      default -> throw new IllegalStateException("Unknown variant " + variant);
    }

    final long firstCommandAt = processingThread.submit(System::nanoTime).get();
    return firstCommandAt - recoveryStartedAt;
  }

  private void markScanDone() {
    scanFinishedAt.set(System.nanoTime());
    scanDone.countDown();
  }

  private void scheduleStallProbe(final long expectedAt) {
    asyncThread.schedule(
        () -> {
          final long now = System.nanoTime();
          maxAsyncStallNanos.accumulateAndGet(now - expectedAt, Math::max);
          if (stallProbeRunning) {
            scheduleStallProbe(now + STALL_PROBE_INTERVAL_NANOS);
          }
        },
        Math.max(0, expectedAt - System.nanoTime()),
        TimeUnit.NANOSECONDS);
  }

  /**
   * A stream processor context whose schedule service runs async tasks on the async thread, and
   * marks the scan done once no recovery task is queued or running any more.
   */
  private ReadonlyStreamProcessorContext asyncContext() {
    final var taskResultBuilder =
        (TaskResultBuilder)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TaskResultBuilder.class},
                (proxy, method, args) -> null);
    final var scheduleService =
        (ProcessingScheduleService)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ProcessingScheduleService.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("runDelayedAsync")) {
                    throw new UnsupportedOperationException(method.getName());
                  }
                  final var task = (Task) args[1];
                  outstandingRecoveryTasks.incrementAndGet();
                  asyncThread.execute(
                      () -> {
                        task.execute(taskResultBuilder);
                        if (outstandingRecoveryTasks.decrementAndGet() == 0) {
                          markScanDone();
                        }
                      });
                  return null;
                });
    return (ReadonlyStreamProcessorContext)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {ReadonlyStreamProcessorContext.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getScheduleService")) {
                return scheduleService;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private ZeebeDb<ZbColumnFamilies> openDb() {
    return new ZeebeRocksDbFactory<ZbColumnFamilies>(
            new RocksDbConfiguration(),
            new ConsistencyChecksSettings(),
            new AccessMetricsConfiguration(Kind.NONE),
            SimpleMeterRegistry::new)
        .createDb(dbDirectory.toFile());
  }

  /**
   * Mirrors the customer's model: every waiting element instance holds 12 subscriptions (event
   * gateway and event subprocesses), almost all of them settled; only a few are mid-handshake.
   */
  private void populate() {
    final var context = zeebeDb.createContext();
    final var transientState = new TransientPendingSubscriptionState();
    final var processState =
        new DbProcessMessageSubscriptionState(
            zeebeDb, context, transientState, InstantSource.system());
    final var messageState =
        new DbMessageSubscriptionState(zeebeDb, context, transientState, InstantSource.system());

    for (int batchStart = 0; batchStart < subscriptionCount; batchStart += BATCH_SIZE) {
      final int from = batchStart;
      final int to = Math.min(batchStart + BATCH_SIZE, subscriptionCount);
      context.runInTransaction(
          () -> {
            for (int i = from; i < to; i++) {
              final long elementInstanceKey =
                  Protocol.encodePartitionId(PARTITION_ID, i / SUBSCRIPTIONS_PER_ELEMENT_INSTANCE);
              final long processInstanceKey = elementInstanceKey;
              final String messageName = "message-" + (i % SUBSCRIPTIONS_PER_ELEMENT_INSTANCE);
              final String correlationKey = "order-" + (i / SUBSCRIPTIONS_PER_ELEMENT_INSTANCE);
              final boolean pending = i % PENDING_EVERY_NTH == 0;
              final long subscriptionKey = Protocol.encodePartitionId(PARTITION_ID, i);

              final var processRecord =
                  new ProcessMessageSubscriptionRecord()
                      .setSubscriptionPartitionId(PARTITION_ID)
                      .setProcessInstanceKey(processInstanceKey)
                      .setElementInstanceKey(elementInstanceKey)
                      .setProcessDefinitionKey(1L)
                      .setBpmnProcessId(wrapString("order-process"))
                      .setElementId(wrapString("catch-" + (i % SUBSCRIPTIONS_PER_ELEMENT_INSTANCE)))
                      .setMessageName(wrapString(messageName))
                      .setCorrelationKey(wrapString(correlationKey))
                      .setInterrupting(true)
                      .setSubscriptionKey(subscriptionKey);
              processState.put(subscriptionKey, processRecord);
              if (pending) {
                processState.updateToOpeningState(processRecord);
              } else {
                processState.updateToOpenedState(processRecord);
              }

              final var messageRecord =
                  new MessageSubscriptionRecord()
                      .setProcessInstanceKey(processInstanceKey)
                      .setElementInstanceKey(elementInstanceKey)
                      .setProcessDefinitionKey(1L)
                      .setBpmnProcessId(wrapString("order-process"))
                      .setMessageName(wrapString(messageName))
                      .setCorrelationKey(wrapString(correlationKey))
                      .setInterrupting(true);
              messageState.put(subscriptionKey, messageRecord);
              if (pending) {
                messageState.updateToCorrelatingState(messageRecord.setMessageKey(1L));
              }
            }
          });
    }
  }
}
