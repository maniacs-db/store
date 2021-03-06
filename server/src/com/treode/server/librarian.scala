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

package com.treode.server

import com.treode.async.Callback, Callback.ignore
import com.treode.store.StoreController

trait Librarian {

  def schema: Schema

  def schema_= (schema: Schema)
}

class LiveLibrarian (controller: StoreController) extends Librarian {

  @volatile private var _schema = Schema.empty

  controller.listen (Schema.catalog) (_schema = _)

  def schema = _schema

  def schema_= (s1: Schema) {
    val s2 = s1.copy (version = _schema.version + 1)
    controller.issue (Schema.catalog) (s2.version, s2) .run (ignore)
  }}
