package lila.ws

import scala.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ ExecutionContext, Future }
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.locks.ReentrantLock

// Best effort fixed capacity cache for the social graph of online users.
//
// Based on a fixed size array with at most 2^logCapacity entries and
// adjacency lists.
//
// It is guaranteed that users do not receive incorrect updates, but there
// is a chance that some updates are missed if the number of online
// users approaches the capacity.
class SocialGraph(
  loadFollowed: User.ID => Future[Iterable[UserRecord]],
  logCapacity: Int
) {
  private val leftFollowsRight = new AdjacenyList()
  private val rightFollowsLeft = new AdjacenyList()

  private val locksMask: Int = (1 << 10) - 1
  private val locks: Array[ReentrantLock] = Array.tabulate(locksMask + 1)(_ => new ReentrantLock())

  private val slotsMask: Int = (1 << logCapacity) - 1
  private val slots: Array[UserEntry] = new Array(1 << logCapacity)

  private def lockFor(slot: Int): ReentrantLock = {
    val lock = locks(slot & locksMask)
    lock.lock()
    lock
  }

  private def lockSlot(id: User.ID): Slot = {
    val hash = id.hashCode & slotsMask
    val searchLossless = hash to (hash + SocialGraph.MaxStride) flatMap { s: Int =>
      // Try to find an existing or empty slot between hash and
      // hash + MaxStride.
      val slot = s & slotsMask
      val lock = lockFor(slot)
      if (slots(slot) == null) Some(NewSlot(slot, lock))
      else if (slots(slot).id == id) Some(ExistingSlot(slot, lock))
      else {
        lock.unlock()
        None
      }
    }
    val searchDecent = searchLossless.headOption orElse {
      (hash to (hash + SocialGraph.MaxStride) flatMap { s: Int =>
        // If no exisiting or empty slot is available, try to replace an
        // offline slot. If someone is watching the offline slot, and that
        // user goes online before the watcher resubscribes, then that update
        // is lost.
        val slot = s & slotsMask
        val lock = lockFor(slot)
        val existing = slots(slot)
        if (existing == null) Some(NewSlot(slot, lock))
        else if (existing.id == id) Some(ExistingSlot(slot, lock))
        else if (!existing.meta.exists(_.online)) {
          leftFollowsRight.read(slot) foreach invalidateRightSlot(slot)
          Some(NewSlot(slot, lock))
        }
        else {
          lock.unlock()
          None
        }
      }).headOption
    }
    searchDecent.headOption getOrElse {
      // The hashtable is full. Overwrite a random entry.
      val lock = lockFor(hash)
      leftFollowsRight.read(hash) foreach invalidateRightSlot(hash)
      NewSlot(hash, lock)
    }
  }

  private def invalidateRightSlot(leftSlot: Int)(rightSlot: Int) = {
    val rightLock = lockFor(rightSlot)
    slots(rightSlot) = slots(rightSlot).copy(fresh = false)
    leftFollowsRight.remove(leftSlot, rightSlot)
    rightFollowsLeft.remove(rightSlot, leftSlot)
    rightLock.unlock()
  }

  private def readFollowed(leftSlot: Int): List[UserInfo] = {
    leftFollowsRight.read(leftSlot) flatMap { rightSlot =>
      val entry = slots(rightSlot)
      entry.username map { username => UserInfo(entry.id, username, entry.meta) }
    }
  }

  private def readFollowing(leftSlot: Int): List[User.ID] = {
    rightFollowsLeft.read(leftSlot) flatMap { rightSlot =>
      val rightLock = lockFor(rightSlot)
      val id =
        if (leftFollowsRight.has(rightSlot, leftSlot)) Some(slots(rightSlot).id)
        else None
      rightLock.unlock()
      id
    }
  }

  private def mergeFollowed(leftSlot: Int, followed: Iterable[UserRecord]): List[UserInfo] = {
    val build: ListBuffer[UserInfo] = new ListBuffer()
    followed foreach { record =>
      lockSlot(record.id) match {
        case NewSlot(rightSlot, rightLock) =>
          slots(rightSlot) = UserEntry(record.id, Some(record.username), None, false)
          leftFollowsRight.add(leftSlot, rightSlot)
          rightFollowsLeft.add(rightSlot, leftSlot)
          rightLock.unlock()
          build += UserInfo(record.id, record.username, None)
        case ExistingSlot(rightSlot, rightLock) =>
          val entry = slots(rightSlot).copy(username = Some(record.username))
          slots(rightSlot) = entry
          leftFollowsRight.add(leftSlot, rightSlot)
          rightFollowsLeft.add(rightSlot, leftSlot)
          rightLock.unlock()
          build += UserInfo(record.id, record.username, entry.meta)
      }
    }
    build.toList
  }

  private def doLoadFollowed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    loadFollowed(id) map { followed =>
      lockSlot(id) match {
        case NewSlot(leftSlot, leftLock) =>
          slots(leftSlot) = UserEntry(id, None, None, true)
          val infos = mergeFollowed(leftSlot, followed)
          leftLock.unlock()
          infos
        case ExistingSlot(leftSlot, leftLock) =>
          slots(leftSlot) = slots(leftSlot).copy(fresh = true)
          val infos = mergeFollowed(leftSlot, followed)
          leftLock.unlock()
          infos
      }
    }
  }

  // Load users that id follows, either from the cache or from the database,
  // and subscribes to future updates.
  def followed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    lockSlot(id) match {
      case NewSlot(slot, lock) =>
        lock.unlock()
        doLoadFollowed(id)
      case ExistingSlot(slot, lock) =>
        if (slots(slot).fresh) {
          val infos = readFollowed(slot)
          lock.unlock()
          Future successful infos
        } else {
          lock.unlock()
          doLoadFollowed(id)
        }
    }
  }

  private def toggleFollow(on: Boolean)(left: User.ID, right: User.ID): Unit = {
    lockSlot(left) match {
      case ExistingSlot(leftSlot, leftLock) =>
        lockSlot(right) match {
          case ExistingSlot(rightSlot, rightLock) =>
            leftFollowsRight.toggle(on)(leftSlot, rightSlot)
            rightFollowsLeft.toggle(on)(rightSlot, leftSlot)
            rightLock.unlock()
          case NewSlot(_, rightLock) =>
            rightLock.unlock()
        }
        leftLock.unlock()
      case NewSlot(_, leftLock) =>
        leftLock.unlock()
    }
  }

  // Update cached relations.
  def follow(left: User.ID, right: User.ID) = toggleFollow(true)(left, right)
  def unfollow(left: User.ID, right: User.ID) = toggleFollow(false)(left, right)

  // Updates the status of a user. Returns the list of subscribed users that
  // are intrested in this update.
  def tell(id: User.ID, meta: UserMeta): List[User.ID] = {
    lockSlot(id) match {
      case ExistingSlot(slot, lock) =>
        slots(slot) = slots(slot).copy(meta = Some(meta))
        val followed = readFollowing(slot)
        lock.unlock()
        followed
      case NewSlot(slot, lock) =>
        slots(slot) = UserEntry(id, None, Some(meta), false)
        lock.unlock()
        Nil
    }
  }
}

