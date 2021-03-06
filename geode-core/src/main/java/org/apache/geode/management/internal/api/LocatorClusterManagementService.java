/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.geode.management.internal.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.cache.configuration.CacheConfig;
import org.apache.geode.cache.configuration.CacheElement;
import org.apache.geode.cache.configuration.GatewayReceiverConfig;
import org.apache.geode.cache.configuration.RegionConfig;
import org.apache.geode.cache.execute.Execution;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.distributed.ConfigurationPersistenceService;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.execute.AbstractExecution;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.management.api.ClusterManagementListResult;
import org.apache.geode.management.api.ClusterManagementOperation;
import org.apache.geode.management.api.ClusterManagementOperationResult;
import org.apache.geode.management.api.ClusterManagementResult;
import org.apache.geode.management.api.ClusterManagementService;
import org.apache.geode.management.api.ConfigurationResult;
import org.apache.geode.management.api.CorrespondWith;
import org.apache.geode.management.api.JsonSerializable;
import org.apache.geode.management.api.RealizationResult;
import org.apache.geode.management.api.RestfulEndpoint;
import org.apache.geode.management.configuration.MemberConfig;
import org.apache.geode.management.configuration.Pdx;
import org.apache.geode.management.internal.CacheElementOperation;
import org.apache.geode.management.internal.ClusterManagementOperationStatusResult;
import org.apache.geode.management.internal.cli.functions.CacheRealizationFunction;
import org.apache.geode.management.internal.configuration.mutators.ConfigurationManager;
import org.apache.geode.management.internal.configuration.mutators.GatewayReceiverConfigManager;
import org.apache.geode.management.internal.configuration.mutators.PdxManager;
import org.apache.geode.management.internal.configuration.mutators.RegionConfigManager;
import org.apache.geode.management.internal.configuration.validators.CacheElementValidator;
import org.apache.geode.management.internal.configuration.validators.ConfigurationValidator;
import org.apache.geode.management.internal.configuration.validators.GatewayReceiverConfigValidator;
import org.apache.geode.management.internal.configuration.validators.MemberValidator;
import org.apache.geode.management.internal.configuration.validators.RegionConfigValidator;
import org.apache.geode.management.internal.exceptions.EntityNotFoundException;
import org.apache.geode.management.internal.operation.OperationHistoryManager;
import org.apache.geode.management.internal.operation.OperationHistoryManager.OperationInstance;
import org.apache.geode.management.internal.operation.OperationManager;
import org.apache.geode.management.runtime.RuntimeInfo;

public class LocatorClusterManagementService implements ClusterManagementService {
  private static final Logger logger = LogService.getLogger();
  private final ConfigurationPersistenceService persistenceService;
  private final Map<Class, ConfigurationManager> managers;
  private final Map<Class, ConfigurationValidator> validators;
  private final OperationManager executorManager;
  private final MemberValidator memberValidator;
  private final CacheElementValidator commonValidator;

  public LocatorClusterManagementService(InternalCache cache,
      ConfigurationPersistenceService persistenceService) {
    this(persistenceService, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
        new MemberValidator(cache, persistenceService), new CacheElementValidator(),
        new OperationManager(new OperationHistoryManager()));
    // initialize the list of managers
    managers.put(RegionConfig.class, new RegionConfigManager());
    managers.put(Pdx.class, new PdxManager());
    managers.put(GatewayReceiverConfig.class, new GatewayReceiverConfigManager(cache));

    // initialize the list of validators
    validators.put(RegionConfig.class, new RegionConfigValidator(cache));
    validators.put(GatewayReceiverConfig.class, new GatewayReceiverConfigValidator());
  }

  @VisibleForTesting
  public LocatorClusterManagementService(ConfigurationPersistenceService persistenceService,
      Map<Class, ConfigurationManager> managers,
      Map<Class, ConfigurationValidator> validators,
      MemberValidator memberValidator,
      CacheElementValidator commonValidator,
      OperationManager executorManager) {
    this.persistenceService = persistenceService;
    this.managers = managers;
    this.validators = validators;
    this.memberValidator = memberValidator;
    this.commonValidator = commonValidator;
    this.executorManager = executorManager;
  }

