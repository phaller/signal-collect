/*
 *  @author Philip Stutz
 *  @author Francisco de Freitas
 *  @author Daniel Strebel
 *  
 *  
 *  Copyright 2012 University of Zurich
 *      
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 */

package com.signalcollect.worker

import com.signalcollect.configuration._
import java.util.concurrent.TimeUnit
import com.signalcollect.interfaces._
import java.util.concurrent.BlockingQueue
import java.util.HashSet
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.LinkedHashMap
import java.util.Map
import java.util.Set
import com.signalcollect._
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.ReceiveTimeout
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import com.signalcollect.interfaces.LogMessage
import com.signalcollect.messaging.Request
import scala.concurrent.Future
import scala.concurrent.Promise
import akka.dispatch.MessageQueue
import com.signalcollect.messaging.Request
import scala.collection.mutable.IndexedSeq
import collection.JavaConversions._
import scala.reflect.ClassTag
import scala.language.reflectiveCalls

class WorkerOperationCounters(
  var messagesReceived: Long = 0l,
  var collectOperationsExecuted: Long = 0l,
  var signalOperationsExecuted: Long = 0l,
  var verticesAdded: Long = 0l,
  var verticesRemoved: Long = 0l,
  var outgoingEdgesAdded: Long = 0l,
  var outgoingEdgesRemoved: Long = 0l,
  var signalSteps: Long = 0l,
  var collectSteps: Long = 0l)

object MaySignal extends Serializable

