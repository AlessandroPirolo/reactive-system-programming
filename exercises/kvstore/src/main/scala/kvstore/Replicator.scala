package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import scala.concurrent.duration.*
import akka.actor.ReceiveTimeout
import akka.util.Timeout
import akka.actor.PoisonPill

object Replicator:
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  case object TimeoutSend
  case object TimeoutAck

  def props(replica: ActorRef): Props = Props(Replicator(replica))

class Replicator(val replica: ActorRef) extends Actor:
  import Replicator.*
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
      
  context.system.scheduler.scheduleAtFixedRate(0.second, 30.millisecond, self, TimeoutSend)
  context.system.scheduler.scheduleAtFixedRate(0.second, 200.millisecond, self, TimeoutAck)

  var _seqCounter = 0L
  def nextSeq() =
    val ret = _seqCounter
    _seqCounter += 1
    ret
   
  /* TODO Behavior for the Replicator. */
  def receive: Receive =
    case SnapshotAck(key, seq) => {
      nextSeq()
      println("Replicator " + self + " acks: " + acks)
      val value = acks(seq)
      value(0) ! Replicated(value(1).key, value(1).id)
      acks = acks - seq
    }
    case r: Replicate => {
      acks += (_seqCounter -> (sender(), r))
      val sn = Snapshot(r.key, r.valueOption, _seqCounter)
      println("Snapshot " + sn + " sent to " + replica)
      pending = pending :+ sn
    }
    case TimeoutSend => {
      if (pending.nonEmpty) {
        val snapshot = pending.head
        replica ! snapshot
        pending = pending.takeRight(pending.length-1)
      } 
    }
    case TimeoutAck => {
      if (acks.nonEmpty) {
        val r = acks.head(1)(1)
        replica ! Snapshot(r.key, r.valueOption, _seqCounter)
      }
    }
    case _ => {}
