package com.treode.store.paxos

import scala.language.postfixOps

import com.treode.async.{Backoff, Callback, Fiber}
import com.treode.async.implicits._
import com.treode.async.misc.RichInt
import com.treode.cluster.{Peer, MessageDescriptor}
import com.treode.store.{BallotNumber, Bytes, TimeoutException, TxClock}

private class Proposer (key: Bytes, time: TxClock, kit: PaxosKit) {
  import kit.proposers.remove
  import kit.{cluster, random, scheduler, track}

  private val proposingBackoff = Backoff (200, 300, 1 minutes, 7)
  private val confirmingBackoff = Backoff (200, 300, 1 minutes, 7)
  private val closedLifetime = 2 seconds

  private val fiber = new Fiber
  var state: State = Opening

  trait State {
    def open (ballot: Long, value: Bytes) = ()
    def learn (k: Learner)
    def refuse (from: Peer, ballot: Long)
    def promise (from: Peer, ballot: Long, proposal: Proposal)
    def accept (from: Peer, ballot: Long)
    def chosen (value: Bytes)
    def timeout()
  }

  private def max (x: Proposal, y: Proposal) = {
    if (x.isDefined && y.isDefined) {
      if (x.get._1 > y.get._1) x else y
    } else if (x.isDefined) {
      x
    } else if (y.isDefined) {
      y
    } else {
      None
    }}

  private def agreement (x: Proposal, value: Bytes) = {
    x match {
      case Some ((_, value)) => value
      case None => value
    }}

  private def illegal = throw new IllegalStateException

  object Opening extends State {

    override def open (ballot: Long, value: Bytes) =
      state = new Open (ballot, value)

    def learn (k: Learner) = throw new IllegalStateException

    def refuse (from: Peer, ballot: Long) = ()

    def promise (from: Peer, ballot: Long, proposal: Proposal) = ()

    def accept (from: Peer, ballot: Long) = ()

    def chosen (v: Bytes): Unit =
      state = new Closed (0, v)

    def timeout() = ()

    override def toString = "Proposer.Opening (%s)" format (key.toString)
  }

  class Open (var ballot: Long, value: Bytes) extends State {

    var learners = List.empty [Learner]
    var refused = ballot
    var proposed = Option.empty [(BallotNumber, Bytes)]
    val promised = track (key, time)
    val accepted = track (key, time)

    // Ballot number zero was implicitly accepted.
    if (ballot == 0)
      Acceptor.propose (key, time, ballot, value) (promised)
    else
      Acceptor.query (key, time, ballot, value) (promised)

    val backoff = proposingBackoff.iterator
    fiber.delay (backoff.next) (state.timeout())

    def learn (k: Learner) =
      learners ::= k

    def refuse (from: Peer, ballot: Long) = {
      refused = math.max (refused, ballot)
      promised.clear()
      accepted.clear()
    }

    def promise (from: Peer, ballot: Long, proposal: Proposal) {
      if (ballot == this.ballot) {
        promised += from
        proposed = max (proposed, proposal)
        if (promised.quorum) {
          val v = agreement (proposed, value)
          Acceptor.propose (key, time, ballot, v) (accepted)
        }}}

    def accept (from: Peer, ballot: Long) {
      if (ballot == this.ballot) {
        accepted += from
        if (accepted.quorum) {
          val v = agreement (proposed, value)
          Acceptor.choose (key, time, v) (track (key, time))
          learners foreach (_.pass (v))
          state = new Closed (ballot, v)
        }}}

    def chosen (v: Bytes) {
      learners foreach (scheduler.pass (_, v))
      state = new Closed (ballot, v)
    }

    def timeout() {
      if (backoff.hasNext) {
        promised.clear()
        accepted.clear()
        ballot = refused + random.nextInt (17) + 1
        refused = ballot
        Acceptor.query (key, time, ballot, value) (promised)
        fiber.delay (backoff.next) (state.timeout())
      } else {
        remove (key, time, Proposer.this)
        learners foreach (scheduler.fail (_, new TimeoutException))
      }}

    override def toString = "Proposer.Open " + (key, ballot, value)
  }

  class Closed (ballot: Long, value: Bytes) extends State {

    fiber.delay (closedLifetime) (remove (key, time, Proposer.this))

    def learn (k: Learner) =
      scheduler.pass (k, value)

    def chosen (v: Bytes) =
      require (v == value, "Paxos disagreement")

    def refuse (from: Peer, ballot: Long) =
      if (ballot == this.ballot)
        Acceptor.choose (key, time, value) (from)

    def promise (from: Peer, ballot: Long, proposal: Proposal) =
      if (ballot == this.ballot)
        Acceptor.choose (key, time, value) (from)

    def accept (from: Peer, ballot: Long) =
      if (ballot == this.ballot)
        Acceptor.choose (key, time, value) (from)

    def timeout() = ()

    override def toString = "Proposer.Closed " + (key, value)
  }

  object Shutdown extends State {

    def learn (k: Learner) = ()
    def refuse (from: Peer, ballot: Long) = ()
    def promise (from: Peer, ballot: Long, proposal: Proposal) = ()
    def accept (from: Peer, ballot: Long) = ()
    def chosen (v: Bytes) = ()
    def timeout() = ()

    override def toString = "Proposer.Shutdown (%s)" format (key)
  }

  def open (ballot: Long, value: Bytes) =
    fiber.execute (state.open (ballot, value))

  def learn (k: Learner) =
    fiber.execute  (state.learn (k))

  def refuse (from: Peer, ballot: Long) =
    fiber.execute  (state.refuse (from, ballot))

  def promise (from: Peer, ballot: Long, proposal: Proposal) =
    fiber.execute  (state.promise (from, ballot, proposal))

  def accept (from: Peer, ballot: Long) =
    fiber.execute  (state.accept (from, ballot))

  def chosen (value: Bytes) =
    fiber.execute  (state.chosen (value))

  override def toString = state.toString
}

private object Proposer {

  val refuse = {
    import PaxosPicklers._
    MessageDescriptor (0xFF3725D9448D98D0L, tuple (bytes, txClock, ulong))
  }

  val promise = {
    import PaxosPicklers._
    MessageDescriptor (0xFF52232E0CCEE1D2L, tuple (bytes, txClock, ulong, proposal))
  }

  val accept = {
    import PaxosPicklers._
    MessageDescriptor (0xFFB799D0E495804BL, tuple (bytes, txClock, ulong))
  }

  val chosen = {
    import PaxosPicklers._
    MessageDescriptor (0xFF3D8DDECF0F6CBEL, tuple (bytes, txClock, bytes))
  }}
