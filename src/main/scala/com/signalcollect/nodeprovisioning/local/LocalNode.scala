/*
 *  @author Philip Stutz
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

package com.signalcollect.nodeprovisioning.local

import com.signalcollect.configuration.ActorSystemRegistry
import com.signalcollect.configuration.AkkaDispatcher
import com.signalcollect.configuration.EventBased
import com.signalcollect.configuration.Pinned
import com.signalcollect.interfaces.WorkerActor
import com.signalcollect.nodeprovisioning.AkkaHelper
import com.signalcollect.nodeprovisioning.Node

import akka.actor.Props

class LocalNode extends Node {

  val system = ActorSystemRegistry.retrieve("SignalCollect").getOrElse(throw new Exception("no actor systme with name \"SignalCollect\" found."))

  def createWorker(workerId: Int, dispatcher: AkkaDispatcher, creator: () => WorkerActor[_, _]): String = {
    val workerName = "Worker" + workerId
    dispatcher match {
      case EventBased =>
        val worker = system.actorOf(Props[WorkerActor[_, _]].withCreator(creator()), name = workerName)
        AkkaHelper.getRemoteAddress(worker, system)
      case Pinned =>
        val worker = system.actorOf(Props[WorkerActor[_, _]].withCreator(creator()).withDispatcher("akka.actor.pinned-dispatcher"), name = workerName)
        AkkaHelper.getRemoteAddress(worker, system)
    }
  }

  def shutdown = {}

  def numberOfCores = Runtime.getRuntime.availableProcessors
}