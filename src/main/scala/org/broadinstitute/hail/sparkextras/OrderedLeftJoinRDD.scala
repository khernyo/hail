package org.broadinstitute.hail.sparkextras

import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.broadinstitute.hail.RichPairIterator
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.utils.BufferedAdvanceableOrderedPairIterator

import scala.reflect.ClassTag

object OrderedRDDIterator {
  def apply[PK, K, V](rdd: OrderedRDD[PK, K, V], context: TaskContext, k0: K): OrderedRDDIterator[PK, K, V] = {

    val it = new OrderedRDDIterator(rdd, context)
    it.setPartition(rdd.orderedPartitioner.getPartition(k0))
    it
  }
}

class OrderedRDDIterator[PK, K, V](
  rdd: OrderedRDD[PK, K, V],
  context: TaskContext,
  var partIndex: Int = -1,
  var it: BufferedIterator[(K, V)] = null,
  var partMaxT: PK = uninitialized[PK]) extends BufferedAdvanceableOrderedPairIterator[K, V] {

  private val nPartitions = rdd.partitions.length
  private val orderedKeyEv = rdd.kOk

  implicit val kOrdering = orderedKeyEv.kOrd

  import orderedKeyEv.pkOrd
  import Ordering.Implicits._

  private val partitioner = rdd.orderedPartitioner

  def head = it.head

  override def buffered = this

  def hasNext: Boolean = it.hasNext

  def setPartition(newPartIndex: Int) {
    partIndex = newPartIndex
    if (partIndex < nPartitions) {
      it = rdd.iterator(rdd.partitions(partIndex), context).buffered
      if (partIndex < nPartitions - 1)
        partMaxT = partitioner.rangeBounds(partIndex)
    } else
      it = Iterator.empty.buffered
  }

  def next(): (K, V) = {
    val n = it.next()

    /* advance to next partition if necessary */
    while (!it.hasNext && partIndex < nPartitions)
      setPartition(partIndex + 1)

    n
  }

  def advanceTo(k: K) {
    val t = orderedKeyEv.project(k)

    if (partIndex < nPartitions - 1 && t > partMaxT)
      setPartition(partitioner.getPartition(k))

    while (hasNext && head._1 < k)
      next()
  }
}

class OrderedLeftJoinRDD[PK, K, V1, V2](left: OrderedRDD[PK, K, V1], right: OrderedRDD[PK, K, V2])
  extends RDD[(K, (V1, Option[V2]))](left.sparkContext, Seq(new OneToOneDependency(left),
    new OrderedDependency(left.orderedPartitioner, right.orderedPartitioner, right)): Seq[Dependency[_]]) {

  override val partitioner: Option[Partitioner] = left.partitioner

  def getPartitions: Array[Partition] = left.partitions

  override def getPreferredLocations(split: Partition): Seq[String] = left.preferredLocations(split)

  override def compute(split: Partition, context: TaskContext): Iterator[(K, (V1, Option[V2]))] = {
    val leftIt = left.iterator(split, context).buffered
    if (leftIt.isEmpty)
      Iterator()
    else {
      val rightIt = OrderedRDDIterator(right, context, leftIt.head._1)
      leftIt.sortedLeftJoinDistinct(rightIt)
    }
  }
}
