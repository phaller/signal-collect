/*
 *  @author Philip Stutz
 *  @author Daniel Strebel
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

import com.signalcollect._
import com.signalcollect.interfaces._

/**
 * Wraps a general graph editor and optimizes operations that happen locally to a worker
 * by calling them directly on the worker itself.
 */
class WorkerGraphEditor[@specialized(Int, Long) Id, @specialized(Int, Long, Float, Double) Signal](worker: Worker[Id, Signal], messageBus: MessageBus[Id, Signal]) extends GraphEditor[Id, Signal] {

  private[signalcollect] val graphEditor = messageBus.getGraphEditor

  def sendSignal(signal: Signal, targetId: Id, sourceId: Option[Id], blocking: Boolean = false) {
    graphEditor.sendSignal(signal, targetId, sourceId, blocking)
  }

  def addVertex(vertex: Vertex[Id, _], blocking: Boolean) {
    if (blocking && shouldHandleLocally(vertex.id)) {
      worker.addVertex(vertex)
    } else {
      graphEditor.addVertex(vertex, blocking)
    }
  }

  def addEdge(sourceVertexId: Id, edge: Edge[Id], blocking: Boolean) {
    if (blocking && shouldHandleLocally(sourceVertexId)) {
      worker.addEdge(sourceVertexId, edge)
    } else {
      graphEditor.addEdge(sourceVertexId, edge, blocking)
    }
  }

  def removeVertex(vertexId: Id, blocking: Boolean) {
    if (blocking && shouldHandleLocally(vertexId)) {
      worker.removeVertex(vertexId)
    } else {
      graphEditor.removeVertex(vertexId, blocking)
    }
  }

  def removeEdge(edgeId: EdgeId[Id], blocking: Boolean) {
    if (blocking && shouldHandleLocally(edgeId.sourceId)) {
      worker.removeEdge(edgeId)
    } else {
      graphEditor.removeEdge(edgeId, blocking)
    }
  }

  def loadGraph(vertexIdHint: Option[Id] = None, graphLoader: GraphEditor[Id, Signal] => Unit, blocking: Boolean) {
    graphEditor.loadGraph(vertexIdHint, graphLoader, blocking)
  }

  protected def shouldHandleLocally(vertexId: Id): Boolean = {
    messageBus.getWorkerIdForVertexId(vertexId) == worker.workerId
  }

  private[signalcollect] def sendToWorkerForVertexIdHash(message: Any, vertexIdHash: Int) {
    graphEditor.sendToWorkerForVertexIdHash(message, vertexIdHash)
  }
}