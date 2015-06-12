package org.apache.spark.metrics

import java.util.concurrent.CountDownLatch

import org.apache.spark.executor.{DataWriteMethod, OutputMetrics, TaskMetrics}
import org.apache.spark.{TaskContext, SparkConf, SparkEnv}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import com.datastax.spark.connector.writer.{RichStatement, WriteConf}

class OutputMetricsUpdaterSpec extends FlatSpec with Matchers with BeforeAndAfter with MockitoSugar {

  val ts = System.currentTimeMillis()

  after {
    SparkEnv.set(null)
  }

  private def newTaskContext(): TaskContext = {
    val tc = mock[TaskContext]
    when(tc.taskMetrics()) thenReturn new TaskMetrics
    tc
  }

  private def newRichStatement(): RichStatement = {
    new RichStatement() {
      override val bytesCount = 100
      override val rowsCount = 10
    }
  }

  "OutputMetricsUpdater" should "initialize task metrics properly when they are empty" in {
    val tc = newTaskContext()
    tc.taskMetrics().outputMetrics = None

    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.cassandra.output.metrics", "true")

    SparkEnv.set(mock[SparkEnv])
    OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))

    tc.taskMetrics().outputMetrics.isDefined shouldBe true
    tc.taskMetrics().outputMetrics.get.writeMethod shouldBe DataWriteMethod.Hadoop
    tc.taskMetrics().outputMetrics.get.bytesWritten shouldBe 0L
    tc.taskMetrics().outputMetrics.get.recordsWritten shouldBe 0L
  }

  it should "initialize task metrics properly when they are defined" in {
    val tc = newTaskContext()
    tc.taskMetrics().outputMetrics = Some(new OutputMetrics(DataWriteMethod.Hadoop))

    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.cassandra.output.metrics", "true")

    SparkEnv.set(mock[SparkEnv])
    OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))

    tc.taskMetrics().outputMetrics.isDefined shouldBe true
    tc.taskMetrics().outputMetrics.get.writeMethod shouldBe DataWriteMethod.Hadoop
    tc.taskMetrics().outputMetrics.get.bytesWritten shouldBe 0L
    tc.taskMetrics().outputMetrics.get.recordsWritten shouldBe 0L
  }

  it should "create updater which uses task metrics" in {
    val tc = newTaskContext()
    tc.taskMetrics().outputMetrics = None

    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.cassandra.output.metrics", "true")

    SparkEnv.set(mock[SparkEnv])
    val updater = OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))

    val rc = newRichStatement()
    updater.batchFinished(success = true, rc, ts, ts)
    tc.taskMetrics().outputMetrics.get.bytesWritten shouldBe 100L // change registered when success
    tc.taskMetrics().outputMetrics.get.recordsWritten shouldBe 10L

    updater.batchFinished(success = false, rc, ts, ts)
    tc.taskMetrics().outputMetrics.get.bytesWritten shouldBe 100L // change not regsitered when failure
    tc.taskMetrics().outputMetrics.get.recordsWritten shouldBe 10L
  }

  it should "create updater which does not use task metrics" in {
    val tc = mock[TaskContext]
    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.cassandra.output.metrics", "false")

    SparkEnv.set(mock[SparkEnv])
    val updater = OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))

    val rc = newRichStatement()
    updater.batchFinished(success = true, rc, ts, ts)
    updater.batchFinished(success = false, rc, ts, ts)

    verify(tc, never).taskMetrics()
  }

  it should "create updater which uses Codahale metrics" in {
    val tc = newTaskContext()
    val conf = new SparkConf(loadDefaults = false)
    SparkEnv.set(mock[SparkEnv])
    val ccs = new CassandraConnectorSource
    CassandraConnectorSource.instance should not be None

    val updater = OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))

    val rc = newRichStatement()
    updater.batchFinished(success = true, rc, ts, ts)
    ccs.writeRowMeter.getCount shouldBe 10L
    ccs.writeByteMeter.getCount shouldBe 100L
    ccs.writeSuccessCounter.getCount shouldBe 1L
    ccs.writeFailureCounter.getCount shouldBe 0L

    updater.batchFinished(success = false, rc, ts, ts)
    ccs.writeRowMeter.getCount shouldBe 10L
    ccs.writeByteMeter.getCount shouldBe 100L
    ccs.writeSuccessCounter.getCount shouldBe 1L
    ccs.writeFailureCounter.getCount shouldBe 1L

    updater.finish()
    ccs.writeTaskTimer.getCount shouldBe 1L
  }

  it should "create updater which doesn't use Codahale metrics" in {
    val tc = newTaskContext()
    val conf = new SparkConf(loadDefaults = false)
    SparkEnv.set(mock[SparkEnv])

    val updater = OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))
    val rc = newRichStatement()
    updater.batchFinished(success = true, rc, ts, ts)
    updater.batchFinished(success = false, rc, ts, ts)

    CassandraConnectorSource.instance shouldBe None

    updater.finish()
  }

  it should "work correctly with multiple threads" in {
    val tc = newTaskContext()
    tc.taskMetrics().outputMetrics = None

    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.cassandra.output.metrics", "true")

    SparkEnv.set(mock[SparkEnv])
    val updater = OutputMetricsUpdater(tc, WriteConf.fromSparkConf(conf))

    val rc = newRichStatement()

    val latch = new CountDownLatch(32)
    class TestThread extends Thread {
      override def run(): Unit = {
        latch.countDown()
        latch.await()
        for (i <- 1 to 100000)
          updater.batchFinished(success = true, rc, ts, ts)
      }
    }

    val threads = Array.fill(32)(new TestThread)
    threads.foreach(_.start())
    threads.foreach(_.join())

    tc.taskMetrics().outputMetrics.get.bytesWritten shouldBe 320000000L
    tc.taskMetrics().outputMetrics.get.recordsWritten shouldBe 32000000L
  }

}