class AkkaWorker[@specialized(Int, Long) Id: ClassTag, @specialized(Int, Long, Float, Double) Signal: ClassTag](
  val workerId: Int,
  val numberOfWorkers: Int,
  val messageBusFactory: MessageBusFactory,
  val storageFactory: StorageFactory,
  val statusUpdateIntervalInMillis: Long,
  val loggingLevel: Int)
    extends Worker[Id, Signal] with ActorLogging {

  override def toString = "Worker" + workerId

  val messageBus: MessageBus[Id, Signal] = {
    messageBusFactory.createInstance[Id, Signal](numberOfWorkers)
  }

  /**
   * Timeout for Akka actor idling
   */
  context.setReceiveTimeout(5.milliseconds)

  def isInitialized = messageBus.isInitialized

  /**
   * This method gets executed when the Akka actor receives a message.
   */
  def receive = {

    case PoisonPill =>
      shutdown

    /**
     * ReceiveTimeout message only gets sent after Akka actor mailbox has been empty for "receiveTimeout" milliseconds
     */
    case ReceiveTimeout =>
      if (isConverged || isPaused) { // if the actor has nothing to compute and the mailbox is empty, then it is idle
        setIdle(true)
      } else {
        handlePauseAndContinue
        performComputations
      }

    case msg =>
      setIdle(false)
      process(msg) // process the message
      handlePauseAndContinue
      performComputations
  }

  val messageQueue: MessageQueue = context.asInstanceOf[{ def mailbox: { def messageQueue: MessageQueue } }].mailbox.messageQueue

  def performComputations {
    val currentTime = System.currentTimeMillis
    if (currentTime - lastStatusUpdate > updateInterval) {
      lastStatusUpdate = currentTime
      sendStatusToCoordinator
    }
    if (!isPaused) {
      scheduleOperations
    }
  }

  def scheduleOperations {
    while (!messageQueue.hasMessages && !isConverged) {
      vertexStore.toSignal.process(executeSignalOperationOfVertex(_))
      vertexStore.toCollect.process(executeCollectOperationOfVertex(_))
    }
  }

  protected val counters = new WorkerOperationCounters()
  protected val graphEditor: GraphEditor[Id, Signal] = new WorkerGraphEditor(this, messageBus)
  protected val vertexGraphEditor = graphEditor.asInstanceOf[GraphEditor[Any, Any]] // Vertex graph edits are not typesafe.
  protected var undeliverableSignalHandler: (Signal, Id, Option[Id], GraphEditor[Id, Signal]) => Unit = (s, tId, sId, ge) => {}

  protected val updateInterval: Long = statusUpdateIntervalInMillis

  var maySignal = false
  var awaitingSignalPermission = false

  protected def process(message: Any) {

    counters.messagesReceived += 1
    message match {
      case s: SignalMessage[Id, Signal] =>
        processSignal(s.signal, s.targetId, s.sourceId)
      case bulkSignal: BulkSignal[Id, Signal] =>
        val size = bulkSignal.signals.length
        var i = 0
        while (i < size) {
          processSignal(bulkSignal.signals(i), bulkSignal.targetIds(i), None)
          i += 1
        }
      case MaySignal =>
        maySignal = true
        awaitingSignalPermission = false
      case Request(command, reply) =>
        try {
          val result = command.asInstanceOf[Worker[Id, Signal] => Any](this)
          if (reply) {
            if (result == null) { // Netty does not like null messages: org.jboss.netty.channel.socket.nio.NioWorker - WARNING: Unexpected exception in the selector loop. - java.lang.NullPointerException 
              messageBus.sendToActor(sender, None)
            } else {
              messageBus.sendToActor(sender, result)
            }
          }
        } catch {
          case e: Exception =>
            severe(e)
            throw e
        }
      case other => warning("Could not handle message " + message)
    }
  }

  def addVertex(vertex: Vertex[Id, _]) {
    if (vertexStore.vertices.put(vertex)) {
      counters.verticesAdded += 1
      counters.outgoingEdgesAdded += vertex.edgeCount
      vertex.afterInitialization(vertexGraphEditor)
      if (vertex.scoreSignal > signalThreshold) {
        vertexStore.toSignal.put(vertex)
      }
    }
  }

  def addEdge(sourceId: Id, edge: Edge[Id]) {
    val vertex = vertexStore.vertices.get(sourceId)
    if (vertex != null) {
      if (vertex.addEdge(edge, vertexGraphEditor)) {
        counters.outgoingEdgesAdded += 1
        if (vertex.scoreSignal > signalThreshold) {
          vertexStore.toSignal.put(vertex)
        }
      }
    } else {
      warning("Did not find vertex with id " + sourceId + " when trying to add outgoing edge (" + sourceId + ", " + edge.targetId + ")")
    }
  }

  def removeEdge(edgeId: EdgeId[Id]) {
    val vertex = vertexStore.vertices.get(edgeId.sourceId)
    if (vertex != null) {
      if (vertex.removeEdge(edgeId.targetId, vertexGraphEditor)) {
        counters.outgoingEdgesRemoved += 1
        if (vertex.scoreSignal > signalThreshold) {
          vertexStore.toSignal.put(vertex)
        }
      } else {
        warning("Outgoing edge not found when trying to remove edge with id " + edgeId)
      }
    } else {
      warning("Source vertex not found found when trying to remove outgoing edge with id " + edgeId)
    }
  }

  def removeVertex(vertexId: Id) {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      processRemoveVertex(vertex)
    } else {
      warning("Should remove vertex with id " + vertexId + ": could not find this vertex.")
    }
  }

  protected def processRemoveVertex(vertex: Vertex[Id, _]) {
    val edgesRemoved = vertex.removeAllEdges(vertexGraphEditor)
    counters.outgoingEdgesRemoved += edgesRemoved
    counters.verticesRemoved += 1
    vertex.beforeRemoval(vertexGraphEditor)
    vertexStore.vertices.remove(vertex.id)
  }

  def loadGraph(graphLoader: GraphEditor[Id, Signal] => Unit) {
    graphLoader(graphEditor)
  }

  def setUndeliverableSignalHandler(h: (Signal, Id, Option[Id], GraphEditor[Id, Signal]) => Unit) {
    undeliverableSignalHandler = h
  }

  def setSignalThreshold(st: Double) {
    signalThreshold = st
  }

  def setCollectThreshold(ct: Double) {
    collectThreshold = ct
  }

  def recalculateScores {
    vertexStore.vertices.foreach(recalculateVertexScores(_))
  }

  def recalculateScoresForVertexWithId(vertexId: Id) {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      recalculateVertexScores(vertex)
    }
  }

  protected def recalculateVertexScores(vertex: Vertex[Id, _]) {
    vertexStore.toCollect.put(vertex)
    vertexStore.toSignal.put(vertex)
  }

  def forVertexWithId[VertexType <: Vertex[Id, _], ResultType](vertexId: Id, f: VertexType => ResultType): ResultType = {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      val result = f(vertex.asInstanceOf[VertexType])
      result
    } else {
      throw new Exception("Vertex with id " + vertexId + " not found.")
    }
  }

  def foreachVertex(f: Vertex[Id, _] => Unit) {
    vertexStore.vertices.foreach(f)
  }

  def aggregate[ValueType](aggregationOperation: AggregationOperation[ValueType]): ValueType = {
    var accumulator = aggregationOperation.neutralElement
    for (vertex <- vertexStore.vertices) {
      accumulator = aggregationOperation.aggregate(accumulator, aggregationOperation.extract(vertex))
    }
    accumulator
  }

  def startAsynchronousComputation {
    shouldStart = true
  }

  def pauseAsynchronousComputation {
    shouldPause = true
  }

  def signalStep {
    counters.signalSteps += 1
    vertexStore.toSignal.process(executeSignalOperationOfVertex(_))
    messageBus.flush
  }

  def collectStep: Boolean = {
    counters.collectSteps += 1
    vertexStore.toCollect.process(executeCollectOperationOfVertex(_))
    vertexStore.toSignal.isEmpty
  }

  def getWorkerStatistics: WorkerStatistics = {
    WorkerStatistics(
      workerId = workerId,
      messagesReceived = counters.messagesReceived,
      messagesSent = messageBus.messagesSent.map(c => c.toLong),
      toSignalSize = vertexStore.toSignal.size,
      toCollectSize = vertexStore.toCollect.size,
      collectOperationsExecuted = counters.collectOperationsExecuted,
      signalOperationsExecuted = counters.signalOperationsExecuted,
      numberOfVertices = vertexStore.vertices.size,
      verticesAdded = counters.verticesAdded,
      verticesRemoved = counters.verticesRemoved,
      numberOfOutgoingEdges = counters.outgoingEdgesAdded - counters.outgoingEdgesRemoved, //only valid if no edges are removed during execution
      outgoingEdgesAdded = counters.outgoingEdgesAdded,
      outgoingEdgesRemoved = counters.outgoingEdgesRemoved)
  }

  def shutdown {}

  protected var shouldShutdown = false
  protected var isIdle = false
  protected var isPaused = true
  protected var shouldPause = false
  protected var shouldStart = false

  protected var signalThreshold = 0.001
  protected var collectThreshold = 0.0

  protected var lastStatusUpdate = System.currentTimeMillis

  protected val vertexStore = storageFactory.createInstance[Id]

  protected def isConverged =
    vertexStore.toCollect.isEmpty && vertexStore.toSignal.isEmpty

  protected def getWorkerStatus: WorkerStatus = {
    WorkerStatus(
      workerId = workerId,
      isIdle = isIdle,
      isPaused = isPaused,
      messagesSent = messageBus.messagesSent,
      messagesReceived = counters.messagesReceived)
  }

  protected def sendStatusToCoordinator {
    val status = getWorkerStatus
    messageBus.sendToCoordinator(status)
  }

  protected def setIdle(newIdleState: Boolean) {
    if (isInitialized && isIdle != newIdleState) {
      isIdle = newIdleState
      sendStatusToCoordinator
    }
  }

  protected def executeCollectOperationOfVertex(vertex: Vertex[Id, _], addToSignal: Boolean = true) {
    counters.collectOperationsExecuted += 1
    vertex.executeCollectOperation(vertexGraphEditor)
    if (addToSignal && vertex.scoreSignal > signalThreshold) {
      vertexStore.toSignal.put(vertex)
    }
  }

  protected def executeSignalOperationOfVertex(vertex: Vertex[Id, _]) {
    counters.signalOperationsExecuted += 1
    vertex.executeSignalOperation(vertexGraphEditor)
  }

  def processSignal(signal: Signal, targetId: Id, sourceId: Option[Id]) {
    val vertex = vertexStore.vertices.get(targetId)
    if (vertex != null) {
      if (vertex.deliverSignal(signal, sourceId)) {
        counters.collectOperationsExecuted += 1
        if (vertex.scoreSignal > signalThreshold) {
          vertexStore.toSignal.put(vertex)
        }
      } else {
        vertexStore.toCollect.put(vertex)
      }
    } else {
      undeliverableSignalHandler(signal, targetId, sourceId, graphEditor)
    }
  }

  def registerWorker(workerId: Int, worker: ActorRef) {
    messageBus.registerWorker(workerId, worker)
  }

  def registerCoordinator(coordinator: ActorRef) {
    messageBus.registerCoordinator(coordinator)
  }

  def registerLogger(logger: ActorRef) {
    messageBus.registerLogger(logger)
  }

  protected def handlePauseAndContinue {
    if (shouldStart) {
      shouldStart = false
      isPaused = false
      sendStatusToCoordinator
    } else if (shouldPause) {
      shouldPause = false
      isPaused = true
      sendStatusToCoordinator
    }
  }

}