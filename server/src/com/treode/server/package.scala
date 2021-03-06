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

package com.treode

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.dataformat.smile.SmileFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.treode.async.BatchIterator
import com.treode.async.misc.RichOption
import com.treode.jackson.DefaultTreodeModule
import com.treode.store.{Bound, Bytes, Cell, InfiniteBound, Key, TableId, TxClock}
import com.treode.twitter.finagle.http.{RichResponse, BadRequestException, RichRequest}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.http.filter.{CommonLogFormatter, LoggingFilter}
import com.twitter.logging.Logger
import org.jboss.netty.handler.codec.http.HttpResponseStatus

package object server {

  implicit val textJson = new ObjectMapper with ScalaObjectMapper
  textJson.registerModule (DefaultScalaModule)
  textJson.registerModule (DefaultTreodeModule)
  textJson.registerModule (AppModule)

  val binaryJson = new ObjectMapper (new SmileFactory) with ScalaObjectMapper
  binaryJson.registerModule (DefaultScalaModule)

  val TableNotFound = new HttpResponseStatus (434, "Table Not Found")

  object respond {

    //
    // Responses with no TxClock headers.
    //

    def apply (req: Request, status: HttpResponseStatus): Response = {
      val rsp = req.response
      rsp.status = status
      rsp
    }

    def apply (req: Request, errors: Seq [SchemaParser.Message]): Response = {
      val rsp = req.response
      rsp.status = Status.BadRequest
      for (e <- errors)
        rsp.write (e.toString)
      rsp
    }

    def plain (req: Request, value: String): Response  = {
      val rsp = req.response
      rsp.status = Status.Ok
      rsp.plain = value
      rsp
    }

    //
    // Responses with TxClock headers.
    //

    def ok (req: Request, vt: TxClock): Response = {
      val rsp = req.response
      rsp.status = Status.Ok
      rsp.valueTxClock = vt
      rsp.serverTxClock = TxClock.now
      rsp
    }

    def json (req: Request, rt: TxClock, vt: TxClock, value: Any): Response  = {
      val rsp = req.response
      rsp.status = Status.Ok
      rsp.date = rt
      rsp.lastModified = vt
      rsp.readTxClock = rt
      rsp.valueTxClock = vt
      rsp.serverTxClock = TxClock.now
      rsp.vary = "Read-TxClock"
      rsp.json = value
      rsp
    }

    def json [A] (req: Request, iter: BatchIterator [A]): Response  = {
      val rsp = req.response
      rsp.status = Status.Ok
      rsp.serverTxClock = TxClock.now
      rsp.json = iter
      rsp
    }

    def json [A] (req: Request, vs: Seq [A]): Response  = {
      val rsp = req.response
      rsp.status = Status.Ok
      rsp.serverTxClock = TxClock.now
      rsp.json = vs
      rsp
    }

    def json [A] (req: Request, link: String, vs: Seq [A]): Response  = {
      val rsp = req.response
      rsp.status = Status.Ok
      rsp.serverTxClock = TxClock.now
      rsp.headers.add ("Link", link)
      rsp.json = vs
      rsp
    }

    def unmodified (req: Request, rt: TxClock, vt: TxClock): Response = {
      val rsp = req.response
      rsp.status = Status.NotModified
      rsp.readTxClock = rt
      rsp.valueTxClock = vt
      rsp.serverTxClock = TxClock.now
      rsp
    }

    def stale (req: Request, vt: TxClock): Response = {
      val rsp = req.response
      rsp.status = Status.PreconditionFailed
      rsp.valueTxClock = vt
      rsp.serverTxClock = TxClock.now
      rsp
    }

    def conflict (req: Request): Response = {
      val rsp = req.response
      rsp.status = Status.Conflict
      rsp.serverTxClock = TxClock.now
      rsp
    }

    def aborted (req: Request): Response = {
      val rsp = req.response
      rsp.status = Status.BadRequest
      rsp.serverTxClock = TxClock.now
      rsp.plain = "The transaction was aborted."
      rsp
    }

    def notAllowed (req: Request): Response = {
      val rsp = req.response
      rsp.status = Status.MethodNotAllowed
      rsp.serverTxClock = TxClock.now
      rsp
    }

    def notFound (req: Request, rt: TxClock, vt: TxClock): Response = {
      val rsp = req.response
      rsp.status = Status.NotFound
      rsp.readTxClock = rt
      rsp.valueTxClock = vt
      rsp.serverTxClock = TxClock.now
      rsp
    }

    def tableNotFound (req: Request): Response = {
      val rsp = req.response
      rsp.status = TableNotFound
      rsp.serverTxClock = TxClock.now
      rsp
    }}

  object LoggingFilter extends LoggingFilter (Logger ("access"), new CommonLogFormatter)

  implicit class RichAny (v: Any) {

    def toJsonText: String =
      textJson.writeValueAsString (v)
  }

  implicit class RichBytes (bytes: Bytes) {

    def toJsonNode: JsonNode =
      binaryJson.readValue (bytes.bytes, classOf [JsonNode])
  }

  implicit class RichJsonNode (node: JsonNode) {

    def toBytes: Bytes =
      Bytes (binaryJson.writeValueAsBytes (node))

    def getAttribute (attr: String): JsonNode = {
      val va = node.get (attr)
      if (va == null)
        throw new BadRequestException (s"There is no attribute called '$attr'")
      else
        va
    }}

  implicit class RichString (s: String) {

    def fromJson [A: Manifest]: A =
      textJson.readValue [A] (s)
  }}
