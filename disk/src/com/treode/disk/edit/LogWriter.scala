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

package com.treode.disk.edit

import scala.collection.mutable.UnrolledBuffer

import com.treode.async.{Async, Callback, Scheduler}, Callback.ignore
import com.treode.async.implicits._
import com.treode.async.io.File
import com.treode.disk.DriveGeometry
import com.treode.buffer.PagedBuffer

class LogWriter (
  file: File,
  geom: DriveGeometry,
  dsp: LogDispatcher
) (implicit
  scheduler: Scheduler
) {

  private var pos = 0L
  private val buf = PagedBuffer (12)

  // constantly listens for the next batch from the dispatcher
  listen() run (ignore)

  def listen(): Async [Unit] =
    for {
      (_, records) <- dsp.receive()
      _ <- record (records)
    } yield {
      listen() run (ignore)
    }

  /*
   * Write a batch of records of varying type to the log file.
   *
   * Each batch has format [length] [count] [records].
   *
   * The file will have an 0x1 byte between batches, and end with an 0x0 byte when
   * there are no more batch to read.
   */
  def record (batch: UnrolledBuffer [PickledRecord]): Async [Unit] = {
    // If there's previous data, re-mark the end to show continuation
    if (buf.writePos > 0)
      buf.writeByte (1)

    val start = buf.writePos
    buf.writeInt (0)  // reserve space for length of batch
    buf.writeInt (batch.length) // write count

    // Write all batch into the PagedBuffer
    for (v <- batch) {
      // Write TypeId of this record
      buf.writeLong (v.tid.id)
      // Write record
      v.write (buf)
    }
    val end = buf.writePos // remember where the log ends

    // Go back to beginning and fill in the proper length
    buf.writePos = start
    buf.writeInt (end - start - 8)  // subtract the length and count of batch

    // Mark the end with a 0 byte
    buf.writePos = end
    buf.writeByte (0)

    // Move up to the next block boundary; must write full block to disk
    buf.writePos = geom.blockAlignUp (buf.writePos)

    for {
      _ <- file.flush (buf, pos)
    } yield {
      // Discard all blocks in the PagedBuffer that have been filled and
      // written to disk
      buf.readPos = geom.blockAlignDown (end)
      buf.writePos = end
      pos += buf.discard (buf.readPos)
      // done flushing, call each entry's callback
      for (v <- batch)
        v.cb.pass(())
    }}}