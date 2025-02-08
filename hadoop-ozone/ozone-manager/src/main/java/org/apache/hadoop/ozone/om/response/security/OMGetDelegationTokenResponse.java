/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.response.security;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.response.CleanupTableInfo;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.security.OzoneTokenIdentifier;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.Table;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.IOException;

import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.DELEGATION_TOKEN_TABLE;

/**
 * Handle response for GetDelegationToken request.
 */
@CleanupTableInfo(cleanupTables = {DELEGATION_TOKEN_TABLE})
public class OMGetDelegationTokenResponse extends OMClientResponse {
  private OzoneTokenIdentifier ozoneTokenIdentifier;
  private final long renewTime;

  public OMGetDelegationTokenResponse(
      @Nullable OzoneTokenIdentifier ozoneTokenIdentifier,
      long renewTime, @Nonnull OMResponse omResponse) {
    super(omResponse);
    this.ozoneTokenIdentifier = ozoneTokenIdentifier;
    this.renewTime = renewTime;
  }

  @Override
  public void addToDBBatch(OMMetadataManager omMetadataManager,
      BatchOperation batchOperation) throws IOException {
    Table table = omMetadataManager.getDelegationTokenTable();
    if (ozoneTokenIdentifier != null &&
        getOMResponse().getStatus() == OzoneManagerProtocolProtos.Status.OK) {
      table.putWithBatch(batchOperation, ozoneTokenIdentifier, renewTime);
    }
  }

}

