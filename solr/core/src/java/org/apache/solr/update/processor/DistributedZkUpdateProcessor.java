/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.update.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.cloud.CloudDescriptor;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZooKeeperException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.DeleteUpdateCommand;
import org.apache.solr.update.SolrCmdDistributor;
import org.apache.solr.update.UpdateCommand;

import static org.apache.solr.update.processor.DistributingUpdateProcessorFactory.DISTRIB_UPDATE_PARAM;

public class DistributedZkUpdateProcessor extends DistributedUpdateProcessor {

  private final CloudDescriptor cloudDesc;
  private final ZkController zkController;

  public DistributedZkUpdateProcessor(SolrQueryRequest req,
                                      SolrQueryResponse rsp, UpdateRequestProcessor next) {
    super(req, rsp, next);
    cloudDesc = req.getCore().getCoreDescriptor().getCloudDescriptor();
    zkController = req.getCore().getCoreContainer().getZkController();
  }

  public DistributedZkUpdateProcessor(SolrQueryRequest req,
                                      SolrQueryResponse rsp, AtomicUpdateDocumentMerger docMerger,
                                      UpdateRequestProcessor next) {
    super(req, rsp, docMerger, next);
    cloudDesc = req.getCore().getCoreDescriptor().getCloudDescriptor();
    zkController = req.getCore().getCoreContainer().getZkController();

  }

  @Override
  String getCollectionName(CloudDescriptor cloudDesc) {
    return cloudDesc.getCollectionName();
  }

  @Override
  Replica.Type getReplicaType(CloudDescriptor cloudDesc) {
    return cloudDesc.getReplicaType();
  }

  @Override
  public void doDeleteByQuery(DeleteUpdateCommand cmd) throws IOException {
    // even in non zk mode, tests simulate updates from a leader
    zkCheck();


    // NONE: we are the first to receive this deleteByQuery
    //       - it must be forwarded to the leader of every shard
    // TO:   we are a leader receiving a forwarded deleteByQuery... we must:
    //       - block all updates (use VersionInfo)
    //       - flush *all* updates going to our replicas
    //       - forward the DBQ to our replicas and wait for the response
    //       - log + execute the local DBQ
    // FROM: we are a replica receiving a DBQ from our leader
    //       - log + execute the local DBQ
    DistribPhase phase = DistribPhase.parseParam(req.getParams().get(DISTRIB_UPDATE_PARAM));

    DocCollection coll = zkController.getClusterState().getCollection(collection);

    if (DistribPhase.NONE == phase) {
      if (rollupReplicationTracker == null) {
        rollupReplicationTracker = new RollupRequestReplicationTracker();
      }
      boolean leaderForAnyShard = false;  // start off by assuming we are not a leader for any shard

      ModifiableSolrParams outParams = new ModifiableSolrParams(filterParams(req.getParams()));
      outParams.set(DISTRIB_UPDATE_PARAM, DistribPhase.TOLEADER.toString());
      outParams.set(DISTRIB_FROM, ZkCoreNodeProps.getCoreUrl(
          zkController.getBaseUrl(), req.getCore().getName()));

      SolrParams params = req.getParams();
      String route = params.get(ShardParams._ROUTE_);
      Collection<Slice> slices = coll.getRouter().getSearchSlices(route, params, coll);

      List<SolrCmdDistributor.Node> leaders =  new ArrayList<>(slices.size());
      for (Slice slice : slices) {
        String sliceName = slice.getName();
        Replica leader;
        try {
          leader = zkController.getZkStateReader().getLeaderRetry(collection, sliceName);
        } catch (InterruptedException e) {
          throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, "Exception finding leader for shard " + sliceName, e);
        }

        // TODO: What if leaders changed in the meantime?
        // should we send out slice-at-a-time and if a node returns "hey, I'm not a leader" (or we get an error because it went down) then look up the new leader?

        // Am I the leader for this slice?
        ZkCoreNodeProps coreLeaderProps = new ZkCoreNodeProps(leader);
        String leaderCoreNodeName = leader.getName();
        String coreNodeName = cloudDesc.getCoreNodeName();
        isLeader = coreNodeName.equals(leaderCoreNodeName);

        if (isLeader) {
          // don't forward to ourself
          leaderForAnyShard = true;
        } else {
          leaders.add(new SolrCmdDistributor.ForwardNode(coreLeaderProps, zkController.getZkStateReader(), collection, sliceName, maxRetriesOnForward));
        }
      }

      outParams.remove("commit"); // this will be distributed from the local commit


      if (params.get(UpdateRequest.MIN_REPFACT) != null) {
        // TODO: Kept this for rolling upgrades. Remove in Solr 9
        outParams.add(UpdateRequest.MIN_REPFACT, req.getParams().get(UpdateRequest.MIN_REPFACT));
      }
      cmdDistrib.distribDelete(cmd, leaders, outParams, false, rollupReplicationTracker, null);

      if (!leaderForAnyShard) {
        return;
      }

      // change the phase to TOLEADER so we look up and forward to our own replicas (if any)
      phase = DistribPhase.TOLEADER;
    }
    List<SolrCmdDistributor.Node> replicas = null;

