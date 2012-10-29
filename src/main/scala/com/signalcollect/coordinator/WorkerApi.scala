/*
 *  @author Philip Stutz
 *  
 *  Copyright 2011 University of Zurich
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

package com.signalcollect.coordinator

import com.signalcollect.interfaces._
import com.signalcollect.configuration._
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.collection.parallel.mutable.ParArray
import scala.collection.JavaConversions._
import com.signalcollect.Edge
import com.signalcollect.Vertex
import com.signalcollect.GraphEditor
import akka.actor.Actor
import akka.actor.ActorRef
import scala.collection.mutable.ArrayBuffer
import akka.actor.ActorSystem
import akka.actor.Props
import scala.util.Random
import scala.concurrent.Future

/**
 * Class that allows to interact with all the workers as if there were just one worker.
 */
class WorkerApi[Id, Signal](val workers: Array[Worker[Id, Signal]], val mapper: VertexToWorkerMapper[Id]) {

  override def toString = "WorkerApi"

  protected lazy val parallelWorkers = workers.par

  def getIndividualWorkerStatistics: List[WorkerStatistics] = {
    parallelWorkers.map(_.getWorkerStatistics).toList
  }

  def getWorkerStatistics: WorkerStatistics = {
    parallelWorkers.map(_.getWorkerStatistics).fold(WorkerStatistics(null))(_ + _)
  }

  def signalStep = parallelWorkers foreach (_.signalStep)

  def collectStep: Boolean = parallelWorkers.map(_.collectStep).reduce(_ && _)

  def startComputation = parallelWorkers foreach (_.startAsynchronousComputation)

  def pauseComputation = parallelWorkers foreach (_.pauseAsynchronousComputation)

  def recalculateScores = parallelWorkers foreach (_.recalculateScores)

  def recalculateScoresForVertexWithId(vertexId: Id) = workers(mapper.getWorkerIdForVertexId(vertexId)).recalculateScoresForVertexWithId(vertexId)

  def shutdown = parallelWorkers foreach (_.shutdown)

  def forVertexWithId[VertexType <: Vertex[Id, _], ResultType](vertexId: Id, f: VertexType => ResultType): ResultType = {
    workers(mapper.getWorkerIdForVertexId(vertexId)).forVertexWithId(vertexId, f)
  }

  def foreachVertex(f: (Vertex[Id, _]) => Unit) = parallelWorkers foreach (_.foreachVertex(f))

  def aggregate[ValueType](aggregationOperation: AggregationOperation[ValueType]) = {
    val aggregateArray: ParArray[ValueType] = parallelWorkers map (_.aggregate(aggregationOperation))
    aggregateArray.fold(aggregationOperation.neutralElement)(aggregationOperation.aggregate(_, _))
  }

  def setUndeliverableSignalHandler(h: (Signal, Id, Option[Id], GraphEditor[Id, Signal]) => Unit) = parallelWorkers foreach (_.setUndeliverableSignalHandler(h))

  def setSignalThreshold(t: Double) = parallelWorkers foreach (_.setSignalThreshold(t))

  def setCollectThreshold(t: Double) = parallelWorkers foreach (_.setCollectThreshold(t))

  //----------------GraphEditor, BLOCKING variant-------------------------

  /**
   *  Adds `vertex` to the graph.
   *
   *  @note If a vertex with the same id already exists, then this operation will be ignored and NO warning is logged.
   */
  def addVertex(vertex: Vertex[Id, _]) {
    workers(mapper.getWorkerIdForVertexId(vertex.id)).addVertex(vertex)
  }

  /**
   *  Adds `edge` to the graph.
   *
   *  @note If no vertex with the required source id is found, then the operation is ignored and a warning is logged.
   *  @note If an edge with the same id already exists, then this operation will be ignored and NO warning is logged.
   */
  def addEdge(sourceId: Id, edge: Edge[Id]) {
    workers(mapper.getWorkerIdForVertexId(sourceId)).addEdge(sourceId, edge)
   }
  
  /**
   *  Sends `signal` to the vertex with `vertex.id==edgeId.targetId`.
   *  Blocks until the operation has completed.
   */
  def sendSignal(signal: Signal, targetId: Id, sourceId: Option[Id]) {
    workers(mapper.getWorkerIdForVertexId(targetId)).processSignal(signal, targetId, sourceId)
  }

  /**
   *  Removes the vertex with id `vertexId` from the graph.
   *
   *  @note If no vertex with this id is found, then the operation is ignored and a warning is logged.
   */
  def removeVertex(vertexId: Id) {
    workers(mapper.getWorkerIdForVertexId(vertexId)).removeVertex(vertexId)
  }

  /**
   *  Removes the edge with id `edgeId` from the graph.
   *
   *  @note If no vertex with the required source id is found, then the operation is ignored and a warning is logged.
   *  @note If no edge with with this id is found, then this operation will be ignored and a warning is logged.
   */
  def removeEdge(edgeId: EdgeId[Id]) {
    workers(mapper.getWorkerIdForVertexId(edgeId.sourceId)).removeEdge(edgeId)
  }

  /**
   * Runs a graph loading function on a worker
   */
  def loadGraph(vertexIdHint: Option[Any], graphLoader: GraphEditor[Id, Signal] => Unit) {
    if (vertexIdHint.isDefined) {
      val workerId = vertexIdHint.get.hashCode % workers.length
      workers(workerId).loadGraph(graphLoader)
    } else {
      val rand = new Random
      val randomWorkerId = rand.nextInt(workers.length)
      workers(randomWorkerId).loadGraph(graphLoader)
    }
  }
}