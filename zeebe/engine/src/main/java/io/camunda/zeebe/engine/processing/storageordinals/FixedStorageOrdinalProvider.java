/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.storageordinals;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Used to support a fixed set of ordinals */
public final class FixedStorageOrdinalProvider implements StorageOrdinalProvider {
  private final List<Integer> storageOrdinals;

  public FixedStorageOrdinalProvider(final int storageOrdinal) {
    this(List.of(storageOrdinal));
  }

  public FixedStorageOrdinalProvider(final List<Integer> storageOrdinals) {
    if (storageOrdinals.isEmpty()) {
      throw new IllegalArgumentException("No storage ordinals specified");
    }
    this.storageOrdinals = storageOrdinals;
  }

  @Override
  public int getStorageOrdinal() {
    final var random = ThreadLocalRandom.current();
    final var index = random.nextInt(storageOrdinals.size());
    return storageOrdinals.get(index);
  }
}
