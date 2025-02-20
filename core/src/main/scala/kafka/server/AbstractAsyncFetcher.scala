/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import kafka.cluster.BrokerEndPoint
import kafka.common.ClientIdAndBroker
import kafka.log.LogAppendInfo
import kafka.metrics.KafkaMetricsGroup
import kafka.server.AbstractFetcherThread.ResultWithPartitions
import kafka.utils.{DelayedItem, Logging, Pool}
import org.apache.kafka.common.errors._
import org.apache.kafka.common.internals.{KafkaFutureImpl, PartitionStates}
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.{FileRecords, MemoryRecords, Records}
import org.apache.kafka.common.requests.EpochEndOffset._
import org.apache.kafka.common.requests.{EpochEndOffset, FetchRequest, FetchResponse, OffsetsForLeaderEpochRequest}
import org.apache.kafka.common.{InvalidRecordException, TopicPartition}

import scala.collection.JavaConverters._
import scala.collection.{Map, Seq, Set, mutable}
import scala.math.min


sealed trait FetcherEvent extends Comparable[FetcherEvent] {
  def priority: Int // an event with a higher priority value is more important
  def state: FetcherState
  override def compareTo(other: FetcherEvent): Int = {
    // an event with a higher proirity value should be dequeued first in a PriorityQueue
    other.priority.compareTo(this.priority)
  }
}

case class ModifyPartitionsAndGetCount(partitionsToRemove: Set[TopicPartition], partitionsToAdd: Map[TopicPartition, FollowerPartitionStateInFetcher],
  future: KafkaFutureImpl[Int]) extends FetcherEvent {
  override def priority = 2
  override def state = FetcherState.ModifyPartitionsAndGetCount
}

case object TruncateAndFetch extends FetcherEvent {
  override def priority = 1
  override def state = FetcherState.TruncateAndFetch
}


class DelayedFetcherEvent(delay: Long, val fetcherEvent: FetcherEvent) extends DelayedItem(delayMs = delay) {
}

