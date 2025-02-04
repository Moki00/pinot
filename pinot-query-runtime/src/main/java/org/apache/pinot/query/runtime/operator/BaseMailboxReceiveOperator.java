/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.runtime.operator;

import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelDistribution;
import org.apache.pinot.query.mailbox.MailboxIdUtils;
import org.apache.pinot.query.mailbox.MailboxService;
import org.apache.pinot.query.mailbox.ReceivingMailbox;
import org.apache.pinot.query.routing.VirtualServerAddress;
import org.apache.pinot.query.routing.WorkerMetadata;
import org.apache.pinot.query.runtime.plan.OpChainExecutionContext;


/**
 * Base class to be used by the various MailboxReceiveOperators such as the sorted and non-sorted versions. This
 * class contains the common logic needed for MailboxReceive
 *
 * BaseMailboxReceiveOperator receives mailbox from mailboxService from sendingStageInstances.
 * We use sendingStageInstance to deduce mailboxId and fetch the content from mailboxService.
 * When exchangeType is Singleton, we find the mapping mailbox for the mailboxService. If not found, use empty list.
 * When exchangeType is non-Singleton, we pull from each instance in round-robin way to get matched mailbox content.
 */
public abstract class BaseMailboxReceiveOperator extends MultiStageOperator {
  protected final MailboxService _mailboxService;
  protected final RelDistribution.Type _exchangeType;
  protected final List<String> _mailboxIds;
  protected final Deque<ReceivingMailbox> _mailboxes;

  public BaseMailboxReceiveOperator(OpChainExecutionContext context, RelDistribution.Type exchangeType,
      int senderStageId) {
    super(context);
    _mailboxService = context.getMailboxService();
    Preconditions.checkState(MailboxSendOperator.SUPPORTED_EXCHANGE_TYPES.contains(exchangeType),
        "Unsupported exchange type: %s", exchangeType);
    _exchangeType = exchangeType;

    long requestId = context.getRequestId();
    int receiverStageId = context.getStageId();
    List<WorkerMetadata> senderMetadataList = context.getStageMetadataList().get(senderStageId).getWorkerMetadataList();
    VirtualServerAddress receiver = context.getServer();
    if (exchangeType == RelDistribution.Type.SINGLETON) {
      VirtualServerAddress singletonSender = null;
      for (WorkerMetadata senderMetadata : senderMetadataList) {
        VirtualServerAddress sender = senderMetadata.getVirtualServerAddress();
        if (sender.hostname().equals(_mailboxService.getHostname()) && sender.port() == _mailboxService.getPort()) {
          Preconditions.checkState(singletonSender == null, "Multiple instances found for SINGLETON exchange type");
          singletonSender = sender;
        }
      }
      Preconditions.checkState(singletonSender != null, "Failed to find instance for SINGLETON exchange type");
      _mailboxIds = Collections.singletonList(
          MailboxIdUtils.toMailboxId(requestId, senderStageId, singletonSender.workerId(), receiverStageId,
              receiver.workerId()));
    } else {
      _mailboxIds = new ArrayList<>(senderMetadataList.size());
      for (WorkerMetadata senderMetadata : senderMetadataList) {
        VirtualServerAddress sender = senderMetadata.getVirtualServerAddress();
        _mailboxIds.add(MailboxIdUtils.toMailboxId(requestId, senderStageId, sender.workerId(), receiverStageId,
            receiver.workerId()));
      }
    }
    _mailboxes = _mailboxIds.stream().map(_mailboxService::getReceivingMailbox)
        .collect(Collectors.toCollection(ArrayDeque::new));
  }

  public List<String> getMailboxIds() {
    return _mailboxIds;
  }

  @Override
  public List<MultiStageOperator> getChildOperators() {
    return Collections.emptyList();
  }

  @Override
  public void close() {
    super.close();
    cancelRemainingMailboxes();
  }

  @Override
  public void cancel(Throwable t) {
    super.cancel(t);
    cancelRemainingMailboxes();
  }

  protected void cancelRemainingMailboxes() {
    ReceivingMailbox mailbox;
    while ((mailbox = _mailboxes.poll()) != null) {
      mailbox.cancel();
    }
  }
}
