// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend

import com.daml.metrics.api.MetricsContext
import com.daml.platform.v1.index.StatusDetails
import com.digitalasset.canton.data.DeduplicationPeriod.{DeduplicationDuration, DeduplicationOffset}
import com.digitalasset.canton.data.{CantonTimestamp, Offset}
import com.digitalasset.canton.ledger.participant.state
import com.digitalasset.canton.ledger.participant.state.Update.TopologyTransactionEffective.AuthorizationLevel.*
import com.digitalasset.canton.ledger.participant.state.Update.TopologyTransactionEffective.TopologyEvent
import com.digitalasset.canton.ledger.participant.state.Update.TopologyTransactionEffective.TopologyEvent.PartyToParticipantAuthorization
import com.digitalasset.canton.ledger.participant.state.{
  DomainIndex,
  Reassignment,
  ReassignmentInfo,
  RequestIndex,
  SequencerIndex,
  Update,
}
import com.digitalasset.canton.metrics.LedgerApiServerMetrics
import com.digitalasset.canton.platform.store.dao.JdbcLedgerDao
import com.digitalasset.canton.platform.store.dao.events.{
  CompressionStrategy,
  FieldCompressionStrategy,
  LfValueSerialization,
}
import com.digitalasset.canton.platform.{ContractId, Create, Exercise}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext.Implicits.Empty.emptyTraceContext
import com.digitalasset.canton.tracing.{SerializableTraceContext, TraceContext, Traced}
import com.digitalasset.canton.util.ReassignmentTag.{Source, Target}
import com.digitalasset.canton.{RequestCounter, SequencerCounter}
import com.digitalasset.daml.lf.crypto
import com.digitalasset.daml.lf.data.{Bytes, Ref, Time}
import com.digitalasset.daml.lf.ledger.EventId
import com.digitalasset.daml.lf.transaction.GlobalKey
import com.digitalasset.daml.lf.transaction.test.TestNodeBuilder.CreateKey
import com.digitalasset.daml.lf.transaction.test.{
  NodeIdTransactionBuilder,
  TestNodeBuilder,
  TransactionBuilder,
}
import com.digitalasset.daml.lf.value.Value
import com.google.rpc.status.Status as StatusProto
import io.grpc.Status
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}
import java.util.UUID

// Note: this suite contains hand-crafted updates that are impossible to produce on some ledgers
// (e.g., because the ledger removes rollback nodes before sending them to the index database).
// Should you ever consider replacing this suite by something else, make sure all functionality is still covered.
class UpdateToDbDtoSpec extends AnyWordSpec with Matchers {

  import TraceContext.Implicits.Empty.*
  import TransactionBuilder.Implicits.*
  import UpdateToDbDtoSpec.*

  object TxBuilder {
    def apply(): NodeIdTransactionBuilder & TestNodeBuilder = new NodeIdTransactionBuilder
      with TestNodeBuilder
  }

  "UpdateToDbDto" should {

    "handle Init" in {
      val update = state.Update.Init(
        someRecordTime
      )
      val dtos = updateToDtos(update)

      dtos shouldBe empty
    }

    "handle PartyAddedToParticipant (local party)" in {
      val displayName = "Test party"
      val update = state.Update.PartyAddedToParticipant(
        someParty,
        displayName,
        someParticipantId,
        someRecordTime,
        Some(someSubmissionId),
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.PartyEntry(
          ledger_offset = someOffset.toHexString,
          recorded_at = someRecordTime.micros,
          submission_id = Some(someSubmissionId),
          party = Some(someParty),
          display_name = Some(displayName),
          typ = JdbcLedgerDao.acceptType,
          rejection_reason = None,
          is_local = Some(true),
        )
      )
    }

    "handle PartyAddedToParticipant (remote party)" in {
      val displayName = "Test party"
      val update = state.Update.PartyAddedToParticipant(
        someParty,
        displayName,
        otherParticipantId,
        someRecordTime,
        None,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.PartyEntry(
          ledger_offset = someOffset.toHexString,
          recorded_at = someRecordTime.micros,
          submission_id = None,
          party = Some(someParty),
          display_name = Some(displayName),
          typ = JdbcLedgerDao.acceptType,
          rejection_reason = None,
          is_local = Some(false),
        )
      )
    }

    "handle PartyAllocationRejected" in {
      val rejectionReason = "Test party rejection reason"
      val update = state.Update.PartyAllocationRejected(
        someSubmissionId,
        someParticipantId,
        someRecordTime,
        rejectionReason,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.PartyEntry(
          ledger_offset = someOffset.toHexString,
          recorded_at = someRecordTime.micros,
          submission_id = Some(someSubmissionId),
          party = None,
          display_name = None,
          typ = JdbcLedgerDao.rejectType,
          rejection_reason = Some(rejectionReason),
          is_local = None,
        )
      )
    }

    "handle CommandRejected (sequenced rejection)" in {
      val status = StatusProto.of(Status.Code.ABORTED.value(), "test reason", Seq.empty)
      val completionInfo = someCompletionInfo
      val update = state.Update.CommandRejected(
        someRecordTime,
        completionInfo,
        state.Update.CommandRejected.FinalReason(status),
        domainId = someDomainId1,
        domainIndex = Some(
          DomainIndex.of(
            RequestIndex(
              counter = RequestCounter(11),
              sequencerCounter = Some(SequencerCounter(15)),
              timestamp = CantonTimestamp.ofEpochMicro(1234567),
            )
          )
        ),
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = someApplicationId,
          submitters = Set(someParty),
          command_id = someCommandId,
          update_id = None,
          rejection_status_code = Some(status.code),
          rejection_status_message = Some(status.message),
          rejection_status_details = Some(StatusDetails.of(status.details).toByteArray),
          submission_id = Some(someSubmissionId),
          deduplication_offset = None,
          deduplication_duration_seconds = None,
          deduplication_duration_nanos = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = Some(15),
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        )
      )
    }