  @Override
  public <T extends CacheElement> ClusterManagementResult create(T config) {
    // validate that user used the correct config object type
    ConfigurationManager configurationManager = getConfigurationManager(config);

    if (persistenceService == null) {
      return new ClusterManagementResult(false,
          "Cluster configuration service needs to be enabled");
    }

    // first validate common attributes of all configuration object
    commonValidator.validate(CacheElementOperation.CREATE, config);

    String group = config.getConfigGroup();
    ConfigurationValidator validator = validators.get(config.getClass());
    if (validator != null) {
      validator.validate(CacheElementOperation.CREATE, config);
    }

    // check if this config already exists on all/some members of this group
    memberValidator.validateCreate(config, configurationManager);

    // execute function on all members
    Set<DistributedMember> targetedMembers = memberValidator.findServers(group);

    ClusterManagementResult result = new ClusterManagementResult();

    List<RealizationResult> functionResults = executeAndGetFunctionResult(
        new CacheRealizationFunction(),
        Arrays.asList(config, CacheElementOperation.CREATE),
        targetedMembers);

    functionResults.forEach(result::addMemberStatus);

    // if any false result is added to the member list
    if (result.getStatusCode() != ClusterManagementResult.StatusCode.OK) {
      result.setStatus(false, "Failed to apply the update on all members");
      return result;
    }

    // persist configuration in cache config
    final String finalGroup = group; // the below lambda requires a reference that is final
    persistenceService.updateCacheConfig(finalGroup, cacheConfigForGroup -> {
      try {
        configurationManager.add(config, cacheConfigForGroup);
        result.setStatus(true,
            "Successfully updated config for " + finalGroup);
      } catch (Exception e) {
        String message = "Failed to update cluster config for " + finalGroup;
        logger.error(message, e);
        result.setStatus(ClusterManagementResult.StatusCode.FAIL_TO_PERSIST, message);
        return null;
      }
      return cacheConfigForGroup;
    });

    // add the config object which includes the HATOS information of the element created
    if (result.isSuccessful() && config instanceof RestfulEndpoint) {
      result.setUri(((RestfulEndpoint) config).getUri());
    }
    return result;
  }

  @Override
  public <T extends CacheElement> ClusterManagementResult delete(
      T config) {
    // validate that user used the correct config object type
    ConfigurationManager configurationManager = getConfigurationManager(config);

    if (persistenceService == null) {
      return new ClusterManagementResult(false,
          "Cluster configuration service needs to be enabled");
    }

    // first validate common attributes of all configuration object
    commonValidator.validate(CacheElementOperation.DELETE, config);

    ConfigurationValidator validator = validators.get(config.getClass());
    if (validator != null) {
      validator.validate(CacheElementOperation.DELETE, config);
    }

    String[] groupsWithThisElement =
        memberValidator.findGroupsWithThisElement(config.getId(), configurationManager);
    if (groupsWithThisElement.length == 0) {
      throw new EntityNotFoundException("Cache element '" + config.getId() + "' does not exist");
    }

    // execute function on all members
    ClusterManagementResult result = new ClusterManagementResult();

    List<RealizationResult> functionResults = executeAndGetFunctionResult(
        new CacheRealizationFunction(),
        Arrays.asList(config, CacheElementOperation.DELETE),
        memberValidator.findServers(groupsWithThisElement));
    functionResults.forEach(result::addMemberStatus);

    // if any false result is added to the member list
    if (result.getStatusCode() != ClusterManagementResult.StatusCode.OK) {
      result.setStatus(false, "Failed to apply the update on all members");
      return result;
    }

    // persist configuration in cache config
    List<String> updatedGroups = new ArrayList<>();
    List<String> failedGroups = new ArrayList<>();
    for (String finalGroup : groupsWithThisElement) {
      persistenceService.updateCacheConfig(finalGroup, cacheConfigForGroup -> {
        try {
          configurationManager.delete(config, cacheConfigForGroup);
          updatedGroups.add(finalGroup);
        } catch (Exception e) {
          logger.error("Failed to update cluster config for " + finalGroup, e);
          failedGroups.add(finalGroup);
          return null;
        }
        return cacheConfigForGroup;
      });
    }

    if (failedGroups.isEmpty()) {
      result.setStatus(true, "Successfully removed config for " + updatedGroups);
    } else {
      String message = "Failed to update cluster config for " + failedGroups;
      result.setStatus(ClusterManagementResult.StatusCode.FAIL_TO_PERSIST, message);
    }

    return result;
  }