    if (DistribPhase.TOLEADER == phase) {
      // This core should be a leader
      isLeader = true;
      replicas = setupRequestForDBQ();
    } else if (DistribPhase.FROMLEADER == phase) {
      isLeader = false;
    }

    // check if client has requested minimum replication factor information. will set replicationTracker to null if
    // we aren't the leader or subShardLeader
    checkReplicationTracker(cmd);
    super.doDeleteByQuery(cmd, replicas, coll);
  }

  // used for deleteByQuery to get the list of nodes this leader should forward to
  private List<SolrCmdDistributor.Node> setupRequestForDBQ() {
    List<SolrCmdDistributor.Node> nodes = null;
    String shardId = cloudDesc.getShardId();

    try {
      Replica leaderReplica = zkController.getZkStateReader().getLeaderRetry(collection, shardId);
      isLeader = leaderReplica.getName().equals(cloudDesc.getCoreNodeName());

      // TODO: what if we are no longer the leader?

      forwardToLeader = false;
      List<ZkCoreNodeProps> replicaProps = zkController.getZkStateReader()
          .getReplicaProps(collection, shardId, leaderReplica.getName(), null, Replica.State.DOWN, EnumSet.of(Replica.Type.NRT, Replica.Type.TLOG));
      if (replicaProps != null) {
        nodes = new ArrayList<>(replicaProps.size());
        for (ZkCoreNodeProps props : replicaProps) {
          nodes.add(new SolrCmdDistributor.StdNode(props, collection, shardId));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    }

    return nodes;
  }

  @Override
  protected String getLeaderUrl(String id) {
    // try get leader from req params, fallback to zk lookup if not found.
    String distribFrom = req.getParams().get(DISTRIB_FROM);
    if(distribFrom != null) {
      return distribFrom;
    }
    return getLeaderUrlZk(id);
  }

  private String getLeaderUrlZk(String id) {
    // An update we're dependent upon didn't arrive! This is unexpected. Perhaps likely our leader is
    // down or partitioned from us for some reason. Lets force refresh cluster state, and request the
    // leader for the update.
    if (zkController == null) { // we should be in cloud mode, but wtf? could be a unit test
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Can't find document with id=" + id + ", but fetching from leader "
          + "failed since we're not in cloud mode.");
    }
    try {
      return zkController.getZkStateReader().getLeaderRetry(collection, cloudDesc.getShardId()).getCoreUrl();
    } catch (InterruptedException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Exception during fetching from leader.", e);
    }
  }

  @Override
  boolean isLeader(UpdateCommand cmd) {
    zkCheck();
    if (cmd instanceof AddUpdateCommand) {
      AddUpdateCommand acmd = (AddUpdateCommand)cmd;
      nodes = setupRequest(acmd.getHashableId(), acmd.getSolrInputDocument());
    } else if (cmd instanceof DeleteUpdateCommand) {
      DeleteUpdateCommand dcmd = (DeleteUpdateCommand)cmd;
      nodes = setupRequest(dcmd.getId(), null);
    }
    return isLeader;
  }
}
