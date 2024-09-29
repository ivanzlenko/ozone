/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hdds.scm.storage;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import org.apache.hadoop.hdds.annotation.InterfaceStability;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.*;
import org.apache.hadoop.hdds.scm.XceiverClientReply;
import org.apache.hadoop.hdds.scm.XceiverClientSpi;
import org.apache.hadoop.hdds.scm.XceiverClientSpi.Validator;
import org.apache.hadoop.hdds.scm.container.common.helpers.BlockNotCommittedException;
import org.apache.hadoop.hdds.scm.container.common.helpers.ContainerNotOpenException;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.security.token.OzoneBlockTokenIdentifier;
import org.apache.hadoop.hdds.tracing.TracingUtil;
import org.apache.hadoop.ozone.common.Checksum;
import org.apache.hadoop.ozone.common.ChecksumData;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.function.CheckedFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static java.util.Collections.singletonList;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.BLOCK_TOKEN_VERIFICATION_FAILED;

/**
 * Implementation of all container protocol calls performed by Container
 * clients.
 */
public final class ContainerProtocolCalls  {
  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerProtocolCalls.class);

  /**
   * There is no need to instantiate this class.
   */
  private ContainerProtocolCalls() {
  }

  static <T> T tryEachDatanode(Pipeline pipeline,
      CheckedFunction<DatanodeDetails, T, IOException> op,
      Function<DatanodeDetails, String> toErrorMessage)
      throws IOException {
    final Set<DatanodeDetails> excluded = new HashSet<>();
    for (; ;) {
      final DatanodeDetails d = pipeline.getClosestNode(excluded);

      try {
        return op.apply(d);
      } catch (IOException e) {
        Span span = GlobalTracer.get().activeSpan();
        if (e instanceof StorageContainerException) {
          StorageContainerException sce = (StorageContainerException)e;
          // Block token expired. There's no point retrying other DN.
          // Throw the exception to request a new block token right away.
          if (sce.getResult() == BLOCK_TOKEN_VERIFICATION_FAILED) {
            span.log("block token verification failed at DN " + d);
            throw e;
          }
        }
        span.log("failed to connect to DN " + d);
        excluded.add(d);
        if (excluded.size() < pipeline.size()) {
          LOG.warn(toErrorMessage.apply(d)
              + "; will try another datanode.", e);
        } else {
          throw e;
        }
      }
    }
  }

  public static ContainerCommandRequestProto getPutBlockRequest(
      Pipeline pipeline, BlockData containerBlockData, boolean eof,
      String tokenString) throws IOException {
    PutBlockRequestProto.Builder createBlockRequest =
        PutBlockRequestProto.newBuilder()
            .setBlockData(containerBlockData)
            .setEof(eof);
    final String id = pipeline.getFirstNode().getUuidString();
    ContainerCommandRequestProto.Builder builder =
        ContainerCommandRequestProto.newBuilder().setCmdType(Type.PutBlock)
            .setContainerID(containerBlockData.getBlockID().getContainerID())
            .setDatanodeUuid(id)
            .setPutBlock(createBlockRequest);
    if (tokenString != null) {
      builder.setEncodedToken(tokenString);
    }
    return builder.build();
  }

  /**
   * Calls the container protocol to read a chunk.
   *
   * @param xceiverClient client to perform call
   * @param chunk information about chunk to read
   * @param blockID ID of the block
   * @param validators functions to validate the response
   * @param token a token for this block (may be null)
   * @return container protocol read chunk response
   * @throws IOException if there is an I/O error while performing the call
   */
  public static ContainerProtos.ReadChunkResponseProto readChunk(
      XceiverClientSpi xceiverClient, ChunkInfo chunk, DatanodeBlockID blockID,
      List<Validator> validators,
      Token<? extends TokenIdentifier> token) throws IOException {
    ReadChunkRequestProto.Builder readChunkRequest =
        ReadChunkRequestProto.newBuilder()
            .setBlockID(blockID)
            .setChunkData(chunk)
            .setReadChunkVersion(ContainerProtos.ReadChunkVersion.V1);
    ContainerCommandRequestProto.Builder builder =
        ContainerCommandRequestProto.newBuilder().setCmdType(Type.ReadChunk)
            .setContainerID(blockID.getContainerID())
            .setReadChunk(readChunkRequest);
    if (token != null) {
      builder.setEncodedToken(token.encodeToUrlString());
    }

    Span span = GlobalTracer.get()
        .buildSpan("readChunk").start();
    try (Scope ignored = GlobalTracer.get().activateSpan(span)) {
      span.setTag("offset", chunk.getOffset())
          .setTag("length", chunk.getLen())
          .setTag("block", blockID.toString());
      return tryEachDatanode(xceiverClient.getPipeline(),
          d -> readChunk(xceiverClient, chunk, blockID,
              validators, builder, d),
          d -> toErrorMessage(chunk, blockID, d));
    } finally {
      span.finish();
    }
  }

  private static ContainerProtos.ReadChunkResponseProto readChunk(
      XceiverClientSpi xceiverClient, ChunkInfo chunk, DatanodeBlockID blockID,
      List<Validator> validators,
      ContainerCommandRequestProto.Builder builder,
      DatanodeDetails d) throws IOException {
    ContainerCommandRequestProto.Builder requestBuilder = builder
        .setDatanodeUuid(d.getUuidString());
    Span span = GlobalTracer.get().activeSpan();
    String traceId = TracingUtil.exportSpan(span);
    if (traceId != null) {
      requestBuilder = requestBuilder.setTraceID(traceId);
    }
    ContainerCommandResponseProto reply =
        xceiverClient.sendCommand(requestBuilder.build(), validators);
    final ReadChunkResponseProto response = reply.getReadChunk();
    final long readLen = getLen(response);
    if (readLen != chunk.getLen()) {
      throw new IOException(toErrorMessage(chunk, blockID, d)
          + ": readLen=" + readLen);
    }
    return response;
  }

  static String toErrorMessage(ChunkInfo chunk, DatanodeBlockID blockId,
      DatanodeDetails d) {
    return String.format("Failed to read chunk %s (len=%s) %s from %s",
        chunk.getChunkName(), chunk.getLen(), blockId, d);
  }

  static long getLen(ReadChunkResponseProto response) {
    if (response.hasData()) {
      return response.getData().size();
    } else if (response.hasDataBuffers()) {
      return response.getDataBuffers() .getBuffersList().stream()
          .mapToLong(ByteString::size).sum();
    } else {
      return -1;
    }
  }

  /**
   * Calls the container protocol to write a chunk.
   *
   * @param xceiverClient client to perform call
   * @param chunk information about chunk to write
   * @param blockID ID of the block
   * @param data the data of the chunk to write
   * @param tokenString serialized block token
   * @throws IOException if there is an I/O error while performing the call
   */
  @SuppressWarnings("parameternumber")
  public static XceiverClientReply writeChunkAsync(
      XceiverClientSpi xceiverClient, ChunkInfo chunk, BlockID blockID,
      ByteString data, String tokenString,
      int replicationIndex, BlockData blockData, boolean close)
      throws IOException, ExecutionException, InterruptedException {

    WriteChunkRequestProto.Builder writeChunkRequest =
        WriteChunkRequestProto.newBuilder()
            .setBlockID(DatanodeBlockID.newBuilder()
                .setContainerID(blockID.getContainerID())
                .setLocalID(blockID.getLocalID())
                .setBlockCommitSequenceId(blockID.getBlockCommitSequenceId())
                .setReplicaIndex(replicationIndex)
                .build())
            .setChunkData(chunk)
            .setData(data);
    if (blockData != null) {
      PutBlockRequestProto.Builder createBlockRequest =
          PutBlockRequestProto.newBuilder()
              .setBlockData(blockData)
              .setEof(close);
      writeChunkRequest.setBlock(createBlockRequest);
    }
    String id = xceiverClient.getPipeline().getFirstNode().getUuidString();
    ContainerCommandRequestProto.Builder builder =
        ContainerCommandRequestProto.newBuilder()
            .setCmdType(Type.WriteChunk)
            .setContainerID(blockID.getContainerID())
            .setDatanodeUuid(id)
            .setWriteChunk(writeChunkRequest);

    if (tokenString != null) {
      builder.setEncodedToken(tokenString);
    }
    ContainerCommandRequestProto request = builder.build();
    return xceiverClient.sendCommandAsync(request);
  }

  /**
   * Allows writing a small file using single RPC. This takes the container
   * name, block name and data to write sends all that data to the container
   * using a single RPC. This API is designed to be used for files which are
   * smaller than 1 MB.
   *
   * @param client - client that communicates with the container.
   * @param blockID - ID of the block
   * @param data - Data to be written into the container.
   * @param token a token for this block (may be null)
   * @return container protocol writeSmallFile response
   */
  public static PutSmallFileResponseProto writeSmallFile(
      XceiverClientSpi client, BlockID blockID, byte[] data,
      Token<OzoneBlockTokenIdentifier> token) throws IOException {

    BlockData containerBlockData =
        BlockData.newBuilder().setBlockID(blockID.getDatanodeBlockIDProtobuf())
            .build();
    PutBlockRequestProto.Builder createBlockRequest =
        PutBlockRequestProto.newBuilder()
            .setBlockData(containerBlockData);

    KeyValue keyValue =
        KeyValue.newBuilder().setKey("OverWriteRequested").setValue("true")
            .build();

    Checksum checksum = new Checksum(ChecksumType.CRC32, 256);
    final ChecksumData checksumData = checksum.computeChecksum(data);
    ChunkInfo chunk =
        ChunkInfo.newBuilder()
            .setChunkName(blockID.getLocalID() + "_chunk")
            .setOffset(0)
            .setLen(data.length)
            .addMetadata(keyValue)
            .setChecksumData(checksumData.getProtoBufMessage())
            .build();

    PutSmallFileRequestProto putSmallFileRequest =
        PutSmallFileRequestProto.newBuilder().setChunkInfo(chunk)
            .setBlock(createBlockRequest).setData(ByteString.copyFrom(data))
            .build();

    String id = client.getPipeline().getFirstNode().getUuidString();
    ContainerCommandRequestProto.Builder builder =
        ContainerCommandRequestProto.newBuilder()
            .setCmdType(Type.PutSmallFile)
            .setContainerID(blockID.getContainerID())
            .setDatanodeUuid(id)
            .setPutSmallFile(putSmallFileRequest);
    if (token != null) {
      builder.setEncodedToken(token.encodeToUrlString());
    }
    ContainerCommandRequestProto request = builder.build();
    ContainerCommandResponseProto response =
        client.sendCommand(request, getValidatorList());
    return response.getPutSmallFile();
  }

  /**
   * createRecoveringContainer call that creates a container on the datanode.
   * Currently this is used for EC reconstruction containers. When EC
   * reconstruction coordinator reconstructing the containers, the in progress
   * containers would be created as "RECOVERING" state containers.
   * @param client  - client
   * @param containerID - ID of container
   * @param encodedToken - encodedToken if security is enabled
   * @param replicaIndex - index position of the container replica
   */
  @InterfaceStability.Evolving
  public static void createRecoveringContainer(XceiverClientSpi client,
      long containerID, String encodedToken, int replicaIndex)
      throws IOException {
    createContainer(client, containerID, encodedToken,
        ContainerProtos.ContainerDataProto.State.RECOVERING, replicaIndex);
  }

  /**
   * createContainer call that creates a container on the datanode.
   * @param client  - client
   * @param containerID - ID of container
   * @param encodedToken - encodedToken if security is enabled
   */
  public static void createContainer(XceiverClientSpi client, long containerID,
      String encodedToken) throws IOException {
    createContainer(client, containerID, encodedToken, null, 0);
  }
  /**
   * createContainer call that creates a container on the datanode.
   * @param client  - client
   * @param containerID - ID of container
   * @param encodedToken - encodedToken if security is enabled
   * @param state - state of the container
   * @param replicaIndex - index position of the container replica
   */
  public static void createContainer(XceiverClientSpi client,
      long containerID, String encodedToken,
      ContainerProtos.ContainerDataProto.State state, int replicaIndex)
      throws IOException {
    ContainerProtos.CreateContainerRequestProto.Builder createRequest =
        ContainerProtos.CreateContainerRequestProto.newBuilder();
    createRequest
        .setContainerType(ContainerProtos.ContainerType.KeyValueContainer);
    if (state != null) {
      createRequest.setState(state);
    }
    if (replicaIndex > 0) {
      createRequest.setReplicaIndex(replicaIndex);
    }

    String id = client.getPipeline().getFirstNode().getUuidString();
    ContainerCommandRequestProto.Builder request =
        ContainerCommandRequestProto.newBuilder();
    if (encodedToken != null) {
      request.setEncodedToken(encodedToken);
    }
    String traceId = TracingUtil.exportCurrentSpan();
    if (traceId != null) {
      request.setTraceID(traceId);
    }

    request.setCmdType(ContainerProtos.Type.CreateContainer);
    request.setContainerID(containerID);
    request.setCreateContainer(createRequest.build());
    request.setDatanodeUuid(id);
    client.sendCommand(request.build(), getValidatorList());
  }

  /**
   * Deletes a container from a pipeline.
   *
   * @param force whether or not to forcibly delete the container.
   * @param encodedToken - encodedToken if security is enabled
   */
  public static void deleteContainer(XceiverClientSpi client, long containerID,
      boolean force, String encodedToken) throws IOException {
    ContainerProtos.DeleteContainerRequestProto.Builder deleteRequest =
        ContainerProtos.DeleteContainerRequestProto.newBuilder();
    deleteRequest.setForceDelete(force);
    String id = client.getPipeline().getFirstNode().getUuidString();

    ContainerCommandRequestProto.Builder request =
        ContainerCommandRequestProto.newBuilder();
    request.setCmdType(ContainerProtos.Type.DeleteContainer);
    request.setContainerID(containerID);
    request.setDeleteContainer(deleteRequest);
    request.setDatanodeUuid(id);
    if (encodedToken != null) {
      request.setEncodedToken(encodedToken);
    }
    String traceId = TracingUtil.exportCurrentSpan();
    if (traceId != null) {
      request.setTraceID(traceId);
    }
    client.sendCommand(request.build(), getValidatorList());
  }

  /**
   * Close a container.
   *
   * @param encodedToken - encodedToken if security is enabled
   */
  public static void closeContainer(XceiverClientSpi client,
      long containerID, String encodedToken)
      throws IOException {
    String id = client.getPipeline().getFirstNode().getUuidString();

    ContainerCommandRequestProto.Builder request =
        ContainerCommandRequestProto.newBuilder();
    request.setCmdType(Type.CloseContainer);
    request.setContainerID(containerID);
    request.setCloseContainer(CloseContainerRequestProto.getDefaultInstance());
    request.setDatanodeUuid(id);
    if (encodedToken != null) {
      request.setEncodedToken(encodedToken);
    }
    String traceId = TracingUtil.exportCurrentSpan();
    if (traceId != null) {
      request.setTraceID(traceId);
    }
    client.sendCommand(request.build(), getValidatorList());
  }

  /**
   * readContainer call that gets meta data from an existing container.
   *
   * @param client       - client
   * @param encodedToken - encodedToken if security is enabled
   */
  public static ReadContainerResponseProto readContainer(
      XceiverClientSpi client, long containerID, String encodedToken)
      throws IOException {
    String id = client.getPipeline().getFirstNode().getUuidString();

    ContainerCommandRequestProto.Builder request =
        ContainerCommandRequestProto.newBuilder();
    request.setCmdType(Type.ReadContainer);
    request.setContainerID(containerID);
    request.setReadContainer(ReadContainerRequestProto.getDefaultInstance());
    request.setDatanodeUuid(id);
    if (encodedToken != null) {
      request.setEncodedToken(encodedToken);
    }
    String traceId = TracingUtil.exportCurrentSpan();
    if (traceId != null) {
      request.setTraceID(traceId);
    }
    ContainerCommandResponseProto response =
        client.sendCommand(request.build(), getValidatorList());

    return response.getReadContainer();
  }

  /**
   * Reads the data given the blockID.
   *
   * @param blockID - ID of the block
   * @param token a token for this block (may be null)
   * @return GetSmallFileResponseProto
   */
  public static GetSmallFileResponseProto readSmallFile(XceiverClientSpi client,
      BlockID blockID,
      Token<OzoneBlockTokenIdentifier> token) throws IOException {
    GetBlockRequestProto.Builder getBlock = GetBlockRequestProto
        .newBuilder()
        .setBlockID(blockID.getDatanodeBlockIDProtobuf());
    ContainerProtos.GetSmallFileRequestProto getSmallFileRequest =
        GetSmallFileRequestProto
            .newBuilder().setBlock(getBlock)
            .setReadChunkVersion(ContainerProtos.ReadChunkVersion.V1)
            .build();
    String id = client.getPipeline().getClosestNode().getUuidString();

    ContainerCommandRequestProto.Builder builder = ContainerCommandRequestProto
        .newBuilder()
        .setCmdType(Type.GetSmallFile)
        .setContainerID(blockID.getContainerID())
        .setDatanodeUuid(id)
        .setGetSmallFile(getSmallFileRequest);
    if (token != null) {
      builder.setEncodedToken(token.encodeToUrlString());
    }
    String traceId = TracingUtil.exportCurrentSpan();
    if (traceId != null) {
      builder.setTraceID(traceId);
    }
    ContainerCommandRequestProto request = builder.build();
    ContainerCommandResponseProto response =
        client.sendCommand(request, getValidatorList());
    return response.getGetSmallFile();
  }

  /**
   * Send an echo to DataNode.
   *
   * @return EchoResponseProto
   */
  public static EchoResponseProto echo(XceiverClientSpi client, String encodedContainerID,
      long containerID, ByteString payloadReqBytes, int payloadRespSizeKB, int sleepTimeMs, boolean readOnly)
      throws IOException {
    ContainerProtos.EchoRequestProto getEcho =
        EchoRequestProto
            .newBuilder()
            .setPayload(payloadReqBytes)
            .setPayloadSizeResp(payloadRespSizeKB)
            .setSleepTimeMs(sleepTimeMs)
            .setReadOnly(readOnly)
            .build();
    String id = client.getPipeline().getClosestNode().getUuidString();

    ContainerCommandRequestProto.Builder builder = ContainerCommandRequestProto
        .newBuilder()
        .setCmdType(Type.Echo)
        .setContainerID(containerID)
        .setDatanodeUuid(id)
        .setEcho(getEcho);
    if (!encodedContainerID.isEmpty()) {
      builder.setEncodedToken(encodedContainerID);
    }
    String traceId = TracingUtil.exportCurrentSpan();
    if (traceId != null) {
      builder.setTraceID(traceId);
    }
    ContainerCommandRequestProto request = builder.build();
    ContainerCommandResponseProto response =
        client.sendCommand(request, getValidatorList());
    return response.getEcho();
  }

  /**
   * Validates a response from a container protocol call.  Any non-successful
   * return code is mapped to a corresponding exception and thrown.
   *
   * @param response container protocol call response
   * @throws StorageContainerException if the container protocol call failed
   */
  public static void validateContainerResponse(
      ContainerCommandResponseProto response
  ) throws StorageContainerException {
    if (response.getResult() == ContainerProtos.Result.SUCCESS) {
      return;
    } else if (response.getResult()
        == ContainerProtos.Result.BLOCK_NOT_COMMITTED) {
      throw new BlockNotCommittedException(response.getMessage());
    } else if (response.getResult()
        == ContainerProtos.Result.CLOSED_CONTAINER_IO) {
      throw new ContainerNotOpenException(response.getMessage());
    }
    throw new StorageContainerException(
        response.getMessage(), response.getResult());
  }

  private static List<Validator> getValidatorList() {
    return VALIDATORS;
  }

  private static final List<Validator> VALIDATORS = createValidators();

  private static List<Validator> createValidators() {
    return singletonList((request, response) -> validateContainerResponse(response));
  }

  public static List<Validator> toValidatorList(Validator validator) {
    final List<Validator> defaults = getValidatorList();
    final List<Validator> validators
        = new ArrayList<>(defaults.size() + 1);
    validators.addAll(defaults);
    validators.add(validator);
    return Collections.unmodifiableList(validators);
  }
}
