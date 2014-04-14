/**
 * Copyright 2014 the Akka Tracing contributors. See AUTHORS for more details.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.levkhomich.akka.tracing

import java.net.InetAddress
import java.nio.ByteBuffer
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import akka.actor.{Actor, Cancellable}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryBuffer

private[tracing] case class Span(id: Long, parentId: Option[Long], traceId: Long)

private[tracing] object SpanHolderInternalAction {
  final case class Sample(ts: TracingSupport)
  final case class Enqueue(msgId: Long, cancelJob: Boolean)
  case object SendEnqueued
  final case class SetRPCName(msgId: Long, service: String, rpc: String)
  final case class AddAnnotation(msgId: Long, a: thrift.Annotation)
  final case class AddBinaryAnnotation(msgId: Long, a: thrift.BinaryAnnotation)
  final case class CreateChildSpan(msgId: Long, parentId: Long)
  final case class SetSampleRate(sampleRate: Int)
}

/**
 * Internal API
 */
private[tracing] class SpanHolder(client: thrift.Scribe[Option], var sampleRate: Int) extends Actor {

  import SpanHolderInternalAction._

  private[this] var counter = 0L

  private[this] val spans = mutable.Map[Long, thrift.Span]()
  private[this] val sendJobs = mutable.Map[Long, Cancellable]()
  private[this] val serviceNames = mutable.Map[Long, String]()
  private[this] val sendQueue = mutable.UnrolledBuffer[thrift.Span]()

  private[this] val protocolFactory = new TBinaryProtocol.Factory()

  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt

  context.system.scheduler.schedule(0.seconds, 2.seconds, self, SendEnqueued)

  override def receive: Receive = {
    case Sample(ts) =>
      counter += 1
      lookup(ts.msgId) match {
        case None if counter % sampleRate == 0 =>
          val serverRecvAnn = thrift.Annotation(System.nanoTime / 1000, thrift.Constants.SERVER_RECV, None, None)
          if (ts.traceId.isEmpty)
            ts.traceId = Some(Random.nextLong())
          val spanInt = createSpan(ts.msgId, Span(ts.msgId, ts.parentId, ts.traceId.get))
          spans.put(ts.msgId, spanInt.copy(annotations = serverRecvAnn +: spanInt.annotations))

        case _ =>
      }

    case Enqueue(msgId, cancelJob) =>
      enqueue(msgId, cancelJob)

    case SendEnqueued =>
      send()

    case SetRPCName(msgId, service, rpc) =>
      lookup(msgId) foreach { spanInt =>
        spans.put(msgId, spanInt.copy(name = rpc))
        serviceNames.put(spanInt.id, service)
      }

    case AddAnnotation(msgId, a) =>
      lookup(msgId) foreach { spanInt =>
        spans.put(msgId, spanInt.copy(annotations = a +: spanInt.annotations))
        if (a.value == thrift.Constants.SERVER_SEND) {
          enqueue(msgId, cancelJob = true)
        }
      }

    case AddBinaryAnnotation(msgId, a) =>
      lookup(msgId) foreach { spanInt =>
        spans.put(msgId, spanInt.copy(binaryAnnotations = a +: spanInt.binaryAnnotations))
      }

    case CreateChildSpan(msgId, parentId) =>
      lookup(msgId) match {
        case Some(parentSpan) =>
          val spanInt = createSpan(msgId, Span(msgId, Some(parentSpan.id), parentSpan.traceId))
          spans.put(msgId, spanInt)
        case _ =>
          None
      }

    case SetSampleRate(sampleRate) =>
      this.sampleRate = sampleRate
  }

  override def postStop(): Unit = {
    spans.keys.foreach(id =>
      enqueue(id, cancelJob = true)
    )
    send()
    super.postStop()
  }

  @inline
  private def lookup(id: Long): Option[thrift.Span] =
    spans.get(id)

  private def createSpan(id: Long, span: Span): thrift.Span = {
    sendJobs.put(id, context.system.scheduler.scheduleOnce(30.seconds, self, Enqueue(id, cancelJob = false)))
    thrift.Span(span.traceId, null, span.id, span.parentId, Nil, Nil)
  }

  private def enqueue(id: Long, cancelJob: Boolean): Unit = {
    sendJobs.remove(id).foreach(job => if (cancelJob) job.cancel())
    spans.remove(id).foreach(span => sendQueue.append(span))
  }

  private def send(): thrift.ResultCode = {
    if (!sendQueue.isEmpty) {
      val messages = sendQueue.map(spanToLogEntry)
      sendQueue.clear()
      client.log(messages).getOrElse(thrift.ResultCode.TryLater)
    } else
      thrift.ResultCode.Ok
  }

  private def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    val buffer = new TMemoryBuffer(1024)
    val endpoint = getEndpoint(spanInt.id)

    spanInt.copy(
      annotations = spanInt.annotations.map(a => a.copy(host = Some(endpoint))),
      binaryAnnotations = spanInt.binaryAnnotations.map(a => a.copy(host = Some(endpoint)))
    ).write(protocolFactory.getProtocol(buffer))

    val thriftBytes = buffer.getArray.take(buffer.length)
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    thrift.LogEntry("zipkin", encodedSpan)
  }

  private def getEndpoint(spanId: Long): thrift.Endpoint = {
    val service = serviceNames.get(spanId).getOrElse("Unknown")
    thrift.Endpoint(localAddress, 0, service)
  }

}
