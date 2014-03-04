package com.treode.store.paxos

import com.treode.async.{Async, Callback, Fiber}
import com.treode.cluster.{MessageDescriptor, Peer}
import com.treode.disk.RecordDescriptor
import com.treode.store.Bytes

import Callback.ignore

private class Acceptor (val key: Bytes, kit: PaxosKit) {
  import Acceptor.{ActiveStatus, NoPost, Post}
  import kit.{archive, cluster, disks, scheduler}
  import kit.config.{closedLifetime, deliberatingTimeout}

  private val fiber = new Fiber (scheduler)
  var state: State = null

  trait State {

    def status: Option [ActiveStatus]
    def query (proposer: Peer, ballot: Long, default: Bytes)
    def propose (proposer: Peer, ballot: Long, value: Bytes)
    def choose (chosen: Bytes)

    def shutdown(): Unit =
      state = new Shutdown (status)
  }

  class Opening extends State {

    def status = throw new IllegalStateException

    def query (proposer: Peer, ballot: Long, default: Bytes) {
      val s = new Restoring (default)
      s.query (proposer, ballot, default)
      s.restore()
      state = s
    }

    def propose (proposer: Peer, ballot: Long, value: Bytes) {
      val s = new Restoring (value)
      s.propose (proposer, ballot, value)
      s.restore()
      state = s
    }

    def choose (chosen: Bytes) {
      val s = new Restoring (chosen)
      s.choose (chosen)
      s.restore()
      state = s
    }}

  class Restoring (default: Bytes) extends State {

    var ballot: BallotNumber = BallotNumber.zero
    var proposal: Proposal = Option.empty
    var proposers = Set.empty [Peer]
    var postable = {_: Deliberating => ()}

    def status = None

    def promise (ballot: BallotNumber, proposal: Proposal, proposer: Peer): Unit =
      postable = (_.promise (ballot, proposal, proposer))

    def accept (ballot: BallotNumber, value: Bytes, proposer: Peer): Unit =
      postable = (_.accept (ballot, value, proposer))

    def restore() {
      archive.get (key) run (new Callback [Option [Bytes]] {

        def pass (chosen: Option [Bytes]): Unit = fiber.execute {
          if (state == Restoring.this) {
            state = chosen match {
              case Some (value) =>
                Proposer.chosen (key, value) (proposers)
                new Closed (value)
              case None =>
                val s = new Deliberating (default, ballot, proposal, proposers)
                postable (s)
                s
            }}}

        def fail (t: Throwable): Unit = fiber.execute {
          if (state == Restoring.this) {
            state = new Panicked (status, t)
            throw t
          }}})
    }

    def query (proposer: Peer, _ballot: Long, default: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (this.ballot <= ballot) {
        promise (ballot, proposal, proposer)
        this.ballot = ballot
      }}

    def propose (proposer: Peer, _ballot: Long, value: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (this.ballot <= ballot) {
        accept (ballot, value, proposer)
        this.ballot = ballot
        this.proposal = Some ((ballot, value))
      }}

    def choose (chosen: Bytes) {
      state = new Closed (chosen)
      val gen  = archive.put (key, chosen)
      Acceptor.close.record (key, chosen, gen) .run (Callback.ignore)
      Proposer.chosen (key, chosen) (proposers)
    }

    override def toString = s"Acceptor.Restoring($key)"
  }

  class Deliberating (
      val default: Bytes,
      var ballot: BallotNumber,
      var proposal: Proposal,
      var proposers: Set [Peer]) extends State {

    var posting: Post = NoPost
    var postable: Post = NoPost

    val posted = new Callback [Unit] {

      def pass (v: Unit): Unit = fiber.execute {
        if (state == Deliberating.this) {
          posting.reply()
          posting = postable
          postable = NoPost
          posting.record()
        }}

      def fail (t: Throwable): Unit = fiber.execute {
        if (state == Deliberating.this) {
          state = new Panicked (status, t)
        }}}

    def post (post: Post) {
      if (posting == NoPost) {
        posting = post
        posting.record()
      } else {
        this.postable = post
      }}

    fiber.delay (deliberatingTimeout) {
      if (state == Deliberating.this)
        kit.propose (key, default) .map (Acceptor.this.choose (_)) .run (ignore)
    }

    def open (ballot: Long, default: Bytes, proposer: Peer): Unit =
      post (new Post {
        def record = Acceptor.open.record (key, default) .run (posted)
        def reply() = Proposer.promise (key, ballot, None) (proposer)
      })

    def promise (ballot: BallotNumber, proposal: Proposal, proposer: Peer): Unit =
      post (new Post {
        def record = Acceptor.promise.record (key, ballot) .run (posted)
        def reply() = Proposer.promise (key, ballot.number, proposal) (proposer)
      })

    def accept (ballot: BallotNumber, value: Bytes, proposer: Peer): Unit =
      post (new Post {
        def record() = Acceptor.accept.record (key, ballot, value) .run (posted)
        def reply() = Proposer.accept (key, ballot.number) (proposer)
      })

    def reaccept (ballot: BallotNumber, proposer: Peer): Unit =
      post (new Post {
        def record() = Acceptor.reaccept.record (key, ballot) .run (posted)
        def reply() = Proposer.accept (key, ballot.number) (proposer)
      })

    def status = Some (ActiveStatus.Deliberating (key, default, ballot, proposal))

    def query (proposer: Peer, _ballot: Long, default: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (ballot < this.ballot) {
        Proposer.refuse (key, this.ballot.number) (proposer)
      } else {
        promise (ballot, proposal, proposer)
        this.ballot = ballot
      }}

    def propose (proposer: Peer, _ballot: Long, value: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (ballot < this.ballot) {
        Proposer.refuse (key, this.ballot.number) (proposer)
      } else {
        if (proposal.isDefined && value == proposal.get._2)
          reaccept (ballot, proposer)
        else
          accept (ballot, value, proposer)
        this.ballot = ballot
        this.proposal = Some ((ballot, value))
      }}

    def choose (chosen: Bytes) {
      state = new Closed (chosen)
      val gen  = archive.put (key, chosen)
      Acceptor.close.record (key, chosen, gen) .run (Callback.ignore)
    }

    override def toString = s"Acceptor.Deliberating($key, $proposal)"
  }

