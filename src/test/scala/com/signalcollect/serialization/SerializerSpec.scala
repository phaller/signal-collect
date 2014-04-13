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

package com.signalcollect.serialization

import org.junit.runner.RunWith
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import com.signalcollect.GraphBuilder
import com.signalcollect.configuration.ActorSystemRegistry
import akka.serialization.SerializationExtension
import com.romix.akka.serialization.kryo.KryoSerializer

@RunWith(classOf[JUnitRunner])
class SerializerSpec extends SpecificationWithJUnit with Mockito {

  sequential

  "Kryo" should {

    "correctly serialize Scala immutable maps" in {
      val g = GraphBuilder.build
      try {
        // Scala uses special representations for small maps.
        kryoSerializeAndDeserialize(Map.empty[Int, Double])
        kryoSerializeAndDeserialize(Map(1 -> 1.5))
        kryoSerializeAndDeserialize(Map(1 -> 1.5, 2 -> 5.4))
        kryoSerializeAndDeserialize(Map(1 -> 1.5, 2 -> 5.4, 3 -> 4.5))
        kryoSerializeAndDeserialize(Map(1 -> 1.5, 2 -> 5.4, 3 -> 4.5, 4 -> 1.2))
        kryoSerializeAndDeserialize(Map(1 -> 1.5, 2 -> 5.4, 3 -> 4.5, 4 -> 1.2, 6 -> 3.2))
        true
      } finally {
        g.shutdown
      }
    }

    "correctly serialize Scala immutable sets" in {
      val g = GraphBuilder.build
      try {
        // Scala uses special representations for small sets.
        kryoSerializeAndDeserialize(Set.empty[Int])
        kryoSerializeAndDeserialize(Set(1))
        kryoSerializeAndDeserialize(Set(1, 2))
        kryoSerializeAndDeserialize(Set(1, 2, 3))
        kryoSerializeAndDeserialize(Set(1, 2, 3, 4))
        kryoSerializeAndDeserialize(Set(1, 2, 3, 4, 5))
        true
      } finally {
        g.shutdown
      }
    }

    "correctly serialize Scala None" in {
      val g = GraphBuilder.build
      try {
        kryoSerializeAndDeserialize(None)
        true
      } finally {
        g.shutdown
      }
    }

    "correctly serialize Scala List" in {
      val g = GraphBuilder.build
      try {
        kryoSerializeAndDeserialize(List.empty[Int])
        kryoSerializeAndDeserialize(List(1))
        true
      } finally {
        g.shutdown
      }
    }

    def kryoSerializeAndDeserialize(instance: AnyRef) {
      val akka = ActorSystemRegistry.retrieve("SignalCollect").get
      val serialization = SerializationExtension(akka)
      val s = serialization.findSerializerFor(instance)
      assert(s.isInstanceOf[KryoSerializer])
      val bytes = s.toBinary(instance)
      val b = s.fromBinary(bytes, manifest = None)
      assert(b == instance)
    }

  }

  // TODO: None test

  "DefaultSerializer" should {

    "correctly serialize/deserialize a Double" in {
      DefaultSerializer.read[Double](DefaultSerializer.write(1024.0)) === 1024.0
    }

    "correctly serialize/deserialize a job configuration" in {
      val job = new Job(
        100,
        Some(SpreadsheetConfiguration("some.emailAddress@gmail.com", "somePasswordHere", "someSpreadsheetNameHere", "someWorksheetNameHere")),
        "someUsername",
        "someJobDescription")
      DefaultSerializer.read[Job](DefaultSerializer.write(job)) === job
    }

  }

}

case class SpreadsheetConfiguration(
  gmailAccount: String,
  gmailPassword: String,
  spreadsheetName: String,
  worksheetName: String)

case class Job(
  var jobId: Int,
  var spreadsheetConfiguration: Option[SpreadsheetConfiguration],
  var submittedByUser: String,
  var jobDescription: String)