    "handle CommandRejected (local rejection)" in {
      val status = StatusProto.of(Status.Code.ABORTED.value(), "test reason", Seq.empty)
      val messageUuid = UUID.randomUUID()
      val completionInfo = someCompletionInfo.copy(messageUuid = Some(messageUuid))
      val update = state.Update.CommandRejected(
        someRecordTime,
        completionInfo,
        state.Update.CommandRejected.FinalReason(status),
        domainId = someDomainId1,
        domainIndex = None,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = someApplicationId,
          submitters = Set(someParty),
          command_id = someCommandId,
          update_id = None,
          rejection_status_code = Some(status.code),
          rejection_status_message = Some(status.message),
          rejection_status_details = Some(StatusDetails.of(status.details).toByteArray),
          submission_id = Some(someSubmissionId),
          deduplication_offset = None,
          deduplication_duration_seconds = None,
          deduplication_duration_nanos = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = Some(messageUuid.toString),
          request_sequencer_counter = None,
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        )
      )
    }

    val updateId = Ref.TransactionId.assertFromString("UpdateId")

    "handle TransactionAccepted (single create node)" in {
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val contractId = builder.newCid
      val contractTemplate = Ref.Identifier.assertFromString("P:M:T")
      val keyValue = Value.ValueUnit
      val createNode = builder
        .create(
          id = contractId,
          templateId = contractTemplate,
          argument = Value.ValueUnit,
          signatories = Set("signatory1", "signatory2", "signatory3"),
          observers = Set("observer"),
          key = CreateKey.KeyWithMaintainers(keyValue, Set("signatory2", "signatory3")),
        )
      val createNodeId = builder.add(createNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        contractMetadata = Map(contractId -> someContractDriverMetadata),
        hostedWitnesses = Nil,
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.EventCreate(
        event_offset = someOffset.toHexString,
        update_id = updateId,
        ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
        command_id = Some(completionInfo.commandId),
        workflow_id = transactionMeta.workflowId,
        application_id = Some(completionInfo.applicationId),
        submitters = Some(completionInfo.actAs.toSet),
        node_index = createNodeId.index,
        contract_id = createNode.coid.coid,
        template_id = createNode.templateId.toString,
        package_name = createNode.packageName.toString,
        package_version = createNode.packageVersion.map(_.toString()),
        flat_event_witnesses =
          Set("signatory1", "signatory2", "signatory3", "observer"), // stakeholders
        tree_event_witnesses =
          Set("signatory1", "signatory2", "signatory3", "observer"), // informees
        create_argument = emptyArray,
        create_signatories = Set("signatory1", "signatory2", "signatory3"),
        create_observers = Set("observer"),
        create_key_value = Some(emptyArray),
        create_key_maintainers = Some(Set("signatory2", "signatory3")),
        create_key_hash = Some(
          GlobalKey
            .assertBuild(contractTemplate, keyValue, createNode.packageName)
            .hash
            .bytes
            .toHexString
        ),
        create_argument_compression = compressionAlgorithmId,
        create_key_value_compression = compressionAlgorithmId,
        event_sequential_id = 0,
        driver_metadata = someContractDriverMetadata.toByteArray,
        domain_id = someDomainId1.toProtoPrimitive,
        trace_context = serializedEmptyTraceContext,
        record_time = someRecordTime.micros,
      )
      dtos(5) shouldEqual DbDto.CommandCompletion(
        completion_offset = someOffset.toHexString,
        record_time = someRecordTime.micros,
        publication_time = 0,
        application_id = completionInfo.applicationId,
        submitters = completionInfo.actAs.toSet,
        command_id = completionInfo.commandId,
        update_id = Some(updateId),
        rejection_status_code = None,
        rejection_status_message = None,
        rejection_status_details = None,
        submission_id = completionInfo.submissionId,
        deduplication_offset = None,
        deduplication_duration_nanos = None,
        deduplication_duration_seconds = None,
        deduplication_start = None,
        domain_id = someDomainId1.toProtoPrimitive,
        message_uuid = None,
        request_sequencer_counter = None,
        is_transaction = true,
        trace_context = serializedEmptyTraceContext,
      )
      dtos(6) shouldEqual DbDto.TransactionMeta(
        update_id = updateId,
        event_offset = someOffset.toHexString,
        publication_time = 0,
        record_time = someRecordTime.micros,
        domain_id = someDomainId1.toProtoPrimitive,
        event_sequential_id_first = 0,
        event_sequential_id_last = 0,
      )
      Set(dtos(1), dtos(2), dtos(3), dtos(4)) should contain theSameElementsAs Set(
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "signatory1"),
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "signatory2"),
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "signatory3"),
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "observer"),
      )
      dtos.size shouldEqual 7
    }

    "handle TransactionAccepted (single consuming exercise node)" in {
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val exerciseNode = {
        val createNode = builder.create(
          id = builder.newCid,
          templateId = "M:T",
          argument = Value.ValueUnit,
          signatories = List("signatory"),
          observers = List("observer"),
        )
        builder.exercise(
          contract = createNode,
          choice = "someChoice",
          consuming = true,
          actingParties = Set("signatory"),
          argument = Value.ValueUnit,
          result = Some(Value.ValueUnit),
          choiceObservers = Set.empty,
          byKey = false,
        )
      }
      val exerciseNodeId = builder.add(exerciseNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        contractMetadata = Map.empty,
        hostedWitnesses = Nil,
        domainId = someDomainId1,
        domainIndex = Some(
          DomainIndex.of(
            RequestIndex(
              counter = RequestCounter(100),
              sequencerCounter = Some(SequencerCounter(110)),
              timestamp = CantonTimestamp.ofEpochMicro(120),
            )
          )
        ),
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.EventExercise(
          consuming = true,
          event_offset = someOffset.toHexString,
          update_id = updateId,
          ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = exerciseNodeId.index,
          contract_id = exerciseNode.targetCoid.coid,
          template_id = exerciseNode.templateId.toString,
          package_name = exerciseNode.packageName,
          flat_event_witnesses = Set("signatory", "observer"), // stakeholders
          tree_event_witnesses = Set("signatory", "observer"), // informees
          create_key_value = None,
          exercise_choice = exerciseNode.choiceId,
          exercise_argument = emptyArray,
          exercise_result = Some(emptyArray),
          exercise_actors = Set("signatory"),
          exercise_child_event_ids = Vector.empty,
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = someDomainId1.toProtoPrimitive,
          trace_context = serializedEmptyTraceContext,
          record_time = someRecordTime.micros,
        ),
        DbDto.IdFilterConsumingStakeholder(
          event_sequential_id = 0,
          template_id = exerciseNode.templateId.toString,
          party_id = "signatory",
        ),
        DbDto.IdFilterConsumingStakeholder(
          event_sequential_id = 0,
          template_id = exerciseNode.templateId.toString,
          party_id = "observer",
        ),
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          update_id = Some(updateId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = Some(110),
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        ),
        DbDto.TransactionMeta(
          update_id = updateId,
          event_offset = someOffset.toHexString,
          publication_time = 0,
          record_time = someRecordTime.micros,
          domain_id = someDomainId1.toProtoPrimitive,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (single non-consuming exercise node)" in {
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val exerciseNode = {
        val createNode = builder.create(
          id = builder.newCid,
          templateId = "M:T",
          argument = Value.ValueUnit,
          signatories = List("signatory"),
          observers = List("observer"),
        )
        builder.exercise(
          contract = createNode,
          choice = "someChoice",
          consuming = false,
          actingParties = Set("signatory"),
          argument = Value.ValueUnit,
          result = Some(Value.ValueUnit),
          choiceObservers = Set.empty,
          byKey = false,
        )
      }
      val exerciseNodeId = builder.add(exerciseNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        contractMetadata = Map.empty,
        hostedWitnesses = Nil,
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.EventExercise(
          consuming = false,
          event_offset = someOffset.toHexString,
          update_id = updateId,
          ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = exerciseNodeId.index,
          contract_id = exerciseNode.targetCoid.coid,
          template_id = exerciseNode.templateId.toString,
          package_name = exerciseNode.packageName,
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = exerciseNode.choiceId,
          exercise_argument = emptyArray,
          exercise_result = Some(emptyArray),
          exercise_actors = Set("signatory"),
          exercise_child_event_ids = Vector.empty,
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = someDomainId1.toProtoPrimitive,
          trace_context = serializedEmptyTraceContext,
          record_time = someRecordTime.micros,
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          update_id = Some(updateId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = None,
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        ),
        DbDto.TransactionMeta(
          update_id = updateId,
          event_offset = someOffset.toHexString,
          publication_time = 0,
          record_time = someRecordTime.micros,
          domain_id = someDomainId1.toProtoPrimitive,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (single non-consuming exercise node)" in {
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val exerciseNode = {
        val createNode = builder.create(
          id = builder.newCid,
          templateId = "M:T",
          argument = Value.ValueUnit,
          signatories = List("signatory"),
          observers = List("observer"),
        )
        builder.exercise(
          contract = createNode,
          choice = "someChoice",
          consuming = false,
          actingParties = Set("signatory"),
          argument = Value.ValueUnit,
          result = Some(Value.ValueUnit),
          choiceObservers = Set.empty,
          byKey = false,
        )
      }
      val exerciseNodeId = builder.add(exerciseNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        transactionId = transactionId,
        recordTime = someRecordTime,
        divulgedContracts = List.empty,
        blindingInfoO = None,
        contractMetadata = Map.empty,
        hostedWitnesses = Nil,
      )
      val dtos = updateToDtos(update, multiDomainEnabled = true)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.EventExercise(
          consuming = false,
          event_offset = Some(someOffset.toHexString),
          transaction_id = Some(update.transactionId),
          ledger_effective_time = Some(transactionMeta.ledgerEffectiveTime.micros),
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = Some(exerciseNodeId.index),
          event_id = Some(EventId(update.transactionId, exerciseNodeId).toLedgerString),
          contract_id = exerciseNode.targetCoid.coid,
          template_id = Some(exerciseNode.templateId.toString),
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = Some(exerciseNode.choiceId),
          exercise_argument = Some(emptyArray),
          exercise_result = Some(emptyArray),
          exercise_actors = Some(Set("signatory")),
          exercise_child_event_ids = Some(Vector.empty),
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = Some(someDomainId1.toProtoPrimitive),
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = update.recordTime.micros,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          transaction_id = Some(update.transactionId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = Some(someDomainId1.toProtoPrimitive),
        ),
        DbDto.TransactionMeta(
          transaction_id = transactionId,
          event_offset = someOffset.toHexString,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (nested exercise nodes) single-domain" in {
      // Previous transaction
      // └─ #1 Create
      // Transaction
      // └─ #2 Exercise (choice A)
      //    ├─ #3 Exercise (choice B)
      //    └─ #4 Exercise (choice C)
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val createNode = builder.create(
        id = builder.newCid,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val exerciseNodeA = builder.exercise(
        contract = createNode,
        choice = "A",
        consuming = false,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set.empty,
        byKey = false,
      )
      val exerciseNodeB = builder.exercise(
        contract = createNode,
        choice = "B",
        consuming = false,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set.empty,
        byKey = false,
      )
      val exerciseNodeC = builder.exercise(
        contract = createNode,
        choice = "C",
        consuming = false,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set.empty,
        byKey = false,
      )
      val exerciseNodeAId = builder.add(exerciseNodeA)
      val exerciseNodeBId = builder.add(exerciseNodeB, exerciseNodeAId)
      val exerciseNodeCId = builder.add(exerciseNodeC, exerciseNodeAId)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        transactionId = transactionId,
        recordTime = someRecordTime,
        divulgedContracts = List.empty,
        blindingInfoO = None,
        contractMetadata = Map.empty,
        hostedWitnesses = Nil,
      )
      val dtos = updateToDtos(update, multiDomainEnabled = true)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.EventExercise(
          consuming = false,
          event_offset = Some(someOffset.toHexString),
          transaction_id = Some(update.transactionId),
          ledger_effective_time = Some(transactionMeta.ledgerEffectiveTime.micros),
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = Some(exerciseNodeAId.index),
          event_id = Some(EventId(update.transactionId, exerciseNodeAId).toLedgerString),
          contract_id = exerciseNodeA.targetCoid.coid,
          template_id = Some(exerciseNodeA.templateId.toString),
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = Some(exerciseNodeA.choiceId),
          exercise_argument = Some(emptyArray),
          exercise_result = Some(emptyArray),
          exercise_actors = Some(Set("signatory")),
          exercise_child_event_ids = Some(
            Vector(
              EventId(update.transactionId, exerciseNodeBId).toLedgerString,
              EventId(update.transactionId, exerciseNodeCId).toLedgerString,
            )
          ),
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = Some(someDomainId1.toProtoPrimitive),
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.EventExercise(
          consuming = false,
          event_offset = Some(someOffset.toHexString),
          transaction_id = Some(update.transactionId),
          ledger_effective_time = Some(transactionMeta.ledgerEffectiveTime.micros),
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = Some(exerciseNodeBId.index),
          event_id = Some(EventId(update.transactionId, exerciseNodeBId).toLedgerString),
          contract_id = exerciseNodeB.targetCoid.coid,
          template_id = Some(exerciseNodeB.templateId.toString),
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = Some(exerciseNodeB.choiceId),
          exercise_argument = Some(emptyArray),
          exercise_result = Some(emptyArray),
          exercise_actors = Some(Set("signatory")),
          exercise_child_event_ids = Some(Vector.empty),
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = Some(someDomainId1.toProtoPrimitive),
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.EventExercise(
          consuming = false,
          event_offset = Some(someOffset.toHexString),
          transaction_id = Some(update.transactionId),
          ledger_effective_time = Some(transactionMeta.ledgerEffectiveTime.micros),
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = Some(exerciseNodeCId.index),
          event_id = Some(EventId(update.transactionId, exerciseNodeCId).toLedgerString),
          contract_id = exerciseNodeC.targetCoid.coid,
          template_id = Some(exerciseNodeC.templateId.toString),
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = Some(exerciseNodeC.choiceId),
          exercise_argument = Some(emptyArray),
          exercise_result = Some(emptyArray),
          exercise_actors = Some(Set("signatory")),
          exercise_child_event_ids = Some(Vector.empty),
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = Some(someDomainId1.toProtoPrimitive),
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = update.recordTime.micros,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          transaction_id = Some(update.transactionId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = Some(someDomainId1.toProtoPrimitive),
        ),
        DbDto.TransactionMeta(
          transaction_id = transactionId,
          event_offset = someOffset.toHexString,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (nested exercise nodes)" in {
      // Previous transaction
      // └─ #1 Create
      // Transaction
      // └─ #2 Exercise (choice A)
      //    ├─ #3 Exercise (choice B)
      //    └─ #4 Exercise (choice C)
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val createNode = builder.create(
        id = builder.newCid,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val exerciseNodeA = builder.exercise(
        contract = createNode,
        choice = "A",
        consuming = false,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set.empty,
        byKey = false,
      )
      val exerciseNodeB = builder.exercise(
        contract = createNode,
        choice = "B",
        consuming = false,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set.empty,
        byKey = false,
      )
      val exerciseNodeC = builder.exercise(
        contract = createNode,
        choice = "C",
        consuming = false,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set.empty,
        byKey = false,
      )
      val exerciseNodeAId = builder.add(exerciseNodeA)
      val exerciseNodeBId = builder.add(exerciseNodeB, exerciseNodeAId)
      val exerciseNodeCId = builder.add(exerciseNodeC, exerciseNodeAId)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        contractMetadata = Map.empty,
        hostedWitnesses = Nil,
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.EventExercise(
          consuming = false,
          event_offset = someOffset.toHexString,
          update_id = updateId,
          ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = exerciseNodeAId.index,
          contract_id = exerciseNodeA.targetCoid.coid,
          template_id = exerciseNodeA.templateId.toString,
          package_name = exerciseNodeA.packageName,
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = exerciseNodeA.choiceId,
          exercise_argument = emptyArray,
          exercise_result = Some(emptyArray),
          exercise_actors = Set("signatory"),
          exercise_child_event_ids = Vector(
            EventId(updateId, exerciseNodeBId).toLedgerString,
            EventId(updateId, exerciseNodeCId).toLedgerString,
          ),
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = someDomainId1.toProtoPrimitive,
          trace_context = serializedEmptyTraceContext,
          record_time = someRecordTime.micros,
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.EventExercise(
          consuming = false,
          event_offset = someOffset.toHexString,
          update_id = updateId,
          ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = exerciseNodeBId.index,
          contract_id = exerciseNodeB.targetCoid.coid,
          template_id = exerciseNodeB.templateId.toString,
          package_name = exerciseNodeB.packageName,
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = exerciseNodeB.choiceId,
          exercise_argument = emptyArray,
          exercise_result = Some(emptyArray),
          exercise_actors = Set("signatory"),
          exercise_child_event_ids = Vector.empty,
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = someDomainId1.toProtoPrimitive,
          trace_context = serializedEmptyTraceContext,
          record_time = someRecordTime.micros,
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.EventExercise(
          consuming = false,
          event_offset = someOffset.toHexString,
          update_id = updateId,
          ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = exerciseNodeCId.index,
          contract_id = exerciseNodeC.targetCoid.coid,
          template_id = exerciseNodeC.templateId.toString,
          package_name = exerciseNodeC.packageName,
          flat_event_witnesses = Set.empty, // stakeholders
          tree_event_witnesses = Set("signatory"), // informees
          create_key_value = None,
          exercise_choice = exerciseNodeC.choiceId,
          exercise_argument = emptyArray,
          exercise_result = Some(emptyArray),
          exercise_actors = Set("signatory"),
          exercise_child_event_ids = Vector.empty,
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = someDomainId1.toProtoPrimitive,
          trace_context = serializedEmptyTraceContext,
          record_time = someRecordTime.micros,
        ),
        DbDto.IdFilterNonConsumingInformee(
          event_sequential_id = 0,
          party_id = "signatory",
        ),
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          update_id = Some(updateId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = None,
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        ),
        DbDto.TransactionMeta(
          update_id = updateId,
          event_offset = someOffset.toHexString,
          publication_time = 0,
          record_time = someRecordTime.micros,
          domain_id = someDomainId1.toProtoPrimitive,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (fetch and lookup nodes)" in {
      // Previous transaction
      // └─ #1 Create
      // Transaction
      // ├─ #1 Fetch
      // ├─ #2 Fetch by key
      // └─ #3 Lookup by key
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val createNode = builder.create(
        id = builder.newCid,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
        key = CreateKey.SignatoryMaintainerKey(Value.ValueUnit),
      )
      val fetchNode = builder.fetch(
        contract = createNode,
        byKey = false,
      )
      val fetchByKeyNode = builder.fetch(
        contract = createNode,
        byKey = true,
      )
      val lookupByKeyNode = builder.lookupByKey(
        contract = createNode
      )
      builder.add(fetchNode)
      builder.add(fetchByKeyNode)
      builder.add(lookupByKeyNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        hostedWitnesses = Nil,
        contractMetadata = Map.empty,
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      // Note: fetch and lookup nodes are not indexed
      dtos should contain theSameElementsInOrderAs List(
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          update_id = Some(updateId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = None,
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        ),
        DbDto.TransactionMeta(
          update_id = updateId,
          event_offset = someOffset.toHexString,
          publication_time = 0,
          record_time = someRecordTime.micros,
          domain_id = someDomainId1.toProtoPrimitive,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (single exercise node with divulgence)" in {
      // Previous transaction
      // └─ #1 Create
      // Transaction
      // └─ #2 Exercise (divulges #1 to 'divulgee')
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val createNode = builder.create(
        id = builder.newCid,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val exerciseNode = builder.exercise(
        contract = createNode,
        choice = "someChoice",
        consuming = true,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set("divulgee"),
        byKey = false,
      )
      val exerciseNodeId = builder.add(exerciseNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        hostedWitnesses = Nil,
        contractMetadata = Map.empty,
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.EventExercise(
          consuming = true,
          event_offset = someOffset.toHexString,
          update_id = updateId,
          ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
          command_id = Some(completionInfo.commandId),
          workflow_id = transactionMeta.workflowId,
          application_id = Some(completionInfo.applicationId),
          submitters = Some(completionInfo.actAs.toSet),
          node_index = exerciseNodeId.index,
          contract_id = exerciseNode.targetCoid.coid,
          template_id = exerciseNode.templateId.toString,
          package_name = exerciseNode.packageName,
          flat_event_witnesses = Set("signatory", "observer"),
          tree_event_witnesses = Set("signatory", "observer", "divulgee"),
          create_key_value = None,
          exercise_choice = exerciseNode.choiceId,
          exercise_argument = emptyArray,
          exercise_result = Some(emptyArray),
          exercise_actors = Set("signatory"),
          exercise_child_event_ids = Vector.empty,
          create_key_value_compression = compressionAlgorithmId,
          exercise_argument_compression = compressionAlgorithmId,
          exercise_result_compression = compressionAlgorithmId,
          event_sequential_id = 0,
          domain_id = someDomainId1.toProtoPrimitive,
          trace_context = serializedEmptyTraceContext,
          record_time = someRecordTime.micros,
        ),
        DbDto.IdFilterConsumingStakeholder(
          event_sequential_id = 0,
          template_id = exerciseNode.templateId.toString,
          party_id = "signatory",
        ),
        DbDto.IdFilterConsumingStakeholder(
          event_sequential_id = 0,
          template_id = exerciseNode.templateId.toString,
          party_id = "observer",
        ),
        DbDto.IdFilterConsumingNonStakeholderInformee(
          event_sequential_id = 0,
          party_id = "divulgee",
        ),
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          update_id = Some(updateId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = None,
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        ),
        DbDto.TransactionMeta(
          update_id = updateId,
          event_offset = someOffset.toHexString,
          publication_time = 0,
          record_time = someRecordTime.micros,
          domain_id = someDomainId1.toProtoPrimitive,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (transaction with local divulgence)" in {
      // Transaction
      // ├─ #1 Create
      // └─ #2 Exercise (divulges #1 to 'divulgee')
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val contractId = builder.newCid
      val createNode = builder.create(
        id = contractId,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val exerciseNode = builder.exercise(
        contract = createNode,
        choice = "someChoice",
        consuming = true,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set("divulgee"),
        byKey = false,
      )
      val createNodeId = builder.add(createNode)
      val exerciseNodeId = builder.add(exerciseNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        hostedWitnesses = Nil,
        contractMetadata = Map(contractId -> someContractDriverMetadata),
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.EventCreate(
        event_offset = someOffset.toHexString,
        update_id = updateId,
        ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
        command_id = Some(completionInfo.commandId),
        workflow_id = transactionMeta.workflowId,
        application_id = Some(completionInfo.applicationId),
        submitters = Some(completionInfo.actAs.toSet),
        node_index = createNodeId.index,
        contract_id = createNode.coid.coid,
        template_id = createNode.templateId.toString,
        package_name = createNode.packageName.toString,
        package_version = createNode.packageVersion.map(_.toString()),
        flat_event_witnesses = Set("signatory", "observer"),
        tree_event_witnesses = Set("signatory", "observer"),
        create_argument = emptyArray,
        create_signatories = Set("signatory"),
        create_observers = Set("observer"),
        create_key_value = None,
        create_key_maintainers = None,
        create_key_hash = None,
        create_argument_compression = compressionAlgorithmId,
        create_key_value_compression = None,
        event_sequential_id = 0,
        driver_metadata = someContractDriverMetadata.toByteArray,
        domain_id = someDomainId1.toProtoPrimitive,
        trace_context = serializedEmptyTraceContext,
        record_time = someRecordTime.micros,
      )
      Set(dtos(1), dtos(2)) should contain theSameElementsAs Set(
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "signatory"),
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "observer"),
      )
      dtos(3) shouldEqual DbDto.EventExercise(
        consuming = true,
        event_offset = someOffset.toHexString,
        update_id = updateId,
        ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
        command_id = Some(completionInfo.commandId),
        workflow_id = transactionMeta.workflowId,
        application_id = Some(completionInfo.applicationId),
        submitters = Some(completionInfo.actAs.toSet),
        node_index = exerciseNodeId.index,
        contract_id = exerciseNode.targetCoid.coid,
        template_id = exerciseNode.templateId.toString,
        package_name = exerciseNode.packageName,
        flat_event_witnesses = Set("signatory", "observer"),
        tree_event_witnesses = Set("signatory", "observer", "divulgee"),
        create_key_value = None,
        exercise_choice = exerciseNode.choiceId,
        exercise_argument = emptyArray,
        exercise_result = Some(emptyArray),
        exercise_actors = Set("signatory"),
        exercise_child_event_ids = Vector.empty,
        create_key_value_compression = compressionAlgorithmId,
        exercise_argument_compression = compressionAlgorithmId,
        exercise_result_compression = compressionAlgorithmId,
        event_sequential_id = 0,
        domain_id = someDomainId1.toProtoPrimitive,
        trace_context = serializedEmptyTraceContext,
        record_time = someRecordTime.micros,
      )
      dtos(4) shouldEqual DbDto.IdFilterConsumingStakeholder(
        event_sequential_id = 0,
        template_id = exerciseNode.templateId.toString,
        party_id = "signatory",
      )
      dtos(5) shouldEqual DbDto.IdFilterConsumingStakeholder(
        event_sequential_id = 0,
        template_id = exerciseNode.templateId.toString,
        party_id = "observer",
      )
      dtos(6) shouldEqual DbDto.IdFilterConsumingNonStakeholderInformee(
        event_sequential_id = 0,
        party_id = "divulgee",
      )
      dtos(7) shouldEqual DbDto.CommandCompletion(
        completion_offset = someOffset.toHexString,
        record_time = someRecordTime.micros,
        publication_time = 0,
        application_id = completionInfo.applicationId,
        submitters = completionInfo.actAs.toSet,
        command_id = completionInfo.commandId,
        update_id = Some(updateId),
        rejection_status_code = None,
        rejection_status_message = None,
        rejection_status_details = None,
        submission_id = completionInfo.submissionId,
        deduplication_offset = None,
        deduplication_duration_nanos = None,
        deduplication_duration_seconds = None,
        deduplication_start = None,
        domain_id = someDomainId1.toProtoPrimitive,
        message_uuid = None,
        request_sequencer_counter = None,
        is_transaction = true,
        trace_context = serializedEmptyTraceContext,
      )
      dtos(8) shouldEqual DbDto.TransactionMeta(
        update_id = updateId,
        event_offset = someOffset.toHexString,
        publication_time = 0,
        record_time = someRecordTime.micros,
        domain_id = someDomainId1.toProtoPrimitive,
        event_sequential_id_first = 0,
        event_sequential_id_last = 0,
      )
      dtos.size shouldEqual 9
    }

    "handle TransactionAccepted (rollback node)" in {
      // Transaction
      // └─ #1 Rollback
      //    ├─ #2 Create
      //    └─ #3 Exercise (divulges #2 to divulgee)
      // - Create and Exercise events must not be visible
      val completionInfo = someCompletionInfo
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val rollbackNode = builder.rollback()
      val createNode = builder.create(
        id = builder.newCid,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val exerciseNode = builder.exercise(
        contract = createNode,
        choice = "someChoice",
        consuming = true,
        actingParties = Set("signatory"),
        argument = Value.ValueUnit,
        result = Some(Value.ValueUnit),
        choiceObservers = Set("divulgee"),
        byKey = false,
      )
      val rollbackNodeId = builder.add(rollbackNode)
      builder.add(createNode, rollbackNodeId)
      builder.add(exerciseNode, rollbackNodeId)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = Some(completionInfo),
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = updateId,
        recordTime = someRecordTime,
        hostedWitnesses = Nil,
        contractMetadata = Map.empty,
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos should contain theSameElementsInOrderAs List(
        DbDto.CommandCompletion(
          completion_offset = someOffset.toHexString,
          record_time = someRecordTime.micros,
          publication_time = 0,
          application_id = completionInfo.applicationId,
          submitters = completionInfo.actAs.toSet,
          command_id = completionInfo.commandId,
          update_id = Some(updateId),
          rejection_status_code = None,
          rejection_status_message = None,
          rejection_status_details = None,
          submission_id = completionInfo.submissionId,
          deduplication_offset = None,
          deduplication_duration_nanos = None,
          deduplication_duration_seconds = None,
          deduplication_start = None,
          domain_id = someDomainId1.toProtoPrimitive,
          message_uuid = None,
          request_sequencer_counter = None,
          is_transaction = true,
          trace_context = serializedEmptyTraceContext,
        ),
        DbDto.TransactionMeta(
          update_id = updateId,
          event_offset = someOffset.toHexString,
          publication_time = 0,
          record_time = someRecordTime.micros,
          domain_id = someDomainId1.toProtoPrimitive,
          event_sequential_id_first = 0,
          event_sequential_id_last = 0,
        ),
      )
    }

    "handle TransactionAccepted (no submission info)" in {
      // Transaction that is missing a SubmitterInfo
      // This happens if a transaction was submitted through a different participant
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val contractId = builder.newCid
      val createNode = builder.create(
        id = contractId,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val createNodeId = builder.add(createNode)
      val transaction = builder.buildCommitted()
      val update = state.Update.TransactionAccepted(
        completionInfoO = None,
        transactionMeta = transactionMeta,
        transaction = transaction,
        updateId = Ref.TransactionId.assertFromString("UpdateId"),
        recordTime = someRecordTime,
        hostedWitnesses = Nil,
        contractMetadata = Map(contractId -> someContractDriverMetadata),
        domainId = someDomainId1,
        domainIndex = someDomainIndex,
      )
      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.EventCreate(
        event_offset = someOffset.toHexString,
        update_id = updateId,
        ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
        command_id = None,
        workflow_id = transactionMeta.workflowId,
        application_id = None,
        submitters = None,
        node_index = createNodeId.index,
        contract_id = createNode.coid.coid,
        template_id = createNode.templateId.toString,
        package_name = createNode.packageName.toString,
        package_version = createNode.packageVersion.map(_.toString()),
        flat_event_witnesses = Set("signatory", "observer"),
        tree_event_witnesses = Set("signatory", "observer"),
        create_argument = emptyArray,
        create_signatories = Set("signatory"),
        create_observers = Set("observer"),
        create_key_value = None,
        create_key_maintainers = None,
        create_key_hash = None,
        create_argument_compression = compressionAlgorithmId,
        create_key_value_compression = None,
        event_sequential_id = 0,
        driver_metadata = someContractDriverMetadata.toByteArray,
        domain_id = someDomainId1.toProtoPrimitive,
        trace_context = serializedEmptyTraceContext,
        record_time = someRecordTime.micros,
      )
      Set(dtos(1), dtos(2)) should contain theSameElementsAs Set(
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "signatory"),
        DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "observer"),
      )
      dtos.size shouldEqual 4
    }

    val deduplicationPeriods = Table(
      (
        "Deduplication period",
        "Expected deduplication offset",
        "Expected deduplication duration seconds",
        "Expected deduplication duration nanos",
      ),
      (None, None, None, None),
      (
        Some(DeduplicationOffset(Offset.beforeBegin)),
        Some(Offset.beforeBegin.toHexString),
        None,
        None,
      ),
      (
        Some(DeduplicationDuration(Duration.ofDays(1L).plusNanos(100 * 1000))),
        None,
        Some(Duration.ofDays(1L).toMinutes * 60L),
        Some(100 * 1000),
      ),
    )

    "handle CommandRejected (all deduplication data)" in {
      val status = StatusProto.of(Status.Code.ABORTED.value(), "test reason", Seq.empty)
      forAll(deduplicationPeriods) {
        case (
              deduplicationPeriod,
              expectedDeduplicationOffset,
              expectedDeduplicationDurationSeconds,
              expectedDeduplicationDurationNanos,
            ) =>
          val completionInfo = someCompletionInfo.copy(optDeduplicationPeriod = deduplicationPeriod)
          val update = state.Update.CommandRejected(
            someRecordTime,
            completionInfo,
            state.Update.CommandRejected.FinalReason(status),
            domainId = someDomainId1,
            domainIndex = someDomainIndexWithSequencerCounter,
          )
          val dtos = updateToDtos(update)

          dtos should contain theSameElementsInOrderAs List(
            DbDto.CommandCompletion(
              completion_offset = someOffset.toHexString,
              record_time = someRecordTime.micros,
              publication_time = 0,
              application_id = someApplicationId,
              submitters = Set(someParty),
              command_id = someCommandId,
              update_id = None,
              rejection_status_code = Some(status.code),
              rejection_status_message = Some(status.message),
              rejection_status_details = Some(StatusDetails.of(status.details).toByteArray),
              submission_id = Some(someSubmissionId),
              deduplication_offset = expectedDeduplicationOffset,
              deduplication_duration_seconds = expectedDeduplicationDurationSeconds,
              deduplication_duration_nanos = expectedDeduplicationDurationNanos,
              deduplication_start = None,
              domain_id = someDomainId1.toProtoPrimitive,
              message_uuid = None,
              request_sequencer_counter = Some(10),
              is_transaction = true,
              trace_context = serializedEmptyTraceContext,
            )
          )
      }
    }

    "handle TransactionAccepted (all deduplication data)" in {
      val transactionMeta = someTransactionMeta
      val builder = TxBuilder()
      val contractId = builder.newCid
      val createNode = builder.create(
        id = contractId,
        templateId = "M:T",
        argument = Value.ValueUnit,
        signatories = List("signatory"),
        observers = List("observer"),
      )
      val createNodeId = builder.add(createNode)
      val transaction = builder.buildCommitted()

      forAll(deduplicationPeriods) {
        case (
              deduplicationPeriod,
              expectedDeduplicationOffset,
              expectedDeduplicationDurationSeconds,
              expectedDeduplicationDurationNanos,
            ) =>
          val completionInfo = someCompletionInfo.copy(optDeduplicationPeriod = deduplicationPeriod)
          val update = state.Update.TransactionAccepted(
            completionInfoO = Some(completionInfo),
            transactionMeta = transactionMeta,
            transaction = transaction,
            updateId = updateId,
            recordTime = someRecordTime,
            hostedWitnesses = Nil,
            contractMetadata = Map(contractId -> someContractDriverMetadata),
            domainId = someDomainId1,
            domainIndex = someDomainIndex,
          )
          val dtos = updateToDtos(update)

          dtos.head shouldEqual DbDto.EventCreate(
            event_offset = someOffset.toHexString,
            update_id = updateId,
            ledger_effective_time = transactionMeta.ledgerEffectiveTime.micros,
            command_id = Some(completionInfo.commandId),
            workflow_id = transactionMeta.workflowId,
            application_id = Some(completionInfo.applicationId),
            submitters = Some(completionInfo.actAs.toSet),
            node_index = createNodeId.index,
            contract_id = createNode.coid.coid,
            template_id = createNode.templateId.toString,
            package_name = createNode.packageName.toString,
            package_version = createNode.packageVersion.map(_.toString()),
            flat_event_witnesses = Set("signatory", "observer"), // stakeholders
            tree_event_witnesses = Set("signatory", "observer"), // informees
            create_argument = emptyArray,
            create_signatories = Set("signatory"),
            create_observers = Set("observer"),
            create_key_value = None,
            create_key_maintainers = None,
            create_key_hash = None,
            create_argument_compression = compressionAlgorithmId,
            create_key_value_compression = None,
            event_sequential_id = 0,
            driver_metadata = someContractDriverMetadata.toByteArray,
            domain_id = someDomainId1.toProtoPrimitive,
            trace_context = serializedEmptyTraceContext,
            record_time = someRecordTime.micros,
          )
          Set(dtos(1), dtos(2)) should contain theSameElementsAs Set(
            DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "signatory"),
            DbDto.IdFilterCreateStakeholder(0L, createNode.templateId.toString, "observer"),
          )
          dtos(3) shouldEqual DbDto.CommandCompletion(
            completion_offset = someOffset.toHexString,
            record_time = someRecordTime.micros,
            publication_time = 0,
            application_id = completionInfo.applicationId,
            submitters = completionInfo.actAs.toSet,
            command_id = completionInfo.commandId,
            update_id = Some(updateId),
            rejection_status_code = None,
            rejection_status_message = None,
            rejection_status_details = None,
            submission_id = Some(someSubmissionId),
            deduplication_offset = expectedDeduplicationOffset,
            deduplication_duration_seconds = expectedDeduplicationDurationSeconds,
            deduplication_duration_nanos = expectedDeduplicationDurationNanos,
            deduplication_start = None,
            domain_id = someDomainId1.toProtoPrimitive,
            message_uuid = None,
            request_sequencer_counter = None,
            is_transaction = true,
            trace_context = serializedEmptyTraceContext,
          )
          dtos(4) shouldEqual DbDto.TransactionMeta(
            update_id = updateId,
            event_offset = someOffset.toHexString,
            publication_time = 0,
            record_time = someRecordTime.micros,
            domain_id = someDomainId1.toProtoPrimitive,
            event_sequential_id_first = 0,
            event_sequential_id_last = 0,
          )
          dtos.size shouldEqual 5
      }
    }

    "handle ReassignmentAccepted - Assign" in {
      val completionInfo = someCompletionInfo
      val builder = TxBuilder()
      val contractId = builder.newCid
      val createNode = builder
        .create(
          id = contractId,
          templateId = "M:T",
          argument = Value.ValueUnit,
          signatories = Set("signatory"),
          observers = Set("observer", "observer2"),
        )

      val update = state.Update.ReassignmentAccepted(
        optCompletionInfo = Some(completionInfo),
        workflowId = Some(someWorkflowId),
        updateId = updateId,
        recordTime = someRecordTime,
        reassignmentInfo = ReassignmentInfo(
          sourceDomain = Source(DomainId.tryFromString("x::domain1")),
          targetDomain = Target(DomainId.tryFromString("x::domain2")),
          submitter = Option(someParty),
          reassignmentCounter = 1500L,
          hostedStakeholders = Nil,
          unassignId = CantonTimestamp.assertFromLong(1000000000),
          isObservingReassigningParticipant = true,
        ),
        reassignment = Reassignment.Assign(
          ledgerEffectiveTime = Time.Timestamp.assertFromLong(17000000),
          createNode = createNode,
          contractMetadata = someContractDriverMetadata,
        ),
        domainIndex = someDomainIndex,
      )

      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.EventAssign(
        event_offset = someOffset.toHexString,
        update_id = update.updateId,
        command_id = Some(completionInfo.commandId),
        workflow_id = Some(someWorkflowId),
        submitter = Option(someParty),
        contract_id = createNode.coid.coid,
        template_id = createNode.templateId.toString,
        package_name = createNode.packageName.toString,
        package_version = createNode.packageVersion.map(_.toString()),
        flat_event_witnesses = Set("signatory", "observer", "observer2"),
        create_argument = emptyArray,
        create_signatories = Set("signatory"),
        create_observers = Set("observer", "observer2"),
        create_key_value = None,
        create_key_maintainers = None,
        create_key_hash = None,
        create_argument_compression = compressionAlgorithmId,
        create_key_value_compression = None,
        event_sequential_id = 0,
        ledger_effective_time = 17000000,
        driver_metadata = someContractDriverMetadata.toByteArray,
        source_domain_id = "x::domain1",
        target_domain_id = "x::domain2",
        unassign_id = "1000000000",
        reassignment_counter = 1500L,
        trace_context = serializedEmptyTraceContext,
        record_time = someRecordTime.micros,
      )
      dtos(4) shouldEqual DbDto.CommandCompletion(
        completion_offset = someOffset.toHexString,
        record_time = someRecordTime.micros,
        publication_time = 0,
        application_id = completionInfo.applicationId,
        submitters = completionInfo.actAs.toSet,
        command_id = completionInfo.commandId,
        update_id = Some(updateId),
        rejection_status_code = None,
        rejection_status_message = None,
        rejection_status_details = None,
        submission_id = completionInfo.submissionId,
        deduplication_offset = None,
        deduplication_duration_nanos = None,
        deduplication_duration_seconds = None,
        deduplication_start = None,
        domain_id = "x::domain2",
        message_uuid = None,
        request_sequencer_counter = None,
        is_transaction = false,
        trace_context = serializedEmptyTraceContext,
      )
      dtos(5) shouldEqual DbDto.TransactionMeta(
        update_id = updateId,
        event_offset = someOffset.toHexString,
        publication_time = 0,
        record_time = someRecordTime.micros,
        domain_id = "x::domain2",
        event_sequential_id_first = 0,
        event_sequential_id_last = 0,
      )
      Set(dtos(1), dtos(2), dtos(3)) should contain theSameElementsAs Set(
        DbDto.IdFilterAssignStakeholder(0L, createNode.templateId.toString, "signatory"),
        DbDto.IdFilterAssignStakeholder(0L, createNode.templateId.toString, "observer"),
        DbDto.IdFilterAssignStakeholder(0L, createNode.templateId.toString, "observer2"),
      )
      dtos.size shouldEqual 6
    }

    "handle ReassignmentAccepted - Unassign" in {
      val completionInfo = someCompletionInfo
      val builder = TxBuilder()
      val contractId = builder.newCid
      val createNode = builder
        .create(
          id = contractId,
          templateId = "M:T",
          argument = Value.ValueUnit,
          signatories = Set("signatory"),
          observers = Set("observer"),
        )

      val update = state.Update.ReassignmentAccepted(
        optCompletionInfo = Some(completionInfo),
        workflowId = Some(someWorkflowId),
        updateId = updateId,
        recordTime = someRecordTime,
        reassignmentInfo = ReassignmentInfo(
          sourceDomain = Source(DomainId.tryFromString("x::domain1")),
          targetDomain = Target(DomainId.tryFromString("x::domain2")),
          submitter = Option(someParty),
          reassignmentCounter = 1500L,
          hostedStakeholders = Nil,
          unassignId = CantonTimestamp.assertFromLong(1000000000),
          isObservingReassigningParticipant = true,
        ),
        reassignment = Reassignment.Unassign(
          contractId = contractId,
          templateId = createNode.templateId,
          packageName = createNode.packageName,
          stakeholders =
            List("signatory12", "observer23", "asdasdasd").map(Ref.Party.assertFromString),
          assignmentExclusivity = Some(Time.Timestamp.assertFromLong(123456)),
        ),
        domainIndex = Some(
          DomainIndex.of(
            RequestIndex(
              counter = RequestCounter(100),
              sequencerCounter = Some(SequencerCounter(110)),
              timestamp = CantonTimestamp.ofEpochMicro(120),
            )
          )
        ),
      )

      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.EventUnassign(
        event_offset = someOffset.toHexString,
        update_id = update.updateId,
        command_id = Some(completionInfo.commandId),
        workflow_id = Some(someWorkflowId),
        submitter = someParty,
        contract_id = createNode.coid.coid,
        template_id = createNode.templateId.toString,
        package_name = createNode.packageName,
        flat_event_witnesses = Set("signatory12", "observer23", "asdasdasd"),
        event_sequential_id = 0,
        source_domain_id = "x::domain1",
        target_domain_id = "x::domain2",
        unassign_id = "1000000000",
        reassignment_counter = 1500L,
        assignment_exclusivity = Some(123456L),
        trace_context = serializedEmptyTraceContext,
        record_time = someRecordTime.micros,
      )
      dtos(4) shouldEqual DbDto.CommandCompletion(
        completion_offset = someOffset.toHexString,
        record_time = someRecordTime.micros,
        publication_time = 0,
        application_id = completionInfo.applicationId,
        submitters = completionInfo.actAs.toSet,
        command_id = completionInfo.commandId,
        update_id = Some(updateId),
        rejection_status_code = None,
        rejection_status_message = None,
        rejection_status_details = None,
        submission_id = completionInfo.submissionId,
        deduplication_offset = None,
        deduplication_duration_nanos = None,
        deduplication_duration_seconds = None,
        deduplication_start = None,
        domain_id = "x::domain1",
        message_uuid = None,
        request_sequencer_counter = Some(110),
        is_transaction = false,
        trace_context = serializedEmptyTraceContext,
      )
      dtos(5) shouldEqual DbDto.TransactionMeta(
        update_id = updateId,
        event_offset = someOffset.toHexString,
        publication_time = 0,
        record_time = someRecordTime.micros,
        domain_id = "x::domain1",
        event_sequential_id_first = 0,
        event_sequential_id_last = 0,
      )
      Set(dtos(1), dtos(2), dtos(3)) should contain theSameElementsAs Set(
        DbDto.IdFilterUnassignStakeholder(0L, createNode.templateId.toString, "signatory12"),
        DbDto.IdFilterUnassignStakeholder(0L, createNode.templateId.toString, "observer23"),
        DbDto.IdFilterUnassignStakeholder(0L, createNode.templateId.toString, "asdasdasd"),
      )
      dtos.size shouldEqual 6
    }

    "handle TopologyTransactionEffective - PartyToParticipantAuthorization" in {
      val events = Set[TopologyEvent](
        PartyToParticipantAuthorization(
          party = someParty,
          participant = someParticipantId,
          level = Submission,
        ),
        PartyToParticipantAuthorization(
          party = someParty,
          participant = otherParticipantId,
          level = Revoked,
        ),
      )

      val update = state.Update.TopologyTransactionEffective(
        updateId = updateId,
        events = events,
        recordTime = someRecordTime,
        domainId = someDomainId1,
        domainIndex = domainIndexWithSequencerCounter,
      )

      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.EventPartyToParticipant(
        event_sequential_id = 0,
        event_offset = someOffset.toHexString,
        update_id = update.updateId,
        party_id = someParty,
        participant_id = someParticipantId,
        participant_permission = UpdateToDbDto.authorizationLevelToInt(Submission),
        domain_id = someDomainId1.toProtoPrimitive,
        record_time = someRecordTime.micros,
        trace_context = serializedEmptyTraceContext,
      )
      dtos(1) shouldEqual DbDto.EventPartyToParticipant(
        event_sequential_id = 0,
        event_offset = someOffset.toHexString,
        update_id = update.updateId,
        party_id = someParty,
        participant_id = otherParticipantId,
        participant_permission = UpdateToDbDto.authorizationLevelToInt(Revoked),
        domain_id = someDomainId1.toProtoPrimitive,
        record_time = someRecordTime.micros,
        trace_context = serializedEmptyTraceContext,
      )
      dtos(2) shouldEqual DbDto.TransactionMeta(
        update_id = updateId,
        event_offset = someOffset.toHexString,
        publication_time = 0,
        record_time = someRecordTime.micros,
        domain_id = "x::domain1",
        event_sequential_id_first = 0,
        event_sequential_id_last = 0,
      )
      dtos.size shouldEqual 3
    }

    "handle SequencerIndexMoved" in {
      val update = state.Update.SequencerIndexMoved(
        domainId = someDomainId1,
        sequencerIndex = SequencerIndex(
          counter = SequencerCounter(1000),
          timestamp = CantonTimestamp.ofEpochMicro(2000),
        ),
        requestCounterO = None,
      )
      val dtos = updateToDtos(update)

      dtos.head shouldEqual DbDto.SequencerIndexMoved(
        domainId = someDomainId1.toProtoPrimitive
      )
      dtos.size shouldEqual 1
    }

  }

  private def updateToDtos(update: Update) =
    UpdateToDbDto(
      someParticipantId,
      valueSerialization,
      compressionStrategy,
      LedgerApiServerMetrics.ForTesting,
    )(
      MetricsContext.Empty
    )(
      someOffset
    )(Traced[Update](update)).toList
}

object UpdateToDbDtoSpec {
  private val emptyArray = Array.emptyByteArray

  // These tests do not check the correctness of the LF value serialization.
  // All LF values are serialized into empty arrays in this suite.
  private val valueSerialization = new LfValueSerialization {
    override def serialize(
        contractId: ContractId,
        contractArgument: Value.VersionedValue,
    ): Array[Byte] = emptyArray

    /** Returns (contract argument, contract key) */
    override def serialize(create: Create): (Array[Byte], Option[Array[Byte]]) =
      (emptyArray, create.keyOpt.map(_ => emptyArray))

    /** Returns (choice argument, exercise result, contract key) */
    override def serialize(
        eventId: EventId,
        exercise: Exercise,
    ): (Array[Byte], Option[Array[Byte]], Option[Array[Byte]]) =
      (
        emptyArray,
        exercise.exerciseResult.map(_ => emptyArray),
        exercise.keyOpt.map(_ => emptyArray),
      )
  }

  // These test do not check the correctness of compression.
  // All values are compressed using a dummy (identity) algorithm in this suite.
  private val compressionAlgorithmId = Some(123)
  private val compressionStrategy: CompressionStrategy = {
    val noCompression = new FieldCompressionStrategy(compressionAlgorithmId, x => x)
    CompressionStrategy(noCompression, noCompression, noCompression, noCompression)
  }

  private val someParticipantId =
    Ref.ParticipantId.assertFromString("UpdateToDbDtoSpecParticipant")
  private val otherParticipantId =
    Ref.ParticipantId.assertFromString("UpdateToDbDtoSpecRemoteParticipant")
  private val someOffset = Offset.fromHexString(Ref.HexString.assertFromString("abcdef"))
  private val someRecordTime =
    Time.Timestamp.assertFromInstant(Instant.parse(("2000-01-01T00:00:00.000000Z")))
  private val someDomainIndex =
    Some(
      DomainIndex.of(
        RequestIndex(
          counter = RequestCounter(10),
          sequencerCounter = None,
          timestamp = CantonTimestamp(someRecordTime),
        )
      )
    )
  private val domainIndexWithSequencerCounter =
    DomainIndex.of(
      RequestIndex(
        counter = RequestCounter(10),
        sequencerCounter = Some(SequencerCounter(10)),
        timestamp = CantonTimestamp(someRecordTime),
      )
    )
  private val someDomainIndexWithSequencerCounter =
    Some(domainIndexWithSequencerCounter)
  private val someApplicationId =
    Ref.ApplicationId.assertFromString("UpdateToDbDtoSpecApplicationId")
  private val someCommandId = Ref.CommandId.assertFromString("UpdateToDbDtoSpecCommandId")
  private val someSubmissionId =
    Ref.SubmissionId.assertFromString("UpdateToDbDtoSpecSubmissionId")
  private val someWorkflowId = Ref.WorkflowId.assertFromString("UpdateToDbDtoSpecWorkflowId")
  private val someParty = Ref.Party.assertFromString("UpdateToDbDtoSpecParty")
  private val someHash =
    crypto.Hash.assertFromString("01cf85cfeb36d628ca2e6f583fa2331be029b6b28e877e1008fb3f862306c086")
  private val someCompletionInfo = state.CompletionInfo(
    actAs = List(someParty),
    applicationId = someApplicationId,
    commandId = someCommandId,
    optDeduplicationPeriod = None,
    submissionId = Some(someSubmissionId),
    messageUuid = None,
  )
  private val someDomainId1 = DomainId.tryFromString("x::domain1")
  private val someTransactionMeta = state.TransactionMeta(
    ledgerEffectiveTime = Time.Timestamp.assertFromLong(2),
    workflowId = Some(someWorkflowId),
    submissionTime = Time.Timestamp.assertFromLong(3),
    submissionSeed = someHash,
    optUsedPackages = None,
    optNodeSeeds = None,
    optByKeyNodes = None,
  )
  private val someContractDriverMetadata = Bytes.assertFromString("00abcd")

  implicit private val DbDtoEqual: org.scalactic.Equality[DbDto] = DbDtoEq.DbDtoEq

  private val serializedEmptyTraceContext =
    SerializableTraceContext(emptyTraceContext).toDamlProto.toByteArray
}
