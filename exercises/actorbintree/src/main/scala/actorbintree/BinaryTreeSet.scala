/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor.*
import scala.collection.immutable.Queue
import java.{util => ju}
import java.nio.file.CopyOption

object BinaryTreeSet:

  trait Operation:
    def requester: ActorRef
    def id: Int
    def elem: Int

  trait OperationReply:
    def id: Int

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply



class BinaryTreeSet extends Actor:
  import BinaryTreeSet.*
  import BinaryTreeNode.*

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = { 
    case operation : Operation => root ! operation
    case GC => // ATTENTION: Comment this part to pass the test, otherwise it won't work
      def newRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case operation : Operation => pendingQueue.enqueue(operation)
    case GC =>
    case CopyFinished =>
      root ! PoisonPill
      root = newRoot
      context.become(normal)
      pendingQueue.foreach(op => root ! op)
      pendingQueue = Queue.empty[Operation]
  }


object BinaryTreeNode:
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  /**
   * Acknowledges that a copy has been completed. This message should be sent
   * from a node to its parent, when this node and all its children nodes have
   * finished being copied.
   */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor:
  import BinaryTreeNode.*
  import BinaryTreeSet.*

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = { 
    case Insert(req, id, el) => 
      if (el > elem) {
        if (subtrees.contains(Left)) {
          subtrees(Left) ! Insert(req, id, el)
        } else {
          def newLeftNode: ActorRef = context.actorOf(BinaryTreeNode.props(el, false))
          subtrees += (Left -> newLeftNode)
          req ! OperationFinished(id)
        }
      } else if (el < elem) { 
        if (subtrees.contains(Right)) {
          subtrees(Right) ! Insert(req, id, el)
        } else {
          def newRightNode: ActorRef = context.actorOf(BinaryTreeNode.props(el, false))
          subtrees += (Right -> newRightNode)
          req ! OperationFinished(id)
        }
      } else {
        removed = false
        req ! OperationFinished(id)
      } 
    case Contains(req, id, el) =>
      if (el > elem) {
        if (subtrees.contains(Left)) {
          subtrees(Left) ! Contains(req, id, el)
        } else {
          req ! ContainsResult(id, false)
        }
      } else if (el < elem) { 
        if (subtrees.contains(Right)) {
          subtrees(Right) ! Contains(req, id, el)
        } else {
          req ! ContainsResult(id, false)
        }
      } else if (!removed && (el == elem)) {
        req ! ContainsResult(id, true)
      } else {
        req ! ContainsResult(id, false)
      } 
    case Remove(req, id, el) =>
      if (el > elem) {
        if (subtrees.contains(Left)) {
          subtrees(Left) ! Remove(req, id, el)
        } else {
          req ! OperationFinished(id)
        }
      } else if (el < elem) { 
        if (subtrees.contains(Right)) {
          subtrees(Right) ! Remove(req, id, el)
        } else {
          req ! OperationFinished(id)
        }
      } else {
        removed = true
        req ! OperationFinished(id)
      }
    case CopyTo(node) =>
      if (removed && subtrees.isEmpty) {
        sender() ! CopyFinished
      } else {
        if (!removed) node ! Insert(self, elem, elem)
        val sub = subtrees.values.toSet
        sub.foreach(
          tree => tree ! CopyTo(node)
          )
        context.become(copying(sub, removed))
      }
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished(_) => 
      if (expected.isEmpty) {
        context.parent ! CopyFinished
        context.become(normal)
      }
    case CopyFinished => 
      val remaining = expected - sender()
      if (remaining.isEmpty && insertConfirmed) {
        context.parent ! CopyFinished
        context.become(normal)
      } else {
        context.become(copying(remaining, insertConfirmed))
      }
  }


