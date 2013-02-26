/*
* Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
*/

package com.continuuity.internal.app.services;

import com.continuuity.api.ApplicationSpecification;
import com.continuuity.api.data.OperationException;
import com.continuuity.api.flow.FlowSpecification;
import com.continuuity.api.flow.FlowletDefinition;
import com.continuuity.api.procedure.ProcedureSpecification;
import com.continuuity.app.Id;
import com.continuuity.app.authorization.AuthorizationFactory;
import com.continuuity.app.deploy.Manager;
import com.continuuity.app.deploy.ManagerFactory;
import com.continuuity.app.program.Program;
import com.continuuity.app.program.RunRecord;
import com.continuuity.app.program.Type;
import com.continuuity.app.queue.QueueSpecification;
import com.continuuity.app.queue.QueueSpecificationGenerator;
import com.continuuity.app.runtime.ProgramController;
import com.continuuity.app.runtime.ProgramRuntimeService;
import com.continuuity.app.runtime.RunId;
import com.continuuity.app.services.ActiveFlow;
import com.continuuity.app.services.AppFabricService;
import com.continuuity.app.services.AppFabricServiceException;
import com.continuuity.app.services.AuthToken;
import com.continuuity.app.services.DeployStatus;
import com.continuuity.app.services.DeploymentStatus;
import com.continuuity.app.services.EntityType;
import com.continuuity.app.services.FlowDescriptor;
import com.continuuity.app.services.FlowIdentifier;
import com.continuuity.app.services.FlowRunRecord;
import com.continuuity.app.services.FlowStatus;
import com.continuuity.app.services.ResourceIdentifier;
import com.continuuity.app.services.ResourceInfo;
import com.continuuity.app.services.RunIdentifier;
import com.continuuity.app.store.Store;
import com.continuuity.app.store.StoreFactory;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.common.utils.StackTraceUtil;
import com.continuuity.data.operation.ClearFabric;
import com.continuuity.data.operation.OperationContext;
import com.continuuity.data.operation.executor.OperationExecutor;
import com.continuuity.filesystem.Location;
import com.continuuity.filesystem.LocationFactory;
import com.continuuity.internal.app.deploy.SessionInfo;
import com.continuuity.internal.app.deploy.pipeline.ApplicationWithPrograms;
import com.continuuity.internal.app.queue.SimpleQueueSpecificationGenerator;
import com.continuuity.internal.app.runtime.BasicArguments;
import com.continuuity.internal.app.runtime.SimpleProgramOptions;
import com.continuuity.internal.app.services.legacy.ConnectionDefinitionImpl;
import com.continuuity.internal.app.services.legacy.FlowDefinitionImpl;
import com.continuuity.internal.app.services.legacy.FlowStreamDefinitionImpl;
import com.continuuity.internal.app.services.legacy.FlowletDefinitionImpl;
import com.continuuity.internal.app.services.legacy.FlowletStreamDefinitionImpl;
import com.continuuity.internal.app.services.legacy.FlowletType;
import com.continuuity.internal.app.services.legacy.MetaDefinitionImpl;
import com.continuuity.internal.app.services.legacy.QueryDefinitionImpl;
import com.continuuity.internal.app.services.legacy.StreamNamerImpl;
import com.continuuity.internal.filesystem.LocationCodec;
import com.continuuity.metadata.MetadataService;
import com.continuuity.metrics2.frontend.MetricsFrontendServiceImpl;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.io.Closeables;
import com.google.common.io.InputSupplier;
import com.google.common.io.OutputSupplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * This is a concrete implementation of AppFabric thrift Interface.
 */
