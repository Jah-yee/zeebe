/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.it.shared.smoke;

import static io.camunda.zeebe.it.util.ZeebeContainerUtil.newClientBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.configuration.data.SecondaryStorage.SecondaryStorageType;
import io.camunda.container.CamundaContainer.BrokerContainer;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.qa.util.testcontainers.ZeebeTestContainerDefaults;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers that a non-ASCII value set in the container's environment reaches a job unchanged through
 * the environment-variable secret store. Only the real image shows this: how the JVM decodes its
 * environment depends on the image's locale, which an in-JVM broker test never goes through.
 */
@Testcontainers
final class EnvSecretStoreContainerSmokeIT {

  private static final String PREFIX = "SMOKE_";
  private static final String VALUE = "pässwörd€";

  @Container
  private final BrokerContainer broker =
      new BrokerContainer(ZeebeTestContainerDefaults.defaultTestImage())
          .withUnifiedConfig(
              cfg -> cfg.getData().getSecondaryStorage().setType(SecondaryStorageType.none))
          .withEnv("CAMUNDA_SECRETS_STORES_ENV_DEFAULT_PREFIX", PREFIX)
          .withEnv(PREFIX + "PASSWORD", VALUE);

  @AutoClose private CamundaClient client;

  @BeforeEach
  void beforeEach() {
    // container tests might be less responsive when emulation is involved, e.g. of ARM64
    client =
        newClientBuilder(broker)
            .preferRestOverGrpc(false)
            .defaultRequestTimeout(Duration.ofMinutes(2))
            .build();
  }

  @ContainerSmokeTest
  void shouldHandANonAsciiEnvironmentValueToAJobUnchanged() {
    // given
    client
        .newDeployResourceCommand()
        .addProcessModel(
            Bpmn.createExecutableProcess("envSecret")
                .startEvent()
                .serviceTask(
                    "task",
                    task ->
                        task.zeebeJobType("env-secret")
                            .zeebeInputExpression("camunda.secrets.PASSWORD", "password"))
                .endEvent()
                .done(),
            "envSecret.bpmn")
        .send()
        .join();
    client.newCreateInstanceCommand().bpmnProcessId("envSecret").latestVersion().send().join();

    // when
    final var jobs =
        client
            .newActivateJobsCommand()
            .jobType("env-secret")
            .maxJobsToActivate(1)
            .requestTimeout(Duration.ofSeconds(30))
            .send()
            .join()
            .getJobs();

    // then
    assertThat(jobs).hasSize(1);
    assertThat(jobs.get(0).getVariablesAsMap()).containsEntry("password", VALUE);
  }
}
