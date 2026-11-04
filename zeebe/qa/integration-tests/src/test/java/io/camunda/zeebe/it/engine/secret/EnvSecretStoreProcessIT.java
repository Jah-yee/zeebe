/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.it.engine.secret;

import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.INPUT_TARGET;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.RESOLUTION_INTERVAL;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.activateOneJob;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.applyRecordWaitTime;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.awaitJobKeyOf;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.backtickedSecretReference;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.createProcessInstance;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.deployProcessWithInput;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.primingPoll;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.resolutionFailureStates;
import static io.camunda.zeebe.it.engine.secret.SecretResolutionTestHarness.secretReference;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.application.commons.secrets.SecretStoreEnvironment;
import io.camunda.client.CamundaClient;
import io.camunda.configuration.Secrets.EnvStore;
import io.camunda.zeebe.protocol.record.intent.IncidentIntent;
import io.camunda.zeebe.protocol.record.value.ErrorType;
import io.camunda.zeebe.protocol.record.value.ResolutionState;
import io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration.TestZeebe;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.logging.LogCapturer;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers values from the environment-variable secret store that a simple string does not: a
 * multi-line PEM, a JSON document and a name only a backticked reference can reach, each through a
 * deployed process into the job a worker receives, and a missing variable that must raise an
 * incident rather than hand out the job.
 *
 * <p>The broker runs inside this JVM, which cannot set its own environment variables, so the
 * environment the store snapshots is supplied as a {@link SecretStoreEnvironment} bean instead.
 */
@ZeebeIntegration
final class EnvSecretStoreProcessIT {

  // unique per run, so no variable this test sets can match a prefix another test configures
  private static final String PREFIX =
      "ENVIT_" + UUID.randomUUID().toString().replace("-", "").toUpperCase() + "_";

  private static final String PEM =
      """
      -----BEGIN CERTIFICATE-----
      bm90IGEgcmVhbCBjZXJ0aWZpY2F0ZSwg
      b25seSB0ZXN0IGRhdGE=
      -----END CERTIFICATE-----
      """;
  private static final String JSON = "{\"user\":\"svc\",\"port\":5432,\"tags\":[\"a\",\"b\"]}";
  private static final String DB_PASSWORD = "db-password-value";

  private static final Map<String, String> ENVIRONMENT =
      Map.of(
          PREFIX + "TLS_KEY", PEM,
          PREFIX + "CONFIG", JSON,
          PREFIX + "db-password", DB_PASSWORD);

  @TestZeebe
  private static final TestStandaloneBroker BROKER =
      new TestStandaloneBroker()
          .withRecordingExporter(true)
          .withUnauthenticatedAccess()
          .withUnifiedConfig(
              camunda -> {
                final var store = new EnvStore();
                store.setPrefix(PREFIX);
                camunda.getSecrets().getStores().getEnv().put("default", store);
              })
          .withProcessingConfig(
              processing -> processing.getEngine().getSecrets().setInterval(RESOLUTION_INTERVAL))
          .withBean(
              "secretStoreEnvironment",
              (SecretStoreEnvironment) () -> ENVIRONMENT,
              SecretStoreEnvironment.class);

  @AutoClose private final CamundaClient client = BROKER.newClientBuilder().build();

  private final String processId = Strings.newRandomValidBpmnId();
  private final String jobType = Strings.newRandomValidBpmnId();

  @BeforeEach
  void setUp() {
    applyRecordWaitTime();
  }

  @Test
  void shouldHandOutAMultiLinePemByteForByte() {
    // given
    deployProcessWithInput(client, processId, jobType, secretReference("TLS_KEY"));
    createProcessInstance(client, processId);

    // when
    final var job = activateOneJob(client, jobType);

    // then - newlines and the trailing newline included
    assertThat(job.getVariablesAsMap().get(INPUT_TARGET)).isEqualTo(PEM);
  }

  @Test
  void shouldHandOutAJsonValueAsTheSameStringUnparsed() {
    // given
    deployProcessWithInput(client, processId, jobType, secretReference("CONFIG"));
    createProcessInstance(client, processId);

    // when
    final var job = activateOneJob(client, jobType);

    // then - a string, not the object it describes
    assertThat(job.getVariablesAsMap().get(INPUT_TARGET)).isEqualTo(JSON);
  }

  @Test
  void shouldResolveABacktickedReferenceToItsPrefixedVariable() {
    // given
    deployProcessWithInput(client, processId, jobType, backtickedSecretReference("db-password"));
    createProcessInstance(client, processId);

    // when
    final var job = activateOneJob(client, jobType);

    // then
    assertThat(job.getVariablesAsMap()).containsEntry(INPUT_TARGET, DB_PASSWORD);
  }

  @Test
  void shouldRaiseAnIncidentInsteadOfHandingOutAJobWhoseVariableIsUnset() {
    // given
    final String missingName = "MISSING_" + UUID.randomUUID().toString().replace("-", "");
    deployProcessWithInput(client, processId, jobType, secretReference(missingName));

    try (final var logs = LogCapturer.capturing("io.camunda", Level.DEBUG)) {
      final long processInstanceKey = createProcessInstance(client, processId);
      awaitJobKeyOf(processInstanceKey);

      // when - the poll checks the job's references and finds the variable unset
      final var jobs = primingPoll(client, jobType);
      final var incident =
          RecordingExporter.incidentRecords(IncidentIntent.CREATED)
              .withProcessInstanceKey(processInstanceKey)
              .withErrorType(ErrorType.SECRET_RESOLUTION_ERROR)
              .getFirst()
              .getValue();

      // then - no job, and an incident that names the secret as not found
      assertThat(jobs).isEmpty();
      assertThat(incident.getErrorMessage()).contains(missingName);
      assertThat(resolutionFailureStates(missingName)).containsOnly(ResolutionState.NOT_FOUND);

      // and - no value the store holds leaks into the incident or the logs
      assertThat(ENVIRONMENT.values())
          .allSatisfy(
              value -> {
                assertThat(incident.getErrorMessage()).doesNotContain(value);
                assertThat(logs.contains(value)).as("logs contain a secret value").isFalse();
              });
    }
  }
}
