/*
 * Copyright © 2014-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.internal.app.deploy.pipeline;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import io.cdap.cdap.api.ProgramSpecification;
import io.cdap.cdap.api.app.ApplicationSpecification;
import io.cdap.cdap.api.dataset.DataSetException;
import io.cdap.cdap.api.dataset.DatasetManagementException;
import io.cdap.cdap.api.dataset.DatasetSpecification;
import io.cdap.cdap.api.workflow.ScheduleProgramInfo;
import io.cdap.cdap.api.workflow.WorkflowActionNode;
import io.cdap.cdap.api.workflow.WorkflowConditionNode;
import io.cdap.cdap.api.workflow.WorkflowForkNode;
import io.cdap.cdap.api.workflow.WorkflowNode;
import io.cdap.cdap.api.workflow.WorkflowNodeType;
import io.cdap.cdap.api.workflow.WorkflowSpecification;
import io.cdap.cdap.app.store.Store;
import io.cdap.cdap.app.verification.Verifier;
import io.cdap.cdap.app.verification.VerifyResult;
import io.cdap.cdap.data2.dataset2.DatasetFramework;
import io.cdap.cdap.internal.app.verification.ApplicationVerification;
import io.cdap.cdap.internal.app.verification.DatasetCreationSpecVerifier;
import io.cdap.cdap.internal.app.verification.ProgramVerification;
import io.cdap.cdap.internal.dataset.DatasetCreationSpec;
import io.cdap.cdap.internal.schedule.ScheduleCreationSpec;
import io.cdap.cdap.pipeline.AbstractStage;
import io.cdap.cdap.proto.id.ApplicationId;
import io.cdap.cdap.proto.id.DatasetId;
import io.cdap.cdap.proto.id.KerberosPrincipalId;
import io.cdap.cdap.proto.id.NamespacedEntityId;
import io.cdap.cdap.security.authorization.AuthorizationUtil;
import io.cdap.cdap.security.impersonation.OwnerAdmin;
import io.cdap.cdap.security.impersonation.SecurityUtil;
import io.cdap.cdap.security.spi.authentication.AuthenticationContext;
import io.cdap.cdap.security.spi.authorization.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * This {@link io.cdap.cdap.pipeline.Stage} is responsible for verifying
 * the specification and components of specification. Verification of each
 * component of specification is achieved by the {@link Verifier}
 * concrete implementations.
 */