object SocialGraph {
  private val MaxStride: Int = 20
}

private class AdjacenyList {
  private val inner: ConcurrentSkipListSet[Long] = new ConcurrentSkipListSet()

  def toggle(on: Boolean) = if (on) add _ else remove _
  def add(a: Int, b: Int): Unit = inner.add(AdjacenyList.makePair(a, b))
  def remove(a: Int, b: Int): Unit = inner.remove(AdjacenyList.makePair(a, b))
  def has(a: Int, b: Int): Boolean = inner.contains(AdjacenyList.makePair(a, b))

  def read(a: Int): List[Int] =
    inner.subSet(AdjacenyList.makePair(a, 0), AdjacenyList.makePair(a + 1, 0)).asScala.map { entry =>
      entry.toInt & 0xffffffff
    }.toList
}

private object AdjacenyList {
  private def makePair(a: Int, b: Int): Long = (a.toLong << 32) | b.toLong
}

case class UserMeta(online: Boolean)
case class UserRecord(id: User.ID, username: String)
case class UserInfo(id: User.ID, username: String, meta: Option[UserMeta])

private case class UserEntry(id: User.ID, username: Option[String], meta: Option[UserMeta], fresh: Boolean)

private sealed trait Slot
private case class NewSlot(slot: Int, lock: ReentrantLock) extends Slot
private case class ExistingSlot(slot: Int, lock: ReentrantLock) extends Slot
