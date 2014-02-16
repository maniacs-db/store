package com.treode.async

object Latch {

  def array [A] (count: Int, cb: Callback [Array [A]]) (implicit m: Manifest [A]): Callback [(Int, A)] =
    new ArrayLatch (count, cb)

  def map [K, V] (count: Int, cb: Callback [Map [K, V]]): Callback [(K, V)] =
    new MapLatch (count, cb)

  def seq [A] (count: Int, cb: Callback [Seq [A]]) (implicit m: Manifest [A]): Callback [A] =
    new SeqLatch (count, cb)

  def unit [A] (count: Int, cb: Callback [Unit]): Callback [A] =
    new CountingLatch (count, cb)
}