public class ApplicationVerificationStage extends AbstractStage<ApplicationDeployable> {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationVerificationStage.class);
  private final Map<Class<?>, Verifier<?>> verifiers = Maps.newIdentityHashMap();
  private final DatasetFramework dsFramework;
  private final Store store;
  private final OwnerAdmin ownerAdmin;
  private final AuthenticationContext authenticationContext;

  public ApplicationVerificationStage(Store store, DatasetFramework dsFramework,
                                      OwnerAdmin ownerAdmin, AuthenticationContext authenticationContext) {
    super(TypeToken.of(ApplicationDeployable.class));
    this.store = store;
    this.dsFramework = dsFramework;
    this.ownerAdmin = ownerAdmin;
    this.authenticationContext = authenticationContext;
  }

  /**
   * Receives an input containing application specification and location
   * and verifies both.
   *
   * @param input An instance of {@link ApplicationDeployable}
   */
  @Override
  public void process(ApplicationDeployable input) throws Exception {
    Preconditions.checkNotNull(input);

    ApplicationSpecification specification = input.getSpecification();
    ApplicationId appId = input.getApplicationId();

    // verify that the owner principal is valid if one was given
    if (input.getOwnerPrincipal() != null) {
      SecurityUtil.validateKerberosPrincipal(input.getOwnerPrincipal());
    }

    Collection<ApplicationId> allAppVersionsAppIds = store.getAllAppVersionsAppIds(appId.getAppReference());
    // if allAppVersionsAppIds.isEmpty() is false that means some version of this app already exists so we should
    // verify that the owner is same
    if (!allAppVersionsAppIds.isEmpty()) {
      verifyOwner(appId, input.getOwnerPrincipal());
    }

    verifySpec(appId, specification);
    // We are verifying owner of dataset at this stage itself even though the creation will fail in later
    // stage if the owner is different because we don't want to end up in scenario where we created few dataset
    // and the failed because some dataset already exists and have different owner
    verifyData(appId, specification, input.getOwnerPrincipal());
    verifyPrograms(appId, specification);

    // Emit the input to next stage.
    emit(input);
  }

  private void verifySpec(ApplicationId appId,
                          ApplicationSpecification specification) {
    VerifyResult result = getVerifier(ApplicationSpecification.class).verify(appId, specification);
    if (!result.isSuccess()) {
      throw new RuntimeException(result.getMessage());
    }
  }

  private void verifyData(ApplicationId appId, ApplicationSpecification specification,
                          @Nullable KerberosPrincipalId ownerPrincipal) throws Exception {
    // NOTE: no special restrictions on dataset module names, etc
    VerifyResult result;
    for (DatasetCreationSpec dataSetCreateSpec : specification.getDatasets().values()) {
      result = getVerifier(DatasetCreationSpec.class).verify(appId, dataSetCreateSpec);
      if (!result.isSuccess()) {
        throw new RuntimeException(result.getMessage());
      }
      String dsName = dataSetCreateSpec.getInstanceName();
      final DatasetId datasetInstanceId = appId.getParent().dataset(dsName);
      // get the authorizing user
      String authorizingUser =
        AuthorizationUtil.getAppAuthorizingUser(ownerAdmin, authenticationContext, appId, ownerPrincipal);
      DatasetSpecification existingSpec =
        AuthorizationUtil.authorizeAs(authorizingUser, new Callable<DatasetSpecification>() {
          @Override
          public DatasetSpecification call() throws Exception {
            return dsFramework.getDatasetSpec(datasetInstanceId);
          }
        });
      if (existingSpec != null && !existingSpec.getType().equals(dataSetCreateSpec.getTypeName())) {
        // New app trying to deploy an dataset with same instanceName but different Type than that of existing.
        throw new DataSetException
          (String.format("Cannot Deploy Dataset : %s with Type : %s : Dataset with different Type Already Exists",
                         dsName, dataSetCreateSpec.getTypeName()));
      }

      // if the dataset existed verify its owner is same.
      if (existingSpec != null) {
        verifyOwner(datasetInstanceId, ownerPrincipal);
      }
    }
  }

  private void verifyOwner(NamespacedEntityId entityId, @Nullable KerberosPrincipalId specifiedOwnerPrincipal)
    throws DatasetManagementException, UnauthorizedException {
    try {
      SecurityUtil.verifyOwnerPrincipal(entityId,
                                        specifiedOwnerPrincipal == null ? null : specifiedOwnerPrincipal.getPrincipal(),
                                        ownerAdmin);
    } catch (IOException e) {
      throw new DatasetManagementException(e.getMessage(), e);
    }
  }

  protected void verifyPrograms(ApplicationId appId, ApplicationSpecification specification) {
    Iterable<ProgramSpecification> programSpecs = Iterables.concat(specification.getMapReduce().values(),
                                                                   specification.getWorkflows().values());
    VerifyResult result;
    for (ProgramSpecification programSpec : programSpecs) {
      Verifier<ProgramSpecification> verifier = getVerifier(programSpec.getClass());
      result = verifier.verify(appId, programSpec);
      if (!result.isSuccess()) {
        throw new RuntimeException(result.getMessage());
      }
    }

    for (Map.Entry<String, WorkflowSpecification> entry : specification.getWorkflows().entrySet()) {
      verifyWorkflowSpecifications(specification, entry.getValue());
    }

    for (Map.Entry<String, ScheduleCreationSpec> entry : specification.getProgramSchedules().entrySet()) {
      String programName = entry.getValue().getProgramName();
      if (!specification.getWorkflows().containsKey(programName)) {
        throw new RuntimeException(String.format("Schedule '%s' is invalid: Workflow '%s' is not configured " +
                                                   "in application '%s'",
                                                 entry.getValue().getName(), programName, specification.getName()));
      }
    }
  }

  private void verifyWorkflowSpecifications(ApplicationSpecification appSpec, WorkflowSpecification workflowSpec) {
    Set<String> existingNodeNames = new HashSet<>();
    verifyWorkflowNodeList(appSpec, workflowSpec, workflowSpec.getNodes(), existingNodeNames);
  }

  private void verifyWorkflowNode(ApplicationSpecification appSpec, WorkflowSpecification workflowSpec,
                                  WorkflowNode node, Set<String> existingNodeNames) {
    WorkflowNodeType nodeType = node.getType();
    // TODO CDAP-5640 Add check so that node id in the Workflow should not be same as name of the Workflow.
    if (node.getNodeId().equals(workflowSpec.getName())) {
      String msg = String.format("Node used in Workflow has same name as that of Workflow '%s'." +
                                   " This will conflict while getting the Workflow token details associated with" +
                                   " the node. Please use name for the node other than the name of the Workflow.",
                                 workflowSpec.getName());
      LOG.warn(msg);
    }
    switch (nodeType) {
      case ACTION:
        verifyWorkflowAction(appSpec, node);
        break;
      case FORK:
        verifyWorkflowFork(appSpec, workflowSpec, node, existingNodeNames);
        break;
      case CONDITION:
        verifyWorkflowCondition(appSpec, workflowSpec, node, existingNodeNames);
        break;
      default:
        break;
    }
  }

  private void verifyWorkflowFork(ApplicationSpecification appSpec, WorkflowSpecification workflowSpec,
                                  WorkflowNode node, Set<String> existingNodeNames) {
    WorkflowForkNode forkNode = (WorkflowForkNode) node;
    Preconditions.checkNotNull(forkNode.getBranches(), String.format("Fork is added in the Workflow '%s' without" +
                                                                       " any branches", workflowSpec.getName()));

    for (List<WorkflowNode> branch : forkNode.getBranches()) {
      verifyWorkflowNodeList(appSpec, workflowSpec, branch, existingNodeNames);
    }
  }

  private void verifyWorkflowCondition(ApplicationSpecification appSpec, WorkflowSpecification workflowSpec,
                                       WorkflowNode node, Set<String> existingNodeNames) {
    WorkflowConditionNode condition = (WorkflowConditionNode) node;
    verifyWorkflowNodeList(appSpec, workflowSpec, condition.getIfBranch(), existingNodeNames);
    verifyWorkflowNodeList(appSpec, workflowSpec, condition.getElseBranch(), existingNodeNames);
  }

  private void verifyWorkflowNodeList(ApplicationSpecification appSpec, WorkflowSpecification workflowSpec,
                                      List<WorkflowNode> nodeList, Set<String> existingNodeNames) {
    for (WorkflowNode n : nodeList) {
      if (existingNodeNames.contains(n.getNodeId())) {
        throw new RuntimeException(String.format("Node '%s' already exists in workflow '%s'.", n.getNodeId(),
                                                 workflowSpec.getName()));
      }
      existingNodeNames.add(n.getNodeId());
      verifyWorkflowNode(appSpec, workflowSpec, n, existingNodeNames);
    }
  }

  private void verifyWorkflowAction(ApplicationSpecification appSpec, WorkflowNode node) {
    WorkflowActionNode actionNode = (WorkflowActionNode) node;
    ScheduleProgramInfo program = actionNode.getProgram();
    switch (program.getProgramType()) {
      case MAPREDUCE:
        Preconditions.checkArgument(appSpec.getMapReduce().containsKey(program.getProgramName()),
                                    String.format("MapReduce program '%s' is not configured with the Application.",
                                                  program.getProgramName()));
        break;
      case SPARK:
        Preconditions.checkArgument(appSpec.getSpark().containsKey(program.getProgramName()),
                                    String.format("Spark program '%s' is not configured with the Application.",
                                                  program.getProgramName()));
        break;
      case CUSTOM_ACTION:
        // no-op
        break;
      default:
        throw new RuntimeException(String.format("Unknown Program '%s' in the Workflow.",
                                                 program.getProgramName()));
    }
  }


  @SuppressWarnings("unchecked")
  private <T> Verifier<T> getVerifier(Class<? extends T> clz) {
    if (verifiers.containsKey(clz)) {
      return (Verifier<T>) verifiers.get(clz);
    }

    if (ApplicationSpecification.class.isAssignableFrom(clz)) {
      verifiers.put(clz, new ApplicationVerification());
    } else if (ProgramSpecification.class.isAssignableFrom(clz)) {
      verifiers.put(clz, createProgramVerifier((Class<ProgramSpecification>) clz));
    } else if (DatasetCreationSpec.class.isAssignableFrom(clz)) {
      verifiers.put(clz, new DatasetCreationSpecVerifier());
    }

    return (Verifier<T>) verifiers.get(clz);
  }

  private <T extends ProgramSpecification> Verifier<T> createProgramVerifier(Class<T> clz) {
    return new ProgramVerification<>();
  }
}
