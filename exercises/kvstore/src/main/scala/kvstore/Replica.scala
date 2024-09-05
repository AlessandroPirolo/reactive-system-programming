package kvstore

import akka.actor.{ OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, ActorRef, Actor, actorRef2Scala, Stash, Cancellable, FSM }
import akka.actor.SupervisorStrategy.Resume
import kvstore.Arbiter.*
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration.*
import akka.util.Timeout
import scala.math.max;


object Replica:
  sealed trait Operation:
    def key: String
    def id: Long
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  sealed trait ReplicaTimeout
  case object TimeoutPersist extends ReplicaTimeout
  case object TimeoutFailure extends ReplicaTimeout

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(Replica(arbiter, persistenceProps))

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor with Stash:
  import Replica.*
  import Replicator.*
  import Persistence.*
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  var pendingPersist = Map.empty[Long, (ActorRef, Persist)]
  var pendingAcks = Map.empty[Long, (ActorRef, Snapshot)]
  var persistence: ActorRef = context.actorOf(persistenceProps)
  var counterSeq = 0L 
  var counterId = 0L
  var schedulers = Map.empty[ReplicaTimeout, Cancellable]

  def nextId(): Long =
    val curr = counterId
    counterId += 1 
    curr

  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: PersistenceException => Resume
  }

  override def preStart(): Unit = 
    arbiter ! Join

  def receive =
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)

  def persist(key: String, valueOption: Option[String], id: Long): Unit =
    val p = Persist(key, valueOption, id)
    pendingPersist += (id -> (sender(), p))
    persistence ! p
    schedulers += (TimeoutPersist -> context.system.scheduler.scheduleOnce(100.millisecond, self, TimeoutPersist))
    schedulers += (TimeoutFailure -> context.system.scheduler.scheduleOnce(1.second, self, TimeoutFailure))
    context.become(leaderWaitPersist(0))
    
  /*
   * leader behavior 
   */
  val leader: Receive =
    case Insert(key, value, id) => {
      kv += (key -> value)
      persist(key, Some(value), id)
    }
    case Remove(key, id) => {
      kv = kv - key
      persist(key, None, id)
    }
    case Get(key, id) => 
      sender() ! GetResult(key, kv get key, id)
    case Replicas(replicas) => {
      val oldReplicas = secondaries.keySet.diff(replicas)
      oldReplicas foreach (r => secondaries(r) ! PoisonPill)
      oldReplicas foreach (_ ! PoisonPill)
      replicas.foreach(replica => {
        if (replica != self) {
          val replicator = context.actorOf(Replicator.props(replica))
          //secondaries = Map.empty[ActorRef, ActorRef] // error: every cycle got reset
          //replicators = Set.empty[ActorRef] //the error
          println(replicator)
          secondaries = secondaries + (replica -> replicator)
          replicators = replicators + replicator  //different replicators seen as equal
          kv foreach ((k,v) => replicator ! Replicate(k,Some(v),nextId()))
          secondaries foreach ((k,_) => context.watch(k))
        }
        }
      )
    } 
    case Terminated(ref) => {
      context.stop(ref)
      context.stop(secondaries(ref))
      secondaries -= ref
      replicators -= ref
    }
    case _ => {}
  end leader
  
  /*
   * Behavior by which the leader wait for a Persisted message 
   */
  def leaderWaitPersist(nAck: Int): Receive =
    case Persisted(key, id) => {
      schedulers(TimeoutPersist).cancel()
      schedulers(TimeoutFailure).cancel()
      schedulers -= TimeoutPersist
      schedulers -= TimeoutFailure
      val p = pendingPersist get id 
      if (secondaries.size == 0) {
        p.head(0) ! OperationAck(id)
        pendingPersist -= id
        context.become(leader)
        unstashAll() 
      } else {
        replicators foreach (_ ! Replicate(key, p.head(1).valueOption, id))
        println("Replication sent to " + replicators)
        schedulers += (TimeoutFailure -> context.system.scheduler.scheduleOnce(1.second, self, TimeoutFailure))
      }
    }
    case TimeoutPersist => {
      persistence = context.actorOf(persistenceProps)
      pendingPersist foreach ((_,p) => persistence ! p(1))
      schedulers += (TimeoutPersist -> context.system.scheduler.scheduleOnce(100.millisecond, self, TimeoutPersist)) //should start as many scheduler as the entry in pendinPersist and not just one
    }
    case Replicated(key, id) => {
      if (nAck+1 >= secondaries.size) {
        schedulers(TimeoutFailure).cancel() 
        schedulers -= TimeoutFailure
        val persist = pendingPersist get id
        persist.head(0) ! OperationAck(id)
        pendingPersist -= id
        context.become(leader)
        unstashAll()
      } else {
        context.become(leaderWaitPersist(nAck+1))
      }
    }
    case TimeoutFailure => { //TODO: timeoutfailure should be parametrized, to refering correctly to the persist failed
      schedulers(TimeoutFailure).cancel()
      schedulers -= TimeoutFailure
      pendingPersist foreach ((_, p) => p(0) ! OperationFailed(p(1).id)) //adjust here if TimeoutFailure will become parametrized
    }
    case Get(key, id) => 
      sender() ! GetResult(key, kv get key, id)
    case Replicas(replicas) => {
      pendingPersist foreach ((_, p) => p(0) ! OperationAck(p(1).id))
      pendingPersist = Map.empty[Long, (ActorRef, Persist)]
      context.become(leader)
      self ! Replicas(replicas)
      unstashAll() 
    }
    case _ => {
      stash()
    }

  
  /* 
   * Behavior of secondary replicas
   */
  val replica: Receive =
    case Get(key, id) =>
      sender() ! GetResult(key, kv get key, id)
    case Snapshot(key, valueOption, seq) => {
      println("Snapshot received")
      if (seq == counterSeq) {
        valueOption match
          case Some(value) =>
            kv += (key -> value)
          case None =>
            kv -= key
        val p = Persist(key, valueOption, seq)
        pendingPersist += (seq -> (sender(), p))
        persistence ! p
        context.system.scheduler.scheduleOnce(100.millisecond, self, TimeoutPersist)
        context.become(replicaWaitPersist)
      } else if (seq < counterSeq) {
        sender() ! SnapshotAck(key, seq)
        counterSeq = max(counterSeq, seq+1)
      }
    }
    case TimeoutPersist => {}
  end replica

  /*
   * Behavior by which a secondary replica wait for a Persisted message 
   */
  val replicaWaitPersist: Receive =
    case Persisted(k,id) => {
      val p = pendingPersist.get(id).head
      p(0) ! SnapshotAck(k, id)
      counterSeq += 1 
      pendingPersist -= id
      context.become(replica)
    }
    case TimeoutPersist => {
      persistence = context.actorOf(persistenceProps)
      val p = pendingPersist.head(1)(1)
      persistence ! p
      context.system.scheduler.scheduleOnce(100.millisecond, self, TimeoutPersist)
    }
    case _: Snapshot => {}
    case Get(key, id) =>
      sender() ! GetResult(key, kv get key, id)
  end replicaWaitPersist 

