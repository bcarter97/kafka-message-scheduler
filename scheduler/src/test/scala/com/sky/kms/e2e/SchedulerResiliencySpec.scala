package com.sky.kms.e2e

import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import cats.data.NonEmptyList
import cats.syntax.either._
import cats.syntax.option._
import com.sky.kms.actors.SchedulingActor.UpstreamFailure
import com.sky.kms.base.SpecBase
import com.sky.kms.config._
import com.sky.kms.domain.{ApplicationError, ScheduleEvent}
import com.sky.kms.kafka.Topic
import com.sky.kms.streams.ScheduleReader
import com.sky.kms.utils.TestDataUtils._
import com.sky.kms.utils.{StubControl, TestConfig}
import com.sky.kms.{AkkaComponents, SchedulerApp}
import eu.timepit.refined.auto._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SchedulerResiliencySpec extends SpecBase {

  override implicit val patienceConfig = PatienceConfig(10.seconds, 500.millis)

  "KMS" should {
    "terminate when the reader stream fails" in new TestContext with FailingSource with AkkaComponents {
      val app = createAppFrom(config)
        .withReaderSource(sourceThatWillFail)

      withRunningScheduler(app) { _ =>
        hasActorSystemTerminated shouldBe true
      }
    }

    "terminate when the publisher stream fails" in new TestContext with IteratingSource with AkkaComponents {
      val app =
        createAppFrom(config)
          .withReaderSource(sourceWith(random[ScheduleEvent](n = 10).map(_.secondsFromNow(2))))
          .withPublisherSink(Sink.ignore)

      withRunningScheduler(app) { runningApp =>
        runningApp.publisher.materializedSource.fail(new Exception("boom!"))

        hasActorSystemTerminated shouldBe true
      }
    }

    "terminate when the queue buffer becomes full" in new TestContext with IteratingSource with AkkaComponents {
      val sameTimeSchedules           = random[ScheduleEvent](n = 20).map(_.secondsFromNow(2))
      val probe                       = TestProbe()
      val sinkThatWillNotSignalDemand = Sink
        .actorRefWithBackpressure(probe.ref, "", "", "", UpstreamFailure)
        .mapMaterializedValue(_ => Future.never)

      val app =
        createAppFrom(config.copy(publisher = PublisherConfig(queueBufferSize = 1)))
          .withReaderSource(sourceWith(sameTimeSchedules))
          .withPublisherSink(sinkThatWillNotSignalDemand)

      withRunningScheduler(app) { _ =>
        hasActorSystemTerminated shouldBe true
      }
    }
  }

  private trait TestContext {
    val someTopic: Topic = "some-topic"
    val config           = TestConfig(NonEmptyList.one(someTopic))

    def createAppFrom(config: SchedulerConfig)(implicit system: ActorSystem): SchedulerApp =
      SchedulerApp.configure apply AppConfig(config)

    def hasActorSystemTerminated(implicit system: ActorSystem): Boolean =
      Await.ready(system.whenTerminated, 10.seconds).isCompleted
  }

  private trait FailingSource {
    this: TestContext =>

    val sourceThatWillFail: Source[ScheduleReader.In, (Future[Done], Future[Control])] =
      Source
        .fromIterator(() =>
          Iterator(("someId" -> none[ScheduleEvent]).asRight[ApplicationError]) ++ (throw new Exception("boom!"))
        )
        .mapMaterializedValue(_ => Future.successful(Done) -> Future.successful(StubControl()))
  }

  private trait IteratingSource {
    this: TestContext =>

    def sourceWith(schedules: Seq[ScheduleEvent]): Source[ScheduleReader.In, (Future[Done], Future[Control])] = {
      val scheduleIds = List.fill(schedules.size)(UUID.randomUUID().toString)

      val elements = scheduleIds.zip(schedules.map(_.some)).map(_.asRight[ApplicationError])

      Source(elements).mapMaterializedValue(_ => Future.successful(Done) -> Future.successful(StubControl()))
    }
  }

}
