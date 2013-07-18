/*
 * Copyright (C) 2012 The Regents of The University California.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark

import java.io.{ObjectOutputStream, IOException}
import java.util.{HashMap => JHashMap}
import org.apache.hadoop.io.BytesWritable
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import shark.execution.{ReduceKeyMapSide, ReduceKeyReduceSide}
import shark.SharkEnv

// A version of CoGroupedRDD with the following changes:
// - Disable map-side aggregation.
// - Enforce return type to Array[ArrayBuffer].

sealed trait CoGroupSplitDep extends Serializable

case class NarrowCoGroupSplitDep(
    rdd: RDD[_],
    splitIndex: Int,
    var split: Partition
  ) extends CoGroupSplitDep {

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream) {
    // Update the reference to parent split at the time of task serialization
    split = rdd.partitions(splitIndex)
    oos.defaultWriteObject()
  }
}

case class ShuffleCoGroupSplitDep(shuffleId: Int) extends CoGroupSplitDep

class CoGroupPartition(idx: Int, val deps: Seq[CoGroupSplitDep])
  extends Partition with Serializable {

  override val index: Int = idx
  override def hashCode(): Int = idx
}

class CoGroupAggregator
  extends Aggregator[Any, Any, ArrayBuffer[Any]](
    { x => ArrayBuffer(x) },
    { (b, x) => b += x },
    null)
  with Serializable

// Disable map-side combine during aggregation.
class CoGroupedRDD[K](@transient var rdds: Seq[RDD[(_, _)]], part: Partitioner)
  extends RDD[(K, Array[ArrayBuffer[Any]])](rdds.head.context, Nil) with Logging {

  val aggr = new CoGroupAggregator

  override def getDependencies: Seq[Dependency[_]] = {
    rdds.map { rdd =>
      if (rdd.partitioner == Some(part)) {
        logInfo("Adding one-to-one dependency with " + rdd)
        new OneToOneDependency(rdd)
      } else {
        logInfo("Adding shuffle dependency with " + rdd)
        new ShuffleDependency[Any, Any](rdd, part, SharkEnv.shuffleSerializerName)
      }
    }
  }

  override def getPartitions: Array[Partition] = {
    val firstRdd = rdds.head
    val array = new Array[Partition](part.numPartitions)
    for (i <- 0 until array.size) {
      array(i) = new CoGroupPartition(i, rdds.zipWithIndex.map { case (r, j) =>
        dependencies(j) match {
          case s: ShuffleDependency[_, _] =>
            new ShuffleCoGroupSplitDep(s.shuffleId): CoGroupSplitDep
          case _ =>
            new NarrowCoGroupSplitDep(r, i, r.partitions(i)): CoGroupSplitDep
        }
      }.toList)
    }
    array
  }

  override val partitioner = Some(part)

  override def compute(s: Partition, context: TaskContext)
  : Iterator[(K, Array[ArrayBuffer[Any]])] = {
    val split = s.asInstanceOf[CoGroupPartition]
    val numRdds = split.deps.size
    val map = new JHashMap[K, Array[ArrayBuffer[Any]]]
    def getSeq(k: K): Array[ArrayBuffer[Any]] = {
      var values = map.get(k)
      if (values == null) {
        values = Array.fill(numRdds)(new ArrayBuffer[Any])
        map.put(k, values)
      }
      values
    }
    val serializer = SparkEnv.get.serializerManager.get(SharkEnv.shuffleSerializerName)
    for ((dep, depNum) <- split.deps.zipWithIndex) dep match {
      case NarrowCoGroupSplitDep(rdd, itsSplitIndex, itsSplit) => {
        // Read them from the parent
        rdd match {
          case rdd: RDD[(ReduceKeyMapSide, BytesWritable)] =>
            for ((k, v) <- rdd.iterator(itsSplit, context)) {
              val keyArr = new Array[Byte](k.length)
              Array.copy(k.byteArray, 0, keyArr, 0, k.length)
              val k1 = new ReduceKeyReduceSide(keyArr)
              val v1 = new Array[Byte](v.getLength)
              Array.copy(v.getBytes, 0, v1, 0, v.getLength)
              getSeq(k1.asInstanceOf[K])(depNum) += v1
            }
          case _ =>
            for ((k, v) <- rdd.iterator(itsSplit, context)) {
              getSeq(k.asInstanceOf[K])(depNum) += v
            }
        }
      }
      case ShuffleCoGroupSplitDep(shuffleId) => {
        // Read map outputs of shuffle
        def mergePair(pair: (K, Any)) { getSeq(pair._1)(depNum) += pair._2 }
        val fetcher = SparkEnv.get.shuffleFetcher
        fetcher.fetch[K, Seq[Any]](shuffleId, split.index, context.taskMetrics, serializer)
          .foreach(mergePair)
      }
    }
    map.iterator
  }

  override def clearDependencies() {
    super.clearDependencies()
    rdds = null
  }
}