abstract class AbstractAsyncFetcher(clientId: String,
                                    val sourceBroker: BrokerEndPoint,
                                    failedPartitions: FailedPartitions,
                                    fetchBackOffMs: Int = 0,
                                    fetcherEventBus: FetcherEventBus,
                                    fetcherId: Int) extends FetcherEventProcessor with Logging {

  type FetchData = FetchResponse.PartitionData[Records]
  type EpochData = OffsetsForLeaderEpochRequest.PartitionData

  private val partitionStates = new PartitionStates[PartitionFetchState]

  private val metricId = ClientIdAndBroker(clientId, sourceBroker.host, sourceBroker.port)
  val fetcherStats = new AsyncFetcherStats(metricId)
  val fetcherLagStats = new AsyncFetcherLagStats(metricId)

  // process fetched data
  protected def processPartitionData(topicPartition: TopicPartition,
                                     fetchOffset: Long,
                                     partitionData: FetchData): Option[LogAppendInfo]

  protected def truncate(topicPartition: TopicPartition, truncationState: OffsetTruncationState): Unit

  protected def truncateFullyAndStartAt(topicPartition: TopicPartition, offset: Long): Unit

  protected def buildFetch(partitionMap: Map[TopicPartition, PartitionFetchState]): ResultWithPartitions[Option[FetchRequest.Builder]]

  protected def latestEpoch(topicPartition: TopicPartition): Option[Int]

  protected def logEndOffset(topicPartition: TopicPartition): Long

  protected def endOffsetForEpoch(topicPartition: TopicPartition, epoch: Int): Option[OffsetAndEpoch]

  protected def fetchEpochEndOffsets(partitions: Map[TopicPartition, EpochData]): Map[TopicPartition, EpochEndOffset]

  protected def fetchFromLeader(fetchRequest: FetchRequest.Builder): Seq[(TopicPartition, FetchData)]

  protected def fetchEarliestOffsetFromLeader(topicPartition: TopicPartition, currentLeaderEpoch: Int): Long

  protected def fetchLatestOffsetFromLeader(topicPartition: TopicPartition, currentLeaderEpoch: Int): Long

  protected def isOffsetForLeaderEpochSupported: Boolean


  def truncateAndFetch(): Unit = {
    maybeTruncate()
    if (maybeFetch()) {
      fetcherEventBus.schedule(new DelayedFetcherEvent(fetchBackOffMs, TruncateAndFetch))
    } else {
      fetcherEventBus.put(TruncateAndFetch)
    }
  }

  /**
   * maybeFetch returns whether the fetching logic should back off
   */
  private def maybeFetch(): Boolean = {
    val (fetchStates, fetchRequestOpt) = {
      val fetchStates = partitionStates.partitionStateMap.asScala
      val ResultWithPartitions(fetchRequestOpt, partitionsWithError) = buildFetch(fetchStates)

      handlePartitionsWithErrors(partitionsWithError, "maybeFetch")

      (fetchStates, fetchRequestOpt)
    }

    fetchRequestOpt match {
      case None =>
        trace(s"There are no active partitions. Back off for $fetchBackOffMs ms before sending a fetch request")
        true
      case Some(fetchRequest) =>
        processFetchRequest(fetchStates, fetchRequest)
    }
  }

  // deal with partitions with errors, potentially due to leadership changes
  private def handlePartitionsWithErrors(partitions: Iterable[TopicPartition], methodName: String): Unit = {
    if (partitions.nonEmpty) {
      debug(s"Handling errors in $methodName for partitions $partitions")
      delayPartitions(partitions, fetchBackOffMs)
    }
  }

  override def process(event: FetcherEvent): Unit = {
    event match {
      case ModifyPartitionsAndGetCount(partitionsToRemove, partitionsToAdd, future) =>
        // 1. remove partitions
        removePartitions(partitionsToRemove)

        // 2. add partitions that are destined to the current fetcher
        val currentBrokerIdAndFetcherId = BrokerIdAndFetcherId(sourceBroker.id, fetcherId)
        val partitionsToBeAdded = for {
          (topicPartition, partitionStateInFetcher) <- partitionsToAdd
          if partitionStateInFetcher.brokerIdAndFetcherId == currentBrokerIdAndFetcherId
        } yield (topicPartition -> partitionStateInFetcher.offsetAndEpoch)
        addPartitions(partitionsToBeAdded)

        // 3. complete the future with the partitions count
        future.complete(partitionStates.size())
      case TruncateAndFetch =>
        truncateAndFetch()
    }
  }

  /**
   * Builds offset for leader epoch requests for partitions that are in the truncating phase based
   * on latest epochs of the future replicas (the one that is fetching)
   */
  private def fetchTruncatingPartitions(): (Map[TopicPartition, EpochData], Set[TopicPartition]) = {
    val partitionsWithEpochs = mutable.Map.empty[TopicPartition, EpochData]
    val partitionsWithoutEpochs = mutable.Set.empty[TopicPartition]

    partitionStates.stream().forEach(new Consumer[PartitionStates.PartitionState[PartitionFetchState]] {
      override def accept(state: PartitionStates.PartitionState[PartitionFetchState]): Unit = {
        if (state.value.isTruncating) {
          val tp = state.topicPartition
          latestEpoch(tp) match {
            case Some(epoch) if isOffsetForLeaderEpochSupported =>
              partitionsWithEpochs += tp -> new EpochData(Optional.of(state.value.currentLeaderEpoch), epoch)
            case _ =>
              partitionsWithoutEpochs += tp
          }
        }
      }
    })

    (partitionsWithEpochs, partitionsWithoutEpochs)
  }

  private def maybeTruncate(): Unit = {
    val (partitionsWithEpochs, partitionsWithoutEpochs) = fetchTruncatingPartitions()
    if (partitionsWithEpochs.nonEmpty) {
      truncateToEpochEndOffsets(partitionsWithEpochs)
    }
    if (partitionsWithoutEpochs.nonEmpty) {
      truncateToHighWatermark(partitionsWithoutEpochs)
    }
  }

  private def doTruncate(topicPartition: TopicPartition, truncationState: OffsetTruncationState): Boolean = {
    try {
      truncate(topicPartition, truncationState)
      true
    }
    catch {
      case e: KafkaStorageException =>
        error(s"Failed to truncate $topicPartition at offset ${truncationState.offset}", e)
        markPartitionFailed(topicPartition)
        false
      case t: Throwable =>
        error(s"Unexpected error occurred during truncation for $topicPartition "
          + s"at offset ${truncationState.offset}", t)
        markPartitionFailed(topicPartition)
        false
    }
  }

  /**
   * - Build a leader epoch fetch based on partitions that are in the Truncating phase
   * - Send OffsetsForLeaderEpochRequest, retrieving the latest offset for each partition's
   *   leader epoch. This is the offset the follower should truncate to ensure
   *   accurate log replication.
   * - Finally truncate the logs for partitions in the truncating phase and mark the
   *   truncation complete. Do this within a lock to ensure no leadership changes can
   *   occur during truncation.
   */
  private def truncateToEpochEndOffsets(latestEpochsForPartitions: Map[TopicPartition, EpochData]): Unit = {
    val endOffsets = fetchEpochEndOffsets(latestEpochsForPartitions)
    //Ensure we hold a lock during truncation.
      //Check no leadership and no leader epoch changes happened whilst we were unlocked, fetching epochs
    val epochEndOffsets = endOffsets.filter { case (tp, _) =>
      val curPartitionState = partitionStates.stateValue(tp)
      val partitionEpochRequest = latestEpochsForPartitions.get(tp).getOrElse {
        throw new IllegalStateException(
          s"Leader replied with partition $tp not requested in OffsetsForLeaderEpoch request")
      }
      val leaderEpochInRequest = partitionEpochRequest.currentLeaderEpoch.get
      curPartitionState != null && leaderEpochInRequest == curPartitionState.currentLeaderEpoch
    }

    val ResultWithPartitions(fetchOffsets, partitionsWithError) = maybeTruncateToEpochEndOffsets(epochEndOffsets, latestEpochsForPartitions)
    handlePartitionsWithErrors(partitionsWithError, "truncateToEpochEndOffsets")
    updateFetchOffsetAndMaybeMarkTruncationComplete(fetchOffsets)
  }

  // Visible for testing
  private[server] def truncateToHighWatermark(partitions: Set[TopicPartition]): Unit = {
    val fetchOffsets = mutable.HashMap.empty[TopicPartition, OffsetTruncationState]

    for (tp <- partitions) {
      val partitionState = partitionStates.stateValue(tp)
      if (partitionState != null) {
        val highWatermark = partitionState.fetchOffset
        val truncationState = OffsetTruncationState(highWatermark, truncationCompleted = true)

        info(s"Truncating partition $tp to local high watermark $highWatermark")
        if (doTruncate(tp, truncationState))
          fetchOffsets.put(tp, truncationState)
      }
    }

    updateFetchOffsetAndMaybeMarkTruncationComplete(fetchOffsets)
  }

  private def maybeTruncateToEpochEndOffsets(fetchedEpochs: Map[TopicPartition, EpochEndOffset],
                                             latestEpochsForPartitions: Map[TopicPartition, EpochData]): ResultWithPartitions[Map[TopicPartition, OffsetTruncationState]] = {
    val fetchOffsets = mutable.HashMap.empty[TopicPartition, OffsetTruncationState]
    val partitionsWithError = mutable.HashSet.empty[TopicPartition]

    fetchedEpochs.foreach { case (tp, leaderEpochOffset) =>
      leaderEpochOffset.error match {
        case Errors.NONE =>
          val offsetTruncationState = getOffsetTruncationState(tp, leaderEpochOffset)
          if(doTruncate(tp, offsetTruncationState))
            fetchOffsets.put(tp, offsetTruncationState)

        case Errors.FENCED_LEADER_EPOCH =>
          if (onPartitionFenced(tp, latestEpochsForPartitions.get(tp).flatMap {
            p =>
              if (p.currentLeaderEpoch.isPresent) Some(p.currentLeaderEpoch.get())
              else None
          })) partitionsWithError += tp

        case error =>
          info(s"Retrying leaderEpoch request for partition $tp as the leader reported an error: $error")
          partitionsWithError += tp
      }
    }

    ResultWithPartitions(fetchOffsets, partitionsWithError)
  }

  /**
   * remove the partition if the partition state is NOT updated. Otherwise, keep the partition active.
   * @return true if the epoch in this thread is updated. otherwise, false
   */
  private def onPartitionFenced(tp: TopicPartition, requestEpoch: Option[Int]): Boolean = {
    Option(partitionStates.stateValue(tp)).exists { currentFetchState =>
      val currentLeaderEpoch = currentFetchState.currentLeaderEpoch
      if (requestEpoch.contains(currentLeaderEpoch)) {
        info(s"Partition $tp has an older epoch ($currentLeaderEpoch) than the current leader. Will await " +
          s"the new LeaderAndIsr state before resuming fetching.")
        markPartitionFailed(tp)
        false
      } else {
        info(s"Partition $tp has an new epoch ($currentLeaderEpoch) than the current leader. retry the partition later")
        true
      }
    }
  }

  /**
   * processFetchRequest fetches data from the leader and returns whether the fetching logic should back off
   * @param fetchStates
   * @param fetchRequest
   */
  private def processFetchRequest(fetchStates: Map[TopicPartition, PartitionFetchState],
                                  fetchRequest: FetchRequest.Builder): Boolean = {
    val partitionsWithError = mutable.Set[TopicPartition]()
    var responseData: Seq[(TopicPartition, FetchData)] = Seq.empty

    try {
      trace(s"Sending fetch request $fetchRequest")
      responseData = fetchFromLeader(fetchRequest)
    } catch {
      case t: Throwable =>
        warn(s"Error in response for fetch request $fetchRequest", t)
        fetcherStats.requestFailureRate.mark()

        partitionsWithError ++= partitionStates.partitionSet.asScala
        // there is an error occurred while fetching partitions, sleep a while
        // note that `ReplicaFetcherThread.handlePartitionsWithError` will also introduce the same delay for every
        // partition with error effectively doubling the delay. It would be good to improve this.
        //partitionMapCond.await(fetchBackOffMs, TimeUnit.MILLISECONDS)
        return true
    }
    fetcherStats.requestRate.mark()

    if (responseData.nonEmpty) {
      // process fetched data
        responseData.foreach { case (topicPartition, partitionData) =>
          Option(partitionStates.stateValue(topicPartition)).foreach { currentFetchState =>
            // It's possible that a partition is removed and re-added or truncated when there is a pending fetch request.
            // In this case, we only want to process the fetch response if the partition state is ready for fetch and
            // the current offset is the same as the offset requested.
            val fetchState = fetchStates(topicPartition)
            if (fetchState.fetchOffset == currentFetchState.fetchOffset && currentFetchState.isReadyForFetch) {
              val requestEpoch = if (fetchState.currentLeaderEpoch >= 0)
                Some(fetchState.currentLeaderEpoch)
              else
                None
              partitionData.error match {
                case Errors.NONE =>
                  try {
                    // Once we hand off the partition data to the subclass, we can't mess with it any more in this thread
                    val logAppendInfoOpt = processPartitionData(topicPartition, currentFetchState.fetchOffset,
                      partitionData)

                    logAppendInfoOpt.foreach { logAppendInfo =>
                      val validBytes = logAppendInfo.validBytes
                      val nextOffset = if (validBytes > 0) logAppendInfo.lastOffset + 1 else currentFetchState.fetchOffset
                      fetcherLagStats.getAndMaybePut(topicPartition).lag = Math.max(0L, partitionData.highWatermark - nextOffset)

                      // ReplicaDirAlterThread may have removed topicPartition from the partitionStates after processing the partition data
                      if (validBytes > 0 && partitionStates.contains(topicPartition)) {
                        // Update partitionStates only if there is no exception during processPartitionData
                        val newFetchState = PartitionFetchState(nextOffset, fetchState.currentLeaderEpoch,
                          state = Fetching)
                        partitionStates.updateAndMoveToEnd(topicPartition, newFetchState)
                        fetcherStats.byteRate.mark(validBytes)
                      }
                    }
                  } catch {
                    case ime@( _: CorruptRecordException | _: InvalidRecordException) =>
                      // we log the error and continue. This ensures two things
                      // 1. If there is a corrupt message in a topic partition, it does not bring the fetcher thread
                      //    down and cause other topic partition to also lag
                      // 2. If the message is corrupt due to a transient state in the log (truncation, partial writes
                      //    can cause this), we simply continue and should get fixed in the subsequent fetches
                      error(s"Found invalid messages during fetch for partition $topicPartition " +
                        s"offset ${currentFetchState.fetchOffset}", ime)
                      partitionsWithError += topicPartition
                    case e: KafkaStorageException =>
                      error(s"Error while processing data for partition $topicPartition " +
                        s"at offset ${currentFetchState.fetchOffset}", e)
                      markPartitionFailed(topicPartition)
                    case t: Throwable =>
                      // stop monitoring this partition and add it to the set of failed partitions
                      error(s"Unexpected error occurred while processing data for partition $topicPartition " +
                        s"at offset ${currentFetchState.fetchOffset}", t)
                      markPartitionFailed(topicPartition)
                  }
                case Errors.OFFSET_OUT_OF_RANGE =>
                  if (handleOutOfRangeError(topicPartition, currentFetchState, requestEpoch))
                    partitionsWithError += topicPartition

                case Errors.UNKNOWN_LEADER_EPOCH =>
                  debug(s"Remote broker has a smaller leader epoch for partition $topicPartition than " +
                    s"this replica's current leader epoch of ${fetchState.currentLeaderEpoch}.")
                  partitionsWithError += topicPartition

                case Errors.FENCED_LEADER_EPOCH =>
                  if (onPartitionFenced(topicPartition, requestEpoch)) partitionsWithError += topicPartition

                case Errors.NOT_LEADER_FOR_PARTITION =>
                  debug(s"Remote broker is not the leader for partition $topicPartition, which could indicate " +
                    "that the partition is being moved")
                  partitionsWithError += topicPartition

                case Errors.UNKNOWN_TOPIC_OR_PARTITION =>
                  warn(s"Remote broker does not host the partition $topicPartition, which could indicate " +
                    "that the partition is being created or deleted.")
                  partitionsWithError += topicPartition

                case _ =>
                  error(s"Error for partition $topicPartition at offset ${currentFetchState.fetchOffset}",
                    partitionData.error.exception)
                  partitionsWithError += topicPartition
              }
            }
          }
        }

    }

    if (partitionsWithError.nonEmpty) {
      handlePartitionsWithErrors(partitionsWithError, "processFetchRequest")
    }
    false
  }

  private def markPartitionFailed(topicPartition: TopicPartition): Unit = {
    failedPartitions.add(topicPartition)
    removePartitions(Set(topicPartition))
    warn(s"Partition $topicPartition marked as failed")
  }

  def addPartitions(initialFetchStates: Map[TopicPartition, OffsetAndEpoch]): Set[TopicPartition] = {
    failedPartitions.removeAll(initialFetchStates.keySet)

    initialFetchStates.foreach { case (tp, initialFetchState) =>
      // We can skip the truncation step iff the leader epoch matches the existing epoch
      val currentState = partitionStates.stateValue(tp)
      val updatedState = if (currentState != null && currentState.currentLeaderEpoch == initialFetchState.leaderEpoch) {
        currentState
      } else {
        val initialFetchOffset = if (initialFetchState.offset < 0)
          fetchOffsetAndTruncate(tp, initialFetchState.leaderEpoch)
        else
          initialFetchState.offset
        PartitionFetchState(initialFetchOffset, initialFetchState.leaderEpoch, state = Truncating)
      }
      partitionStates.updateAndMoveToEnd(tp, updatedState)
    }

    initialFetchStates.keySet
  }

  /**
   * Loop through all partitions, updating their fetch offset and maybe marking them as
   * truncation completed if their offsetTruncationState indicates truncation completed
   *
   * @param fetchOffsets the partitions to update fetch offset and maybe mark truncation complete
   */
  private def updateFetchOffsetAndMaybeMarkTruncationComplete(fetchOffsets: Map[TopicPartition, OffsetTruncationState]): Unit = {
    val newStates: Map[TopicPartition, PartitionFetchState] = partitionStates.partitionStates.asScala
      .map { state =>
        val currentFetchState = state.value
        val maybeTruncationComplete = fetchOffsets.get(state.topicPartition) match {
          case Some(offsetTruncationState) =>
            val state = if (offsetTruncationState.truncationCompleted) Fetching else Truncating
            PartitionFetchState(offsetTruncationState.offset, currentFetchState.currentLeaderEpoch,
              currentFetchState.delay, state)
          case None => currentFetchState
        }
        (state.topicPartition, maybeTruncationComplete)
      }.toMap
    partitionStates.set(newStates.asJava)
  }

  /**
   * Called from maybeTruncate for each topic partition.
   * Returns truncation offset and whether this is the final offset to truncate to
   *
   * For each topic partition, the offset to truncate to is calculated based on leader's returned
   * epoch and offset:
   *  -- If the leader replied with undefined epoch offset, we must use the high watermark. This can
   *  happen if 1) the leader is still using message format older than KAFKA_0_11_0; 2) the follower
   *  requested leader epoch < the first leader epoch known to the leader.
   *  -- If the leader replied with the valid offset but undefined leader epoch, we truncate to
   *  leader's offset if it is lower than follower's Log End Offset. This may happen if the
   *  leader is on the inter-broker protocol version < KAFKA_2_0_IV0
   *  -- If the leader replied with leader epoch not known to the follower, we truncate to the
   *  end offset of the largest epoch that is smaller than the epoch the leader replied with, and
   *  send OffsetsForLeaderEpochRequest with that leader epoch. In a more rare case, where the
   *  follower was not tracking epochs smaller than the epoch the leader replied with, we
   *  truncate the leader's offset (and do not send any more leader epoch requests).
   *  -- Otherwise, truncate to min(leader's offset, end offset on the follower for epoch that
   *  leader replied with, follower's Log End Offset).
   *
   * @param tp                    Topic partition
   * @param leaderEpochOffset     Epoch end offset received from the leader for this topic partition
   */
  private def getOffsetTruncationState(tp: TopicPartition,
                                       leaderEpochOffset: EpochEndOffset): OffsetTruncationState = {
    if (leaderEpochOffset.endOffset == UNDEFINED_EPOCH_OFFSET) {
      // truncate to initial offset which is the high watermark for follower replica. For
      // future replica, it is either high watermark of the future replica or current
      // replica's truncation offset (when the current replica truncates, it forces future
      // replica's partition state to 'truncating' and sets initial offset to its truncation offset)
      warn(s"Based on replica's leader epoch, leader replied with an unknown offset in $tp. " +
        s"The initial fetch offset ${partitionStates.stateValue(tp).fetchOffset} will be used for truncation.")
      OffsetTruncationState(partitionStates.stateValue(tp).fetchOffset, truncationCompleted = true)
    } else if (leaderEpochOffset.leaderEpoch == UNDEFINED_EPOCH) {
      // either leader or follower or both use inter-broker protocol version < KAFKA_2_0_IV0
      // (version 0 of OffsetForLeaderEpoch request/response)
      warn(s"Leader or replica is on protocol version where leader epoch is not considered in the OffsetsForLeaderEpoch response. " +
        s"The leader's offset ${leaderEpochOffset.endOffset} will be used for truncation in $tp.")
      OffsetTruncationState(min(leaderEpochOffset.endOffset, logEndOffset(tp)), truncationCompleted = true)
    } else {
      val replicaEndOffset = logEndOffset(tp)

      // get (leader epoch, end offset) pair that corresponds to the largest leader epoch
      // less than or equal to the requested epoch.
      endOffsetForEpoch(tp, leaderEpochOffset.leaderEpoch) match {
        case Some(OffsetAndEpoch(followerEndOffset, followerEpoch)) =>
          if (followerEpoch != leaderEpochOffset.leaderEpoch) {
            // the follower does not know about the epoch that leader replied with
            // we truncate to the end offset of the largest epoch that is smaller than the
            // epoch the leader replied with, and send another offset for leader epoch request
            val intermediateOffsetToTruncateTo = min(followerEndOffset, replicaEndOffset)
            info(s"Based on replica's leader epoch, leader replied with epoch ${leaderEpochOffset.leaderEpoch} " +
              s"unknown to the replica for $tp. " +
              s"Will truncate to $intermediateOffsetToTruncateTo and send another leader epoch request to the leader.")
            OffsetTruncationState(intermediateOffsetToTruncateTo, truncationCompleted = false)
          } else {
            val offsetToTruncateTo = min(followerEndOffset, leaderEpochOffset.endOffset)
            OffsetTruncationState(min(offsetToTruncateTo, replicaEndOffset), truncationCompleted = true)
          }
        case None =>
          // This can happen if the follower was not tracking leader epochs at that point (before the
          // upgrade, or if this broker is new). Since the leader replied with epoch <
          // requested epoch from follower, so should be safe to truncate to leader's
          // offset (this is the same behavior as post-KIP-101 and pre-KIP-279)
          warn(s"Based on replica's leader epoch, leader replied with epoch ${leaderEpochOffset.leaderEpoch} " +
            s"below any replica's tracked epochs for $tp. " +
            s"The leader's offset only ${leaderEpochOffset.endOffset} will be used for truncation.")
          OffsetTruncationState(min(leaderEpochOffset.endOffset, replicaEndOffset), truncationCompleted = true)
      }
    }
  }

  /**
   * Handle the out of range error. Return false if
   * 1) the request succeeded or
   * 2) was fenced and this thread haven't received new epoch,
   * which means we need not backoff and retry. True if there was a retriable error.
   */
  private def handleOutOfRangeError(topicPartition: TopicPartition,
                                    fetchState: PartitionFetchState,
                                    requestEpoch: Option[Int]): Boolean = {
    try {
      val newOffset = fetchOffsetAndTruncate(topicPartition, fetchState.currentLeaderEpoch)
      val newFetchState = PartitionFetchState(newOffset, fetchState.currentLeaderEpoch, state = Fetching)
      partitionStates.updateAndMoveToEnd(topicPartition, newFetchState)
      info(s"Current offset ${fetchState.fetchOffset} for partition $topicPartition is " +
        s"out of range, which typically implies a leader change. Reset fetch offset to $newOffset")
      false
    } catch {
      case _: FencedLeaderEpochException =>
        onPartitionFenced(topicPartition, requestEpoch)

      case e @ (_ : UnknownTopicOrPartitionException |
                _ : UnknownLeaderEpochException |
                _ : NotLeaderForPartitionException) =>
        info(s"Could not fetch offset for $topicPartition due to error: ${e.getMessage}")
        true

      case e: Throwable =>
        error(s"Error getting offset for partition $topicPartition", e)
        true
    }
  }

  /**
   * Handle a partition whose offset is out of range and return a new fetch offset.
   */
  protected def fetchOffsetAndTruncate(topicPartition: TopicPartition, currentLeaderEpoch: Int): Long = {
    val replicaEndOffset = logEndOffset(topicPartition)

    /**
     * Unclean leader election: A follower goes down, in the meanwhile the leader keeps appending messages. The follower comes back up
     * and before it has completely caught up with the leader's logs, all replicas in the ISR go down. The follower is now uncleanly
     * elected as the new leader, and it starts appending messages from the client. The old leader comes back up, becomes a follower
     * and it may discover that the current leader's end offset is behind its own end offset.
     *
     * In such a case, truncate the current follower's log to the current leader's end offset and continue fetching.
     *
     * There is a potential for a mismatch between the logs of the two replicas here. We don't fix this mismatch as of now.
     */
    val leaderEndOffset = fetchLatestOffsetFromLeader(topicPartition, currentLeaderEpoch)
    if (leaderEndOffset < replicaEndOffset) {
      warn(s"Reset fetch offset for partition $topicPartition from $replicaEndOffset to current " +
        s"leader's latest offset $leaderEndOffset")
      truncate(topicPartition, OffsetTruncationState(leaderEndOffset, truncationCompleted = true))
      leaderEndOffset
    } else {
      /**
       * If the leader's log end offset is greater than the follower's log end offset, there are two possibilities:
       * 1. The follower could have been down for a long time and when it starts up, its end offset could be smaller than the leader's
       * start offset because the leader has deleted old logs (log.logEndOffset < leaderStartOffset).
       * 2. When unclean leader election occurs, it is possible that the old leader's high watermark is greater than
       * the new leader's log end offset. So when the old leader truncates its offset to its high watermark and starts
       * to fetch from the new leader, an OffsetOutOfRangeException will be thrown. After that some more messages are
       * produced to the new leader. While the old leader is trying to handle the OffsetOutOfRangeException and query
       * the log end offset of the new leader, the new leader's log end offset becomes higher than the follower's log end offset.
       *
       * In the first case, the follower's current log end offset is smaller than the leader's log start offset. So the
       * follower should truncate all its logs, roll out a new segment and start to fetch from the current leader's log
       * start offset.
       * In the second case, the follower should just keep the current log segments and retry the fetch. In the second
       * case, there will be some inconsistency of data between old and new leader. We are not solving it here.
       * If users want to have strong consistency guarantees, appropriate configurations needs to be set for both
       * brokers and producers.
       *
       * Putting the two cases together, the follower should fetch from the higher one of its replica log end offset
       * and the current leader's log start offset.
       */
      val leaderStartOffset = fetchEarliestOffsetFromLeader(topicPartition, currentLeaderEpoch)
      warn(s"Reset fetch offset for partition $topicPartition from $replicaEndOffset to current " +
        s"leader's start offset $leaderStartOffset")
      val offsetToFetch = Math.max(leaderStartOffset, replicaEndOffset)
      // Only truncate log when current leader's log start offset is greater than follower's log end offset.
      if (leaderStartOffset > replicaEndOffset)
        truncateFullyAndStartAt(topicPartition, leaderStartOffset)
      offsetToFetch
    }
  }

  def delayPartitions(partitions: Iterable[TopicPartition], delay: Long): Unit = {
    for (partition <- partitions) {
      Option(partitionStates.stateValue(partition)).foreach { currentFetchState =>
        if (!currentFetchState.isDelayed) {
          partitionStates.updateAndMoveToEnd(partition, PartitionFetchState(currentFetchState.fetchOffset,
            currentFetchState.currentLeaderEpoch, new DelayedItem(delay), currentFetchState.state))
        }
      }
    }
  }

  def removePartitions(topicPartitions: Set[TopicPartition]): Unit = {
    topicPartitions.foreach { topicPartition =>
      partitionStates.remove(topicPartition)
      fetcherLagStats.unregister(topicPartition)
    }
  }

  protected def toMemoryRecords(records: Records): MemoryRecords = {
    records match {
      case r: MemoryRecords => r
      case r: FileRecords =>
        val buffer = ByteBuffer.allocate(r.sizeInBytes)
        r.readInto(buffer, 0)
        MemoryRecords.readableRecords(buffer)
    }
  }
}


