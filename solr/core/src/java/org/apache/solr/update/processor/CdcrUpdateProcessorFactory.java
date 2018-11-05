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

import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

/**
 * Factory for {@link org.apache.solr.update.processor.CdcrUpdateProcessor}.
 *
 * @see org.apache.solr.update.processor.CdcrUpdateProcessor
 * @since 6.0.0
 */
public class CdcrUpdateProcessorFactory
    extends UpdateRequestProcessorFactory
    implements DistributingUpdateProcessorFactory {

  @Override
  public void init(NamedList args) {

  }

  @Override
  public CdcrZookeeperZkUpdateProcessor getInstance(SolrQueryRequest req,
                                         SolrQueryResponse rsp, UpdateRequestProcessor next) {
    CoreContainer cc = req.getCore().getCoreContainer();
    if(!cc.isZooKeeperAware()) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "CDCR cannot be instantiated if zookeeper is not enabled");
    }
    return new CdcrZookeeperZkUpdateProcessor(req, rsp, cc, next);
  }

}

