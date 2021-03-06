/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.management.internal.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.Before;
import org.junit.Test;

import org.apache.geode.management.api.ClusterManagementOperation;
import org.apache.geode.management.api.JsonSerializable;
import org.apache.geode.management.internal.operation.OperationHistoryManager.OperationInstance;

public class OperationManagerTest {
  private OperationManager executorManager;

  @Before
  public void setUp() throws Exception {
    executorManager = new OperationManager(new OperationHistoryManager(1));
    executorManager.registerOperation(TestOperation.class, OperationManagerTest::perform);
  }

  @Test
  public void submitAndComplete() throws Exception {
    TestOperation operation = new TestOperation();
    OperationInstance<TestOperation, TestResult> inst = executorManager.submit(operation);
    CompletableFuture<TestResult> future1 = inst.getFuture();
    String id = inst.getId();
    assertThat(id).isNotBlank();

    assertThat(executorManager.getStatus(id)).isNotNull();

    TestOperation operation2 = new TestOperation();
    OperationInstance<TestOperation, TestResult> inst2 = executorManager.submit(operation2);
    CompletableFuture<TestResult> future2 = inst2.getFuture();
    String id2 = inst2.getId();
    assertThat(id2).isNotBlank();

    operation.latch.countDown();
    future1.get();

    operation2.latch.countDown();
    future2.get();

    assertThat(executorManager.getStatus(id)).isNull(); // queue size 1 so should have been bumped
    assertThat(executorManager.getStatus(id2)).isNotNull();
  }

  @Test
  public void submit() {
    TestOperation operation = new TestOperation();
    String id = executorManager.submit(operation).getId();
    assertThat(id).isNotBlank();

    assertThat(executorManager.getStatus(id)).isNotNull();

    TestOperation operation2 = new TestOperation();
    String id2 = executorManager.submit(operation2).getId();
    assertThat(id2).isNotBlank();

    assertThat(executorManager.getStatus(id)).isNotNull(); // all in progress, none should be bumped
    assertThat(executorManager.getStatus(id2)).isNotNull();

    operation.latch.countDown();
    operation2.latch.countDown();
  }

  @Test
  public void submitRogueOperation() {
    TestOperation operation = mock(TestOperation.class);
    assertThatThrownBy(() -> executorManager.submit(operation))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Operation type")
        .hasMessageContaining(" not supported");
  }

  static class TestOperation implements ClusterManagementOperation<TestResult> {
    CountDownLatch latch = new CountDownLatch(1);

    @Override
    public String getEndpoint() {
      return "/operations/test";
    }
  }

  static class TestResult implements JsonSerializable {
    String testResult;
  }

  static TestResult perform(TestOperation operation) {
    try {
      operation.latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return null;
  }
}