  @Override
  public <T extends CacheElement> ClusterManagementResult update(
      T config) {
    throw new NotImplementedException("Not implemented");
  }

  @Override
  public <T extends CacheElement & CorrespondWith<R>, R extends RuntimeInfo> ClusterManagementListResult<T, R> list(
      T filter) {
    ClusterManagementListResult<T, R> result = new ClusterManagementListResult<>();

    if (persistenceService == null) {
      return new ClusterManagementListResult<>(false,
          "Cluster configuration service needs to be enabled");
    }

    List<T> resultList = new ArrayList<>();

    if (filter instanceof MemberConfig) {
      resultList.add(filter);
    } else {
      ConfigurationManager<T> manager = getConfigurationManager(filter);
      // gather elements on all the groups, consolidate the group information and then do the filter
      // so that when we filter by a specific group, we still show that a particular element might
      // also belong to another group.
      for (String group : persistenceService.getGroups()) {
        CacheConfig currentPersistedConfig = persistenceService.getCacheConfig(group, true);
        List<T> listInGroup = manager.list(filter, currentPersistedConfig);
        for (T element : listInGroup) {
          element.setGroup(group);
          resultList.add(element);
        }
      }

      // if empty result, return immediately
      if (resultList.size() == 0) {
        return result;
      }

      // right now the list contains [{regionA, group1}, {regionA, group2}...], if the elements are
      // MultiGroupCacheElement, we need to consolidate the list into [{regionA, [group1, group2]}
      List<T> consolidatedResultList = new ArrayList<>();
      for (T element : resultList) {
        int index = consolidatedResultList.indexOf(element);
        if (index >= 0) {
          T exist = consolidatedResultList.get(index);
          exist.addGroup(element.getGroup());
        } else {
          consolidatedResultList.add(element);
        }
      }
      if (StringUtils.isNotBlank(filter.getGroup())) {
        consolidatedResultList = consolidatedResultList.stream()
            .filter(e -> (e.getGroups().contains(filter.getConfigGroup())))
            .collect(Collectors.toList());
      }
      resultList = consolidatedResultList;
    }

    // gather the runtime info for each configuration objects
    List<ConfigurationResult<T, R>> responses = new ArrayList<>();
    boolean hasRuntimeInfo = filter.hasRuntimeInfo();

    for (T element : resultList) {
      List<String> groups = element.getGroups();
      ConfigurationResult<T, R> response = new ConfigurationResult<>(element);

      // if "cluster" is the only group, clear it, so that the returning json does not show
      // "cluster" as a group value
      if (element.getGroups().size() == 1 && CacheElement.CLUSTER.equals(element.getGroup())) {
        element.getGroups().clear();
      }

      responses.add(response);
      // do not gather runtime if this type of CacheElement is RespondWith<RuntimeInfo>
      if (!hasRuntimeInfo) {
        continue;
      }

      Set<DistributedMember> members;

      if (filter instanceof MemberConfig) {
        members =
            memberValidator.findMembers(filter.getId(), filter.getGroups().toArray(new String[0]));
      } else {
        members = memberValidator.findServers(groups.toArray(new String[0]));
      }

      // no member belongs to these groups
      if (members.size() == 0) {
        continue;
      }

      // if this cacheElement's runtime info only contains global info (no per member info), we will
      // only need to issue get function on any member instead of all of them.
      if (element.isGlobalRuntime()) {
        members = Collections.singleton(members.iterator().next());
      }

      List<R> runtimeInfos = executeAndGetFunctionResult(new CacheRealizationFunction(),
          Arrays.asList(element, CacheElementOperation.GET),
          members);
      response.setRuntimeInfo(runtimeInfos);
    }

    result.setResult(responses);
    return result;
  }