  class Closed (val chosen: Bytes) extends State {

    // TODO: Purge acceptor from memory.
    // fiber.delay (closedLifetime) (remove (key, Acceptor.this))

    def status = None

    def query (proposer: Peer, ballot: Long, default: Bytes): Unit =
      Proposer.chosen (key, chosen) (proposer)

    def propose (proposer: Peer, ballot: Long, value: Bytes): Unit =
      Proposer.chosen (key, chosen) (proposer)

    def choose (chosen: Bytes): Unit =
      require (chosen == this.chosen, "Paxos disagreement")

    override def toString = s"Acceptor.Closed($key, $chosen)"
  }

  class Shutdown (status: Option [ActiveStatus]) extends State {

    // TODO: Purge acceptor from memory.

    def status = None
    def query (proposer: Peer, ballot: Long, default: Bytes): Unit = ()
    def propose (proposer: Peer, ballot: Long, value: Bytes): Unit = ()
    def choose (chosen: Bytes): Unit = ()

    override def shutdown() = ()

    override def toString = s"Acceptor.Shutdown($key)"
  }

  class Panicked (status: Option [ActiveStatus], thrown: Throwable)
  extends Shutdown (status) {

    override def toString = s"Acceptor.Panicked($key, $thrown)"
  }

  def query (proposer: Peer, ballot: Long, default: Bytes): Unit =
    fiber.execute (state.query (proposer, ballot, default))

  def propose (proposer: Peer, ballot: Long, value: Bytes): Unit =
    fiber.execute (state.propose (proposer, ballot, value))

  def choose (chosen: Bytes): Unit =
    fiber.execute (state.choose (chosen))

  def checkpoint(): Async [Option [ActiveStatus]] =
    fiber.supply (state.status)

  def shutdown(): Unit =
    fiber.execute (state.shutdown())

  override def toString = state.toString
}

private object Acceptor {

  val query = {
    import PaxosPicklers._
    MessageDescriptor (0xFF14D4F00908FB59L, tuple (bytes, ulong, bytes))
  }

  val propose = {
    import PaxosPicklers._
    MessageDescriptor (0xFF09AFD4F9B688D9L, tuple (bytes, ulong, bytes))
  }

  val choose = {
    import PaxosPicklers._
    MessageDescriptor (0xFF761FFCDF5DEC8BL, tuple (bytes, bytes))
  }

  val open = {
    import PaxosPicklers._
    RecordDescriptor (0x77784AB1, tuple (bytes, bytes))
  }

  val promise = {
    import PaxosPicklers._
    RecordDescriptor (0x32A1544B, tuple (bytes, ballotNumber))
  }

  val accept = {
    import PaxosPicklers._
    RecordDescriptor (0xD6CCC0BE, tuple (bytes, ballotNumber, bytes))
  }

  val reaccept = {
    import PaxosPicklers._
    RecordDescriptor (0x52720640, tuple (bytes, ballotNumber))
  }

  val close = {
    import PaxosPicklers._
    RecordDescriptor (0xAE980885, tuple (bytes, bytes, long))
  }

  trait Post {
    def record()
    def reply()
  }

  object NoPost extends Post {
    def record() = ()
    def reply() = ()
  }

  sealed abstract class ActiveStatus

  object ActiveStatus {

    case class Restoring (key: Bytes, default: Bytes) extends ActiveStatus

    case class Deliberating (
        key: Bytes,
        default: Bytes,
        ballot: BallotNumber,
        proposal: Proposal) extends ActiveStatus

    case class Closed (key: Bytes, chosen: Bytes) extends ActiveStatus {
      def default = chosen
    }

    val pickler = {
      import PaxosPicklers._
      tagged [ActiveStatus] (
          0x1 -> wrap (bytes, bytes)
                 .build ((Restoring.apply _).tupled)
                 .inspect (v => (v.key, v.default)),
          0x2 -> wrap (bytes, bytes, ballotNumber, proposal)
                 .build ((Deliberating.apply _).tupled)
                 .inspect (v => (v.key, v.default, v.ballot, v.proposal)),
          0x3 -> wrap (bytes, bytes)
                 .build ((Closed.apply _).tupled)
                 .inspect (v => (v.key, v.chosen)))
    }}}