class AsyncFetcherStats(metricId: ClientIdAndBroker) extends KafkaMetricsGroup {
  val tags = Map("clientId" -> metricId.clientId,
    "brokerHost" -> metricId.brokerHost,
    "brokerPort" -> metricId.brokerPort.toString)

  val requestRate = newMeter(FetcherMetrics.RequestsPerSec, "requests", TimeUnit.SECONDS, tags)

  val requestFailureRate = newMeter(FetcherMetrics.RequestFailuresPerSec, "requestFailures", TimeUnit.SECONDS, tags)

  val byteRate = newMeter(FetcherMetrics.BytesPerSec, "bytes", TimeUnit.SECONDS, tags)

  def unregister(): Unit = {
    removeMetric(FetcherMetrics.RequestsPerSec, tags)
    removeMetric(FetcherMetrics.RequestFailuresPerSec, tags)
    removeMetric(FetcherMetrics.BytesPerSec, tags)
  }

}

class AsyncFetcherLagStats(metricId: ClientIdAndBroker) {
  private val valueFactory = (k: ClientIdTopicPartition) => new FetcherLagMetrics(k)
  val stats = new Pool[ClientIdTopicPartition, FetcherLagMetrics](Some(valueFactory))

  def getAndMaybePut(topicPartition: TopicPartition): FetcherLagMetrics = {
    stats.getAndMaybePut(ClientIdTopicPartition(metricId.clientId, topicPartition))
  }

  def isReplicaInSync(topicPartition: TopicPartition): Boolean = {
    val fetcherLagMetrics = stats.get(ClientIdTopicPartition(metricId.clientId, topicPartition))
    if (fetcherLagMetrics != null)
      fetcherLagMetrics.lag <= 0
    else
      false
  }

  def unregister(topicPartition: TopicPartition): Unit = {
    val lagMetrics = stats.remove(ClientIdTopicPartition(metricId.clientId, topicPartition))
    if (lagMetrics != null) lagMetrics.unregister()
  }

  def unregister(): Unit = {
    stats.keys.toBuffer.foreach { key: ClientIdTopicPartition =>
      unregister(key.topicPartition)
    }
  }
}
