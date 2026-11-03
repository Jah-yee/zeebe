/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.it.physicaltenant;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.application.commons.secrets.SecretStoreEnvironment;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.configuration.Secrets.EnvStore;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.qa.util.cluster.PhysicalTenantsITHelper;
import io.camunda.zeebe.qa.util.cluster.PhysicalTenantsITHelper.Storage;
import io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration.TestZeebe;
import io.camunda.zeebe.test.util.Strings;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;

/**
 * Covers that each physical tenant's environment-variable store reads only the variables carrying
 * its own prefix: both tenants hold a variable for the same secret name with a different value, and
 * tenant B holds one more that tenant A must never see.
 *
 * <p>The broker runs inside this JVM, which cannot set its own environment variables, so the
 * environment the stores snapshot is supplied as a {@link SecretStoreEnvironment} bean instead.
 */
@ZeebeIntegration
final class PhysicalTenantEnvSecretIsolationIT {

  private static final String TENANT_A = "tenanta";
  private static final String TENANT_B = "tenantb";

  // unique per run, so no variable this test sets can match a prefix another test configures
  private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").toUpperCase();
  private static final String PREFIX_A = "TENANTA_" + RUN_ID + "_";
  private static final String PREFIX_B = "TENANTB_" + RUN_ID + "_";

  private static final String SECRET_NAME = "API_KEY";
  private static final String ONLY_B_NAME = "ONLY_B";
  private static final String SECRET_VALUE_A = "value-of-tenant-a";
  private static final String SECRET_VALUE_B = "value-of-tenant-b";

  private static final Map<String, String> ENVIRONMENT =
      Map.of(
          PREFIX_A + SECRET_NAME, SECRET_VALUE_A,
          PREFIX_B + SECRET_NAME, SECRET_VALUE_B,
          PREFIX_B + ONLY_B_NAME, "only-tenant-b");

  private static final String INPUT_TARGET = "token";

  private static final String PROCESS_ID = Strings.newRandomValidBpmnId();
  private static final String JOB_TYPE = Strings.newRandomValidBpmnId();

  private static final Duration RESOLUTION_INTERVAL = Duration.ofMillis(200);
  private static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration JOB_TIMEOUT = Duration.ofMinutes(5);

  private static final PhysicalTenantsITHelper TENANTS =
      PhysicalTenantsITHelper.builder()
          .withTenant(PhysicalTenantsITHelper.DEFAULT_TENANT_ID, Storage.none())
          .withTenant(TENANT_A, Storage.none())
          .withTenant(TENANT_B, Storage.none())
          .build();

  @TestZeebe
  private final TestStandaloneBroker broker =
      withPerTenantEnvStores(
              TENANTS.configure(new TestStandaloneBroker().withUnauthenticatedAccess()))
          .withBean(
              "secretStoreEnvironment",
              (SecretStoreEnvironment) () -> ENVIRONMENT,
              SecretStoreEnvironment.class);

  @AutoClose private CamundaClient tenantAClient;
  @AutoClose private CamundaClient tenantBClient;

  @Test
  void shouldHandEachTenantTheValueOfItsOwnPrefixedVariable() {
    // given
    tenantAClient = TENANTS.newClientBuilder(broker, TENANT_A).build();
    tenantBClient = TENANTS.newClientBuilder(broker, TENANT_B).build();
    deployAndCreateInstance(tenantAClient);
    deployAndCreateInstance(tenantBClient);

    // when
    final ActivatedJob jobOfTenantA = activateOneJob(tenantAClient);
    final ActivatedJob jobOfTenantB = activateOneJob(tenantBClient);

    // then - the same reference resolves to a different value per tenant
    assertThat(jobOfTenantA.getVariablesAsMap()).containsEntry(INPUT_TARGET, SECRET_VALUE_A);
    assertThat(jobOfTenantB.getVariablesAsMap()).containsEntry(INPUT_TARGET, SECRET_VALUE_B);
  }

  @Test
  void shouldListOnlyTheSecretsOfTheTenantsOwnPrefix() {
    // given
    tenantAClient = TENANTS.newClientBuilder(broker, TENANT_A).build();
    tenantBClient = TENANTS.newClientBuilder(broker, TENANT_B).build();

    // when
    final var referencesOfTenantA =
        tenantAClient.newListSecretsCommand().send().join().getReferences();
    final var referencesOfTenantB =
        tenantBClient.newListSecretsCommand().send().join().getReferences();

    // then - tenant B's extra variable is listed for tenant B only
    assertThat(referencesOfTenantA).containsExactly("camunda.secrets." + SECRET_NAME);
    assertThat(referencesOfTenantB)
        .containsExactlyInAnyOrder(
            "camunda.secrets." + SECRET_NAME, "camunda.secrets." + ONLY_B_NAME);
  }

  private static TestStandaloneBroker withPerTenantEnvStores(final TestStandaloneBroker broker) {
    Map.of(TENANT_A, PREFIX_A, TENANT_B, PREFIX_B)
        .forEach(
            (tenantId, prefix) -> {
              final var store = new EnvStore();
              store.setPrefix(prefix);
              broker.withPtConfig(
                  tenantId,
                  camunda -> {
                    camunda.getSecrets().getStores().getEnv().put("default", store);
                    camunda
                        .getProcessing()
                        .getEngine()
                        .getSecrets()
                        .setInterval(RESOLUTION_INTERVAL);
                  });
            });
    return broker;
  }

  private static void deployAndCreateInstance(final CamundaClient client) {
    client
        .newDeployResourceCommand()
        .addProcessModel(
            Bpmn.createExecutableProcess(PROCESS_ID)
                .startEvent()
                .serviceTask(
                    "task",
                    task ->
                        task.zeebeJobType(JOB_TYPE)
                            .zeebeInputExpression("camunda.secrets." + SECRET_NAME, INPUT_TARGET))
                .endEvent()
                .done(),
            PROCESS_ID + ".bpmn")
        .send()
        .join();
    client.newCreateInstanceCommand().bpmnProcessId(PROCESS_ID).latestVersion().send().join();
  }

  private static ActivatedJob activateOneJob(final CamundaClient client) {
    final var jobs =
        client
            .newActivateJobsCommand()
            .jobType(JOB_TYPE)
            .maxJobsToActivate(1)
            .timeout(JOB_TIMEOUT)
            .requestTimeout(LONG_POLL_TIMEOUT)
            .send()
            .join()
            .getJobs();
    assertThat(jobs).hasSize(1);
    return jobs.get(0);
  }
}