public class DefaultAppFabricService implements AppFabricService.Iface {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultAppFabricService.class);

  /**
   * Maintains a mapping of transient session state. The state is stored in memory,
   * in case of failure, all the current running sessions will be terminated. As
   * per the current implementation only connection per account is allowed to upload.
   */
  private final Map<String, SessionInfo> sessions = Maps.newConcurrentMap();

  /**
   * Metadata Service instance is used to interact with the metadata store.
   */
  private final MetadataService mds;

  /**
   * Instance of operation executor needed by MetadataService.
   */
  private final OperationExecutor opex;

  /**
   * Configuration object passed from higher up.
   */
  private final CConfiguration configuration;

  /**
   * Factory for handling the location - can do both in either Distributed or Local mode.
   */
  private final LocationFactory locationFactory;

  /**
   * DeploymentManager responsible for running pipeline.
   */
  private final ManagerFactory managerFactory;

  /**
   * Authorization Factory used to create handler used for authroizing use of endpoints.
   */
  private final AuthorizationFactory authFactory;

  private final ProgramRuntimeService runtimeService;

  private final Store store;

  /**
   * The directory where the uploaded files would be placed.
   */
  private final String archiveDir;
  private static final int UPLOAD_TIMEOUT = 10*60*1000; // 10 min

  /**
   * Construct this object with curator client for managing the zookeeper.
   */
  @Inject
  public DefaultAppFabricService(CConfiguration configuration, OperationExecutor opex,
                                 LocationFactory locationFactory, ManagerFactory managerFactory,
                                 AuthorizationFactory authFactory, StoreFactory storeFactory, ProgramRuntimeService
    runtimeService) {
    this.opex = opex;
    this.locationFactory = locationFactory;
    this.configuration = configuration;
    this.managerFactory = managerFactory;
    this.authFactory = authFactory;
    this.runtimeService = runtimeService;
    this.store = storeFactory.create();
    this.archiveDir = configuration.get(Constants.CFG_APP_FABRIC_OUTPUT_DIR, "/tmp") + "/archive";
    this.mds = new MetadataService(opex);

    // FIXME: This is hacky to start service like this.
    if (this.runtimeService.state() != Service.State.RUNNING) {
      this.runtimeService.startAndWait();
    }
  }

  private Type entityTypeToType(FlowIdentifier identifier) {
    switch (identifier.getType()) {
      case FLOW:
        return Type.FLOW;
      case QUERY:
        return Type.PROCEDURE;
    }
    // Never hit
    throw new IllegalArgumentException("Type not support: " + identifier.getType());
  }

  private EntityType typeToEntityType(Type type) {
    switch (type) {
      case FLOW:
        return EntityType.FLOW;
      case PROCEDURE:
        return EntityType.QUERY;
      case BATCH:
        // TODO
        return null;
    }
    // Never hit
    throw new IllegalArgumentException("Type not suppport: " + type);
  }

  /**
   * Starts a Program
   *
   * @param token
   * @param descriptor
   */
  @Override
  public RunIdentifier start(AuthToken token, FlowDescriptor descriptor)
    throws AppFabricServiceException, TException {

    FlowIdentifier id = descriptor.getIdentifier();
    Id.Program programId = Id.Program.from(id.getAccountId(), id.getApplicationId(), id.getFlowId());
    try {
      Program program = store.loadProgram(programId, entityTypeToType(id));
      // TODO: User arguments
      ProgramRuntimeService.RuntimeInfo runtimeInfo =
        runtimeService.run(program, new SimpleProgramOptions(id.getFlowId(),
                                                             new BasicArguments(),
                                                             new BasicArguments()));
      store.setStart(programId, runtimeInfo.getController().getRunId().getId(),
                     System.currentTimeMillis()/1000);
      return new RunIdentifier(runtimeInfo.getController().getRunId().toString());

    } catch (IOException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw Throwables.propagate(e);
    }
  }

  /**
   * Checks the status of a Program
   *
   * @param token
   * @param id
   */
  @Override
  public FlowStatus status(AuthToken token, FlowIdentifier id)
    throws AppFabricServiceException, TException {

    ProgramRuntimeService.RuntimeInfo runtimeInfo = findRuntimeInfo(id);

    int version = 1;  // FIXME, how to get version?
    if (runtimeInfo == null) {
      return new FlowStatus(id.getApplicationId(), id.getFlowId(),
                            version, null, ProgramController.State.STOPPED.toString());
    }

    Id.Program programId = runtimeInfo.getProgramId();
    RunIdentifier runId = new RunIdentifier(runtimeInfo.getController().getRunId().getId());

    // NOTE: This was a temporary hack done to map the status to something that is
    // UI friendly. Internal states of program controller are reasonable and hence
    // no point in changing them.
    String status = controllerStateToString(runtimeInfo.getController().getState());
    return new FlowStatus(programId.getApplicationId(), programId.getId(), version, runId, status);
  }

  private String controllerStateToString(ProgramController.State state) {
    if(state == ProgramController.State.ALIVE) {
      return "RUNNING";
    } else if(state == ProgramController.State.ERROR) {
      return "FAILED";
    }
    return state.toString();
  }

  /**
   * Stops a Program
   *
   * @param token
   * @param identifier
   */
  @Override
  public RunIdentifier stop(AuthToken token, FlowIdentifier identifier)
    throws AppFabricServiceException, TException {
    ProgramRuntimeService.RuntimeInfo runtimeInfo = findRuntimeInfo(identifier);
    Preconditions.checkNotNull(runtimeInfo, "Unable to find runtime info for %s", identifier);

    try {
      ProgramController controller = runtimeInfo.getController();
      RunId runId = controller.getRunId();
      controller.stop().get();
      store.setStop(runtimeInfo.getProgramId(), runId.getId(), System.currentTimeMillis() /1000,
                    runtimeInfo.getController().getState().toString());
      return new RunIdentifier(runId.getId());
    } catch (Exception e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw Throwables.propagate(e);
    }
  }

  /**
   * Set number of instance of a flowlet.
   *
   * @param token
   * @param identifier
   * @param flowletId
   * @param instances
   */
  @Override
  public void setInstances(AuthToken token, FlowIdentifier identifier, String flowletId, short instances)
    throws AppFabricServiceException, TException {
    ProgramRuntimeService.RuntimeInfo runtimeInfo = findRuntimeInfo(identifier);
    Preconditions.checkNotNull(runtimeInfo, "Unable to find runtime info for %s", identifier);

    try {
      runtimeInfo.getController().command("instances", ImmutableMap.of(flowletId, (int) instances)).get();
    } catch (Exception e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw Throwables.propagate(e);
    }
  }

  /**
   * Returns the state of flows within a given account id.
   *
   * @param accountId
   */
  @Override
  public List<ActiveFlow> getFlows(String accountId) throws AppFabricServiceException, TException {

    try {
      Table<Type, Id.Program, RunRecord> histories = store.getAllRunHistory(Id.Account.from(accountId));
      List<ActiveFlow> result = Lists.newLinkedList();
      for (Table.Cell<Type, Id.Program, RunRecord> cell : histories.cellSet()) {
        Id.Program programId = cell.getColumnKey();
        RunRecord runRecord = cell.getValue();
        ActiveFlow activeFlow = new ActiveFlow(programId.getApplicationId(),
                                               programId.getId(),
                                               typeToEntityType(cell.getRowKey()),
                                               runRecord.getStopTs(),
                                               runRecord.getStartTs(),
                                               null,        // TODO
                                               0            // TODO
                                               );
          result.add(activeFlow);
      }
      return result;

    } catch (OperationException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw new AppFabricServiceException("Exception when getting all run histories: " + e.getMessage());
    }
  }

  private ProgramRuntimeService.RuntimeInfo findRuntimeInfo(FlowIdentifier identifier) {
    Collection<ProgramRuntimeService.RuntimeInfo> runtimeInfos = null;
    switch (identifier.getType()) {
      case FLOW:
        runtimeInfos = runtimeService.list(Type.FLOW).values();
        break;
      case QUERY:
        runtimeInfos = runtimeService.list(Type.PROCEDURE).values();
        break;
    }
    Preconditions.checkNotNull(runtimeInfos, "Cannot find any runtime info.");

    Id.Program programId = Id.Program.from(identifier.getAccountId(),
                                           identifier.getApplicationId(),
                                           identifier.getFlowId());

    for (ProgramRuntimeService.RuntimeInfo info : runtimeInfos) {
      if (programId.equals(info.getProgramId())) {
        return info;
      }
    }
    return null;
  }

  /**
   * Returns definition of a flow.
   *
   * @param id
   */
  @Override
  public String getFlowDefinition(FlowIdentifier id)
    throws AppFabricServiceException, TException {
    if(id.getType() == EntityType.FLOW) {
      FlowDefinitionImpl flowDef = getFlowDef(id);
      return new Gson().toJson(flowDef);
    } else if(id.getType() == EntityType.QUERY) {
      QueryDefinitionImpl queryDef = getQueryDefn(id);
      return new Gson().toJson(queryDef);
    }
    return null;
  }

  private QueryDefinitionImpl getQueryDefn(final FlowIdentifier identifier)
    throws AppFabricServiceException {
    ApplicationSpecification appSpec = null;
    try {
      appSpec = store.getApplication(new Id.Application(new Id.Account(identifier.getAccountId()),
                                                        identifier.getApplicationId()));
    } catch(OperationException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw  new AppFabricServiceException("Could not retrieve application spec for " +
                                           identifier.toString() + ", reason: " + e.getMessage());
    }

    ProcedureSpecification procedureSpec = appSpec.getProcedures().get(identifier.getFlowId());
    QueryDefinitionImpl queryDef = new QueryDefinitionImpl();

    // TODO: fill values (incl. list of datasets ) once they are added to ProcedureSpecification
    queryDef.setServiceName(procedureSpec.getName());
    return queryDef;
  }

  private FlowDefinitionImpl getFlowDef(final FlowIdentifier id)
    throws AppFabricServiceException {
    ApplicationSpecification appSpec = null;
    try {
      appSpec = store.getApplication(new Id.Application(new Id.Account(id.getAccountId()),
                                                        id.getApplicationId()));
    } catch(OperationException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw  new AppFabricServiceException("Could not retrieve application spec for " + id.toString() + "." +
                                             e.getMessage());
    }

    Preconditions.checkArgument(appSpec != null, "Not application specification found.");
    FlowSpecification flowSpec = appSpec.getFlows().get(id.getFlowId());
    FlowDefinitionImpl flowDef = new FlowDefinitionImpl();
    MetaDefinitionImpl metaDefinition = new MetaDefinitionImpl();
    metaDefinition.setApp(id.getApplicationId());
    metaDefinition.setName(flowSpec.getName());
    flowDef.setMeta(metaDefinition);
    fillFlowletsAndDataSets(flowSpec, flowDef);
    fillConnectionsAndStreams(id, flowSpec, flowDef);
    return flowDef;
  }

  private void fillConnectionsAndStreams(final FlowIdentifier id, final FlowSpecification spec,
                                         final FlowDefinitionImpl def) {
    List<ConnectionDefinitionImpl> connections = new ArrayList<ConnectionDefinitionImpl>();
    // we gather streams across all connections, hence we need to eliminate duplicate streams hence using map
    Map<String, FlowStreamDefinitionImpl> flowStreams = new HashMap<String, FlowStreamDefinitionImpl>();

    QueueSpecificationGenerator generator =
      new SimpleQueueSpecificationGenerator(new Id.Account(id.getAccountId()));
    Table<QueueSpecificationGenerator.Node, String, Set<QueueSpecification>> queues =  generator.create(spec);

    for (Table.Cell<QueueSpecificationGenerator.Node, String, Set<QueueSpecification>> conSet : queues.cellSet()) {
      for (QueueSpecification queueSpec : conSet.getValue()) {
        String srcName = conSet.getRowKey().getName();
        String destName = conSet.getColumnKey();
        FlowletStreamDefinitionImpl from;
        if (!spec.getFlowlets().containsKey(srcName)) {
          from =  new FlowletStreamDefinitionImpl(srcName);
          flowStreams.put(srcName, new FlowStreamDefinitionImpl(srcName, null));
        } else {
          from =  new FlowletStreamDefinitionImpl(srcName, queueSpec.getQueueName().getSimpleName() +
                                                  FlowletDefinition.OUTPUT_ENDPOINT_POSTFIX);
        }
        FlowletStreamDefinitionImpl to = new FlowletStreamDefinitionImpl(destName,
                                                                         queueSpec.getQueueName().getSimpleName() +
                                                                         FlowletDefinition.INPUT_ENDPOINT_POSTFIX);
        connections.add(new ConnectionDefinitionImpl(from, to));
      }
    }
    def.setConnections(connections);
    def.setFlowStreams(new ArrayList<FlowStreamDefinitionImpl>(flowStreams.values()));

    new StreamNamerImpl().name(id.getAccountId(), def);
  }

  private void fillFlowletsAndDataSets(final FlowSpecification flowSpec, final FlowDefinitionImpl flowDef) {
    Set<String> datasets = new HashSet<String>();
    List<FlowletDefinitionImpl> flowlets = new ArrayList<FlowletDefinitionImpl>();

    for (FlowletDefinition flowletSpec : flowSpec.getFlowlets().values()) {
      datasets.addAll(flowletSpec.getDatasets());

      FlowletDefinitionImpl flowletDef = new FlowletDefinitionImpl();
      flowletDef.setClassName(flowletSpec.getFlowletSpec().getClassName());
      if (flowletSpec.getInputs().isEmpty()) {
        flowletDef.setFlowletType(FlowletType.SOURCE);
      } else if (flowletSpec.getOutputs().isEmpty()) {
        flowletDef.setFlowletType(FlowletType.SINK);
      } else {
        flowletDef.setFlowletType(FlowletType.COMPUTE);
      }

      flowletDef.setInstances(flowletSpec.getInstances());
      flowletDef.setName(flowletSpec.getFlowletSpec().getName());

      flowlets.add(flowletDef);
    }

    flowDef.setFlowlets(flowlets);
    flowDef.setDatasets(datasets);
  }

  /**
   * Returns run information for a given flow id.
   *
   * @param id of the program.
   */
  @Override
  public List<FlowRunRecord> getFlowHistory(FlowIdentifier id) throws AppFabricServiceException, TException {
    List<RunRecord> log;
    Id.Program programId = Id.Program.from(id.getAccountId(), id.getApplicationId(), id.getFlowId());
    try {
      log = store.getRunHistory(programId);
    } catch(OperationException e) {
      throw  new AppFabricServiceException("Unable to retrieve application for " +
                                           id.toString() + e.getMessage());
    }
    List<FlowRunRecord> history = new ArrayList<FlowRunRecord>();
    for (RunRecord runRecord : log) {
      history.add(new FlowRunRecord(runRecord.getPid(), runRecord.getStartTs(),
                                    runRecord.getStopTs(),runRecord.getEndStatus())
      );
    }
    return history;
  }

  /**
   * Returns run information for a given flow id.
   *
   * @param id
   */
  @Override
  public void stopAll(String id) throws AppFabricServiceException, TException {
    // FIXME: Is id application id?
    List<ListenableFuture<?>> futures = Lists.newLinkedList();
    for (Type type : Type.values()) {
      for (Map.Entry<RunId, ProgramRuntimeService.RuntimeInfo> entry : runtimeService.list(type).entrySet()) {
        ProgramRuntimeService.RuntimeInfo runtimeInfo = entry.getValue();
        if (runtimeInfo.getProgramId().getApplicationId().equals(id)) {
          futures.add(runtimeInfo.getController().stop());
        }
      }
    }
    if (!futures.isEmpty()) {
      try {
        Futures.successfulAsList(futures).get();
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  /**
   * Initializes deployment of resources from the client.
   * <p>
   *   Upon receiving a request to initialize an upload with auth-token and resource information,
   *   we create a unique identifier for the upload and also create directories needed for storing
   *   the uploading archive. At this point the upload has not yet begun. The bytes of the archive
   *   are still on the client machine. An session id is returned back to client - which will use
   *   the session id provided to upload the chunks.
   * </p>
   * <p>
   *   <i>Note:</i> As the state of upload are transient they are not being persisted on the server.
   * </p>
   *
   * @param info ResourceInfo
   * @return ResourceIdentifier instance containing the resource id and
   * resource version.
   */
  @Override
  public ResourceIdentifier init(AuthToken token, ResourceInfo info) throws AppFabricServiceException {
    ResourceIdentifier identifier = new ResourceIdentifier( info.getAccountId(), "appId", "resourceId", 1);

    try {
      if(sessions.containsKey(info.getAccountId())) {
        throw new AppFabricServiceException("An upload is already in progress for this account.");
      }
      Location uploadDir = locationFactory.create(archiveDir + "/" + info.getAccountId());
      if(! uploadDir.exists() && ! uploadDir.mkdirs()) {
        LOG.warn("Unable to create directory '{}'", uploadDir.getName());
      }
      Location archive = uploadDir.append(info.getFilename());
      SessionInfo sessionInfo = new SessionInfo(identifier, info, archive, DeployStatus.REGISTERED);
      sessions.put(info.getAccountId(), sessionInfo);
      return identifier;
    } catch (IOException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      throw Throwables.propagate(e);
    }
  }

  /**
   * Writes chunk of data transmitted from the client. Along with data, there is the session id also
   * being returned.
   *
   * @param resource identifier.
   * @param chunk binary data of the resource transmitted from the client.
   * @throws AppFabricServiceException
   */
  @Override
  public void chunk(AuthToken token, ResourceIdentifier resource, ByteBuffer chunk) throws AppFabricServiceException {
    if(! sessions.containsKey(resource.getAccountId())) {
      throw new AppFabricServiceException("A session id has not been created for upload. Please call #init");
    }

    SessionInfo info = sessions.get(resource.getAccountId()).setStatus(DeployStatus.UPLOADING);
    try {
      OutputStream stream = info.getOutputStream();
      // Read the chunk from ByteBuffer and write it to file
      if(chunk != null) {
        byte[] buffer = new byte[chunk.remaining()];
        chunk.get(buffer);
        stream.write(buffer);
      } else {
        sessions.remove(resource.getAccountId());
        throw new AppFabricServiceException("Invalid chunk received.");
      }
    } catch (IOException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      sessions.remove(resource.getAccountId());
      throw new AppFabricServiceException("Failed to write archive chunk");
    }
  }

  /**
   * Finalizes the deployment of a archive. Once upload is completed, it will
   * start the pipeline responsible for verification and registration of archive resources.
   *
   * @param resource identifier to be finalized.
   */
  @Override
  public void deploy(AuthToken token, final ResourceIdentifier resource) throws AppFabricServiceException {
    if(!sessions.containsKey(resource.getAccountId())) {
      throw new AppFabricServiceException("No information about archive being uploaded is available.");
    }

    try {
      Id.Account id = Id.Account.from(resource.getAccountId());
      Location archiveLocation = sessions.get(resource.getAccountId()).getArchiveLocation();
      OutputStream stream = sessions.get(resource.getAccountId()).getOutputStream();
      try {
        sessions.get(resource.getAccountId()).setStatus(DeployStatus.VERIFYING);
        Manager<Location, ApplicationWithPrograms> manager
          = (Manager<Location, ApplicationWithPrograms>)managerFactory.create();
        ListenableFuture<ApplicationWithPrograms> future = manager.deploy(id, archiveLocation);
        Futures.addCallback(future, new FutureCallback<ApplicationWithPrograms>() {
          @Override
          public void onSuccess(ApplicationWithPrograms result) {
            save(sessions.get(resource.getAccountId()).setStatus(DeployStatus.DEPLOYED));
            sessions.remove(resource.getAccountId());
          }

          @Override
          public void onFailure(Throwable t) {
            LOG.warn(StackTraceUtil.toStringStackTrace(t));
            save(sessions.get(resource.getAccountId()).setStatus(DeployStatus.FAILED));
            sessions.remove(resource.getAccountId());
          }
        });
      } finally {
        stream.close();
      }
    } catch (Throwable e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      save(sessions.get(resource.getAccountId()).setStatus(DeployStatus.FAILED));
      sessions.remove(resource.getAccountId());
      throw new AppFabricServiceException(e.getMessage());
    }
  }

  /**
   * Returns status of deployment of archive.
   *
   * @param resource identifier
   * @return status of resource processing.
   * @throws AppFabricServiceException
   */
  @Override
  public DeploymentStatus dstatus(AuthToken token, ResourceIdentifier resource) throws AppFabricServiceException {
    if(!sessions.containsKey(resource.getAccountId())) {
      SessionInfo info = retrieve(resource.getAccountId());
      DeploymentStatus status = new DeploymentStatus(info.getStatus().getCode(),
                                                     info.getStatus().getMessage(), null);
      return status;
    } else {
      SessionInfo info = sessions.get(resource.getAccountId());
      DeploymentStatus status = new DeploymentStatus(info.getStatus().getCode(),
                                                     info.getStatus().getMessage(), null);
      return status;
    }
  }

  /**
   * Promotes a FAR from single node to cloud.
   *
   * @param id of the flow.
   * @return true if successful; false otherwise.
   * @throws AppFabricServiceException
   */
  @Override
  public boolean promote(AuthToken authToken, ResourceIdentifier id, String hostname)
    throws AppFabricServiceException {
    Preconditions.checkArgument(authToken.isSetToken(), "API key is not set");
    Preconditions.checkArgument(!hostname.isEmpty(), "Empty hostname passed.");

    final Location applicationDir = locationFactory.create(archiveDir + "/" + id.getAccountId()
                                                             + "/" + id.getApplicationId() + ".jar");
    try {
      if(! applicationDir.exists()) {
        throw new AppFabricServiceException("Unable to locate the application.");
      }

      String schema = "https";
      if("localhost".equals(hostname)) {
        schema = "http";
      }

      int port = configuration.getInt("connector.appfabric.port", 10007);
      String url = String.format("%s://%s:%d/app", schema, hostname, port);
      SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
        .setUrl(url)
        .setRequestTimeoutInMs(UPLOAD_TIMEOUT)
        .setHeader("X-Archive-Name", applicationDir.getName())
        .setHeader("X-Continuuity-ApiKey", authToken.getToken())
        .build();

      try {
        Future<Response> future = client.put(new LocationBodyGenerator(applicationDir));
        Response response = future.get(UPLOAD_TIMEOUT, TimeUnit.MILLISECONDS);
        if(response.getStatusCode() != 200) {
          throw new RuntimeException(response.getResponseBody());
        }
        return true;
      } finally {
        client.close();
      }
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private static final class LocationBodyGenerator implements BodyGenerator {

    private final Location location;

    private LocationBodyGenerator(Location location) {
      this.location = location;
    }

    @Override
    public Body createBody() throws IOException {
      final InputStream input = location.getInputStream();

      return new Body() {
        @Override
        public long getContentLength() {
          try {
            return location.length();
          } catch (IOException e) {
            throw Throwables.propagate(e);
          }
        }

        @Override
        public long read(ByteBuffer buffer) throws IOException {
          // Fast path
          if (buffer.hasArray()) {
            int len = input.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
            if (len > 0) {
              buffer.position(buffer.position() + len);
            }
            return len;
          }

          byte[] bytes = new byte[buffer.remaining()];
          int len = input.read(bytes);
          if (len < 0) {
            return len;
          }
          buffer.put(bytes, 0, len);
          return len;
        }

        @Override
        public void close() throws IOException {
          input.close();
        }
      };
    }
  }

  /**
   * Deletes a flow specified by {@code FlowIdentifier}.
   *
   * @param identifier of a flow.
   * @throws AppFabricServiceException when there is an issue deactivating the flow.
   */
  @Override
  public void remove(AuthToken token, FlowIdentifier identifier) throws AppFabricServiceException {
    Preconditions.checkNotNull(token);
  }

  @Override
  public void removeAll(AuthToken token, String account) throws AppFabricServiceException {
    Preconditions.checkNotNull(token);
  }

  @Override
  public void reset(AuthToken token, String account) throws AppFabricServiceException {
    Preconditions.checkNotNull(account);

    deleteMetrics(account);
    // delete all meta data
    try {
      mds.deleteAll(account);
    } catch (Exception e) {
      String message = String.format("Error deleting all meta data for " +
                                       "account '%s': %s. At %s", account, e.getMessage(),
                                     StackTraceUtil.toStringStackTrace(e));
      LOG.error(message, e);
      throw new AppFabricServiceException(message);
    }

    // wipe the data fabric
    try {
      LOG.info("Deleting all data for account '" + account + "'.");
      opex.execute(
                    new OperationContext(account),
                    new ClearFabric(ClearFabric.ToClear.ALL)
      );
      LOG.info("All data for account '" + account + "' deleted.");
    } catch (Exception e) {
      String message = String.format("Error deleting the data for " +
                                       "account '%s': %s. At %s", account, e.getMessage(),
                                     StackTraceUtil.toStringStackTrace(e));
      LOG.error(message, e);
      throw new AppFabricServiceException(message);
    }
  }

  /**
   * Deletes metrics for a given account.
   *
   * @param account for which the metrics need to be reset.
   * @throws AppFabricServiceException throw due to issue in reseting metrics for
   * account.
   */
  private void deleteMetrics(String account) throws AppFabricServiceException {
    try {
      LOG.info("Deleting all metrics for account '" + account + "'.");
      MetricsFrontendServiceImpl mfs =
        new MetricsFrontendServiceImpl(configuration);
      mfs.reset(account);
      LOG.info("All metrics for account '" + account + "'deleted.");
    } catch (Exception e) {
      String message = String.format("Error clearing the metrics for " +
                                       "account '%s': %s. At %s", account, e.getMessage(),
                                     StackTraceUtil.toStringStackTrace(e));
      LOG.error(message, e);
      throw new AppFabricServiceException(message);
    }
  }

  /**
   * Saves the {@link SessionInfo} to the filesystem.
   *
   * @param info to be saved.
   * @return true if and only if successful; false otherwise.
   */
  private boolean save(SessionInfo info) {
    try {
      Gson gson = new GsonBuilder().registerTypeAdapter(Location.class, new LocationCodec(locationFactory)).create();
      String accountId = info.getResourceIdenitifier().getAccountId();
      Location outputDir = locationFactory.create(archiveDir + "/" + accountId);
      if(! outputDir.exists()) {
        return false;
      }
      final Location sessionInfoFile = outputDir.append("session.json");
      OutputSupplier<Writer> writer = new OutputSupplier<Writer>() {
        @Override
        public Writer getOutput() throws IOException {
          return new OutputStreamWriter(sessionInfoFile.getOutputStream(), "UTF-8");
        }
      };

      Writer w = writer.getOutput();
      try {
        gson.toJson(info, w);
      } finally {
        Closeables.closeQuietly(w);
      }
    } catch (IOException e) {
      LOG.warn(StackTraceUtil.toStringStackTrace(e));
      return false;
    }
    return true;
  }

  /**
   * Retrieves a {@link SessionInfo} from the file system.
   *
   * @param accountId to which the
   * @return
   */
  @Nullable
  private SessionInfo retrieve(String accountId) {
    try {
      final Location outputDir = locationFactory.create(archiveDir + "/" + accountId);
      if(! outputDir.exists()) {
        return null;
      }
      final Location sessionInfoFile = outputDir.append("session.json");
      InputSupplier<Reader> reader = new InputSupplier<Reader>() {
        @Override
        public Reader getInput() throws IOException {
          return new InputStreamReader(sessionInfoFile.getInputStream(), "UTF-8");
        }
      };

      Gson gson = new GsonBuilder().registerTypeAdapter(Location.class, new LocationCodec(locationFactory)).create();
      Reader r = reader.getInput();
      try {
        SessionInfo info = gson.fromJson(r, SessionInfo.class);
        return info;
      } finally {
        Closeables.closeQuietly(r);
      }
    } catch (IOException e) {
      LOG.warn("Failed to retrieve session info for account.");
    }
    return null;
  }
}
