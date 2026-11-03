/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.application.commons.secrets;

import java.util.Map;

/**
 * The environment variables an environment-variable secret store snapshots. Production registers no
 * bean of this type, so {@link SecretStoreConfiguration} reads {@link System#getenv()}; a test
 * running a broker inside its own JVM, which cannot set its own environment, registers one instead.
 */
@FunctionalInterface
public interface SecretStoreEnvironment {

  Map<String, String> variables();
}