  @Override
  public <T extends CacheElement & CorrespondWith<R>, R extends RuntimeInfo> ClusterManagementListResult<T, R> get(
      T config) {
    ClusterManagementListResult<T, R> list = list(config);
    List<ConfigurationResult<T, R>> result = list.getResult();

    if (result.size() == 0) {
      throw new EntityNotFoundException(
          config.getClass().getSimpleName() + " with id = " + config.getId() + " not found.");
    }

    if (result.size() > 1) {
      throw new IllegalStateException(
          "Expect only one matching " + config.getClass().getSimpleName());
    }
    return list;
  }

  @Override
  public <A extends ClusterManagementOperation<V>, V extends JsonSerializable> ClusterManagementOperationResult<V> startOperation(
      A op) {
    CompletableFuture<V> future = executorManager.submit(op).getFuture();

    ClusterManagementResult result = new ClusterManagementResult(
        ClusterManagementResult.StatusCode.ACCEPTED, "async operation started");
    return new ClusterManagementOperationResult<>(result, future);
  }

  /**
   * this is intended for use by the REST controller. for Java usage, please use
   * {@link #startOperation(ClusterManagementOperation)}
   */
  public <A extends ClusterManagementOperation<V>, V extends JsonSerializable> ClusterManagementOperationResult<V> startOperation(
      A op, String uri) {
    OperationInstance<A, V> operationInstance = executorManager.submit(op);

    ClusterManagementResult result = new ClusterManagementResult(
        ClusterManagementResult.StatusCode.ACCEPTED, "async operation started");

    String opId = operationInstance.getId();
    String instUri = uri + "/" + opId;
    result.setUri(instUri);

    return new ClusterManagementOperationResult<>(result, operationInstance.getFuture());
  }

  /**
   * this is intended for use by the REST controller. for Java usage, please use
   * {@link ClusterManagementOperationResult#getResult()}
   */
  public <V extends JsonSerializable> ClusterManagementOperationStatusResult<V> checkStatus(
      String opId) {
    final CompletableFuture<V> status = executorManager.getStatus(opId);
    if (status == null) {
      throw new EntityNotFoundException("Operation id = " + opId + " not found");
    }
    ClusterManagementOperationStatusResult<V> result =
        new ClusterManagementOperationStatusResult<>();
    if (!status.isDone()) {
      result.setStatus(ClusterManagementResult.StatusCode.IN_PROGRESS, "in progress");
    } else {
      try {
        result.setResult(status.get());
        result.setStatus(ClusterManagementResult.StatusCode.OK, "finished successfully");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
    }
    return result;
  }

  @Override
  public boolean isConnected() {
    return true;
  }

  @Override
  public void close() {
    executorManager.close();
  }

  private <T extends CacheElement> ConfigurationManager<T> getConfigurationManager(
      T config) {
    ConfigurationManager configurationManager = managers.get(config.getClass());
    if (configurationManager == null) {
      throw new IllegalArgumentException(String.format("Configuration type %s is not supported",
          config.getClass().getSimpleName()));
    }

    return configurationManager;
  }

  @VisibleForTesting
  <R> List<R> executeAndGetFunctionResult(Function function, Object args,
      Set<DistributedMember> targetMembers) {
    if (targetMembers.size() == 0) {
      return Collections.emptyList();
    }

    Execution execution = FunctionService.onMembers(targetMembers).setArguments(args);
    ((AbstractExecution) execution).setIgnoreDepartedMembers(true);
    ResultCollector rc = execution.execute(function);

    return (List<R>) rc.getResult();
  }
}
