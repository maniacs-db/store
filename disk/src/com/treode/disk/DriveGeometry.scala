/*
 * Copyright 2014 Treode, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.treode.disk

import com.treode.disk.messages._
import com.treode.jackson.JsonReader
import com.treode.notify.Notification

class DriveGeometry private (
    val segmentBits: Int,
    val blockBits: Int,
    val diskBytes: Long
) {

  val segmentBytes = 1 << segmentBits
  val segmentMask = ~(segmentBytes - 1)

  val blockBytes = 1 << blockBits
  val blockMask = ~(blockBytes - 1)

  val segmentCount = ((diskBytes + segmentBytes - (blockBytes<<2)) >> segmentBits).toInt

  def isSegmentAligned (pos: Long): Boolean =
    (pos & ~segmentMask) == 0

  def isBlockAligned (pos: Long): Boolean =
    (pos & ~blockMask) == 0

  def blockAlignDown (pos: Int): Int =
    pos & blockMask

  def blockAlignDown (pos: Long): Long =
    pos & blockMask.toLong

  def blockAlignUp (length: Int): Int =
    (length + blockBytes - 1) & blockMask

  def blockAlignUp (length: Long): Long =
    (length + blockBytes - 1) & blockMask.toLong

  private [disk] def segmentNum (pos: Long): Int =
    (pos >> segmentBits) .toInt

  private [disk] def validForConfig() (implicit config: DiskConfig) {
    require (
        blockBits <= config.superBlockBits,
        "A superblock must be at least one disk block.")
    require (
        diskBytes >= config.diskLeadBytes,
        "A disk must be larger than two superblocks.")
    require (
        segmentBits >= config.minimumSegmentBits,
        "A segment must be larger than the largest record or page.")
  }

  override def hashCode: Int =
    (segmentBits, blockBits, diskBytes).hashCode

  override def equals (other: Any): Boolean =
    other match {
      case that: DriveGeometry =>
        segmentBits == that.segmentBits &&
        blockBits == that.blockBits &&
        diskBytes == that.diskBytes
      case _ =>
        false
    }

  override def toString: String =
    s"DriveGeometry($segmentBits, $blockBits, $diskBytes)"
}

object DriveGeometry {

  def apply (
      segmentBits: Int,
      blockBits: Int,
      diskBytes: Long
  ): DriveGeometry = {

    require (
        segmentBits > 0,
        "A segment must have more than 2^0 bytes.")
    require (
        blockBits > 0,
        "A block must have more than 2^0 bytes.")
    require (
        diskBytes > 0,
        "A disk must have more than 0 bytes.")
    require (
        segmentBits >= blockBits,
        "A segment must be at least one block.")
    require (
        diskBytes >> segmentBits >= 16,
        "A disk must have at least 16 segments")

    new DriveGeometry (
        segmentBits,
        blockBits,
        diskBytes)
  }

  def fromJson (node: JsonReader): Notification [DriveGeometry] =
    for {
      obj <- node.requireObject
      (segmentBits, blockBits, diskBytes) <- Notification.latch (
        node.getInt ("segmentBits", 1, Int.MaxValue),
        node.getInt ("blockBits", 1, Int.MaxValue),
        node.getLong ("diskBytes", 1L, Long.MaxValue))
      _ <- Notification.latch (
        node.require (segmentBits - blockBits >= 4, SegmentNeeds16Blocks),
        node.require (diskBytes >> segmentBits >= 16, DriveNeeds16Segments))
    } yield {
      new DriveGeometry (segmentBits, blockBits, diskBytes)
    }

  val pickler = {
    import DiskPicklers._
    wrap (uint, uint, ulong)
    .build { v =>
      val (s, b, d) = v
      require (s > 0 && b > 0 && d > 0)
      new DriveGeometry (s, b, d)
    }
    .inspect (v => (v.segmentBits, v.blockBits, v.diskBytes))
  }}
