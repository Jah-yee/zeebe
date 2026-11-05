/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.client.api.response;

import io.camunda.client.api.command.ClientException;
import java.util.Map;

/**
 * An active user task and the variables visible to it, read from the engine's state. The task and
 * its variables are read at the same point in processing, so they are consistent with each other.
 */
public interface LookupUserTaskResponse extends BaseUserTask {

  /**
   * Returns the ID of the process definition associated with this task, the ID from the BPMN model.
   *
   * @return the process definition ID
   */
  String getProcessDefinitionId();

  /**
   * Returns the lifecycle state of the task. While a change is in progress, the task fields and
   * variables are the ones from before the change, and the state names the pending change.
   *
   * @return the lifecycle state
   */
  LookupUserTaskState getState();

  /**
   * @return the variables visible to the task as a JSON document
   */
  String getVariables();

  /**
   * @return the variables visible to the task as a map of name and value
   */
  Map<String, Object> getVariablesAsMap();

  /**
   * @param variableType the type to deserialize the variables into
   * @return the variables visible to the task, deserialized as the given type
   */
  <T> T getVariablesAsType(Class<T> variableType);

  /**
   * @param name the name of the variable
   * @return the value of the variable, which may be {@code null}, if the variable is part of the
   *     response; otherwise throws a {@link ClientException}
   */
  Object getVariable(String name);
}
