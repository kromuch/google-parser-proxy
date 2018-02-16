import scala.io.Source
import scala.util.matching.Regex
import java.io._
import java.net.{HttpURLConnection, InetAddress, InetSocketAddress}
import java.util.concurrent.{Executors, TimeUnit}
import java.util.{Timer, TimerTask}
import javax.net.ssl.HttpsURLConnection

import com.typesafe.config.ConfigFactory
import models._
import slick.jdbc.PostgresProfile.api._
import akka.actor.{Actor, ActorSystem, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

object Main {
  @throws(classOf[java.io.IOException])
  @throws(classOf[java.net.SocketTimeoutException])
  def get(url: String,
          connectTimeout: Int = 5000,
          readTimeout: Int = 5000,
          requestMethod: String = "GET"): String = {
    import java.net.{URL, HttpURLConnection}
    val connection =
      new URL(url).openConnection.asInstanceOf[HttpURLConnection]
    connection.setConnectTimeout(connectTimeout)
    connection.setReadTimeout(readTimeout)
    connection.setRequestMethod(requestMethod)
    val inputStream = connection.getInputStream
    val content = io.Source.fromInputStream(inputStream).mkString
    if (inputStream != null) inputStream.close()
    content
  }

  def main(args: Array[String]): Unit = {
    val dbURL =
      ConfigFactory.load().getString("dbURL")
    initDB(dbURL)
    val timer: Timer = new Timer()
    val hourlyTask: TimerTask = new TimerTask() {
      @Override
      def run() {
        println("DB update time!")
        dbUpdate(dbURL)
        println("DB updated")
      }
    }
    timer.schedule(hourlyTask, 0l, 1000 * 60 * 60)
  }
  def initDB(dbURL: String): Unit = {
    try {
      val db: Database = Database.forURL(dbURL)
      Await.result(db.run(ProxyTable.table.schema.create), Duration.Inf)
      db.close()
    } catch {
      case _: Throwable => println("Table is already exist")
    }
  }
  def checkProxyProtocol(IP: String, port: String, delay: Int): String = {
    try {
      val proxy: java.net.Proxy = new java.net.Proxy(
        java.net.Proxy.Type.HTTP,
        new InetSocketAddress(IP, port.toInt))
      val connection: HttpURLConnection = new java.net.URL("https://google.com")
        .openConnection(proxy)
        .asInstanceOf[HttpsURLConnection]
      connection.setReadTimeout(delay)
      connection.setConnectTimeout(delay)
      connection.connect()
      Source.fromInputStream(connection.getInputStream).getLines.mkString
      "HTTP"
    } catch {
      case _: Throwable =>
        try {
          val proxy: java.net.Proxy = new java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            new InetSocketAddress(IP, port.toInt))
          val connection: HttpURLConnection =
            new java.net.URL("https://google.com")
              .openConnection(proxy)
              .asInstanceOf[HttpsURLConnection]
          connection.setReadTimeout(delay)
          connection.setConnectTimeout(delay)
          connection.connect()
          Source.fromInputStream(connection.getInputStream).getLines.mkString
          "SOCKS"
        } catch {
          case _: Throwable =>
            try {
              val proxy: java.net.Proxy = new java.net.Proxy(
                java.net.Proxy.Type.DIRECT,
                new InetSocketAddress(IP, port.toInt))
              val connection: HttpURLConnection =
                new java.net.URL("https://google.com")
                  .openConnection(proxy)
                  .asInstanceOf[HttpsURLConnection]
              connection.setReadTimeout(delay)
              connection.setConnectTimeout(delay)
              connection.connect()
              Source
                .fromInputStream(connection.getInputStream)
                .getLines
                .mkString
              "DIRECT"
            } catch {
              case _: Throwable =>
                ""
            }
        }
    }
  }

  def parse(dbURL: String): Unit = {
    val db: Database = Database.forURL(dbURL)
    val regex: Regex =
      "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{1,5})".r
    val prr: scala.collection.mutable.Set[(String, String)] =
      scala.collection.mutable.Set[(String, String)]()

    def parseSources() {
      val proxiesFile = new File("proxies.txt")
      val prBw = new BufferedWriter(new FileWriter(proxiesFile))
      val proxyList: List[String] =
        Source.fromFile(new File("proxy-source_sguru.txt")).getLines().toList
      val notThrottledExecutionContext: ExecutionContext =
        new ExecutionContext {
          val threadPool = Executors.newFixedThreadPool(4000)

          def execute(runnable: Runnable) {
            threadPool.submit(runnable)
          }

          def reportFailure(t: Throwable) {}
        }
      val proxySourceTimeout: Int =
        ConfigFactory.load().getInt("proxySourceTimeout")

      def runProxyList(pr: String) =
        Future {
          try {
            val html = get(pr, proxySourceTimeout, proxySourceTimeout)
            for (i <- regex.findAllMatchIn(html)) {
              prBw.write(i.group(1) + ":" + i.group(2) + '\n')
              prr += ((i.group(1), i.group(2)))
            }
          } catch {
            case _: Throwable =>
          }
        }(notThrottledExecutionContext).recover {
          case _: Exception =>
        }(notThrottledExecutionContext)

      val proxySourceFutureList: immutable.Seq[Future[Unit]] =
        proxyList.map(pr => runProxyList(pr))
      val fProxySourceList = Future.sequence(proxySourceFutureList)(
        implicitly,
        notThrottledExecutionContext)
      Await.result(fProxySourceList, Duration.Inf)
      prBw.close()
    }

    parseSources()

    def checker() = {
      val file = new File("out.txt")
      val proxies = prr.toList
      val file1 = "whatever.txt"
      val writer = new BufferedWriter(new FileWriter(file1))
      proxies.foreach(a => writer.write(a._1 + ":" + a._2 + "\n"))
      writer.close()
      var goodProxies: scala.collection.mutable.Buffer[(String, String)] =
        ArrayBuffer()
      val system = ActorSystem("checker")
      //implicit val executionContext: ExecutionContext = system.dispatchers.defaultGlobalDispatcher
      implicit val executionContext: ExecutionContext = system.dispatchers.lookup("task-dispatcher")
      val timeout: Int = ConfigFactory.load().getInt("proxyTimeout")
      case class ProxyMes(IP:String,port:String)
      class CheckActor extends Actor {
        override def receive: Receive = {
          case addr: ProxyMes =>
            val result = runProxyCheck((addr.IP,addr.port))
            sender() ! result
          case _ => println("bad message")
        }
        def runProxyCheck(addr: (String, String)): Future[Any] =
          Future {
            try {
              val inetAddr: InetAddress = InetAddress.getByName(addr._1)
              if (inetAddr.isReachable(timeout))
                if (addr._1 != "127.0.0.1") {
                  val protocol = checkProxyProtocol(addr._1, addr._2, timeout)
                  if (protocol != "") {
                    val defaultCurrentConnections = 0
                    val defaultTTL: Byte =
                      ConfigFactory.load().getInt("defaultTTL").toByte
                    val currentRecord: Option[Proxy] =
                      Await.result(db.run(
                        ProxyTable.table
                          .filter(_.IP === addr._1)
                          .filter(_.port === addr._2)
                          .result
                          .headOption),
                        Duration.Inf)
                    if (currentRecord.isDefined) {
                      val proxy = Proxy(currentRecord.get.IP,
                        currentRecord.get.port,
                        defaultTTL,
                        currentRecord.get.protocol)
                      db.run(
                        ProxyTable.table
                          .filter(_.IP === addr._1)
                          .filter(_.port === addr._2)
                          .update(proxy))
                    } else {
                      val proxy = Proxy(addr._1,
                        addr._2,
                        defaultTTL,
                        protocol)
                      db.run(ProxyTable.table += proxy)
                    }
                    goodProxies += ((addr._1, addr._2))
                  }
                }
            } catch {
              case _: Throwable =>
            }
          }.recover {
            case _: Exception =>
          }
      }
      object CheckActor{
        def props():Props = Props(new CheckActor)
      }
      val actorRef = system.actorOf(CheckActor.props(),"checkerActor")
      val checkingProxies: immutable.Seq[Future[Any]] =
        proxies.map { addr =>
          val adr = ProxyMes(addr._1,addr._2)
          implicit val timeout: Timeout = Timeout(1, TimeUnit.MINUTES)
          (actorRef ? adr).map(_.asInstanceOf[Future[Any]])
        }
      val allCheckers = Future.sequence(checkingProxies)
      println("Start of waiting!")
      Await.result(allCheckers, Duration.Inf)
      println(proxies.size)
      println(goodProxies.size)
      val bw = new BufferedWriter(new FileWriter(file))
      bw.flush()
      for (good <- goodProxies)
        bw.write(good._1 + ":" + good._2 + '\n')
      bw.close()
      db.close()
    }
    checker()
  }

  def dbUpdate(dbURL: String): Unit = {
    def badProxy(addr: Proxy, db: Database) = {
      if (addr.TTL > 0) {
        val newProxy = Proxy(addr.IP,
          addr.port,
          (addr.TTL - 1).toByte,
          addr.protocol)
        db.run(
          ProxyTable.table
            .filter(_.IP === addr.IP)
            .filter(_.port === addr.port)
            .update(newProxy))
      } else {
        db.run(
          ProxyTable.table
            .filter(_.IP === addr.IP)
            .filter(_.port === addr.port)
            .delete)
      }
    }
    val db: Database = Database.forURL(dbURL)
    val allProxies: List[Proxy] =
      Await.result(db.run(ProxyTable.table.result), Duration.Inf).toList
    val maxThreadsNumber = ConfigFactory.load().getInt("maxThreadsNumber")
    //import ExecutionContext.Implicits.global
    implicit val ec = new ExecutionContext {
      val threadPool = Executors.newFixedThreadPool(maxThreadsNumber)

      def execute(runnable: Runnable) {
        threadPool.submit(runnable)
      }

      def reportFailure(t: Throwable) {}
    }
    val timeout: Int = ConfigFactory.load().getInt("proxyTimeout")
    def run(addr: Proxy): Future[Any] =
      Future {
        try {
          val inetAddr: InetAddress = InetAddress.getByName(addr.IP)
          if (inetAddr.isReachable(timeout)) {
            if (inetAddr.isReachable(timeout)) {
              val protocol = checkProxyProtocol(addr.IP, addr.port, timeout)
              if (protocol == "") {
                badProxy(addr, db)
              } else {
                val defaultTTL: Byte =
                  ConfigFactory.load().getInt("defaultTTL").toByte
                val currentRecord: Option[Proxy] =
                  Await.result(db.run(
                    ProxyTable.table
                      .filter(_.IP === addr.IP)
                      .filter(_.port === addr.port)
                      .result
                      .headOption),
                    Duration.Inf)
                if (currentRecord.isDefined) {
                  val proxy = Proxy(currentRecord.get.IP,
                    currentRecord.get.port,
                    defaultTTL,
                    currentRecord.get.protocol)
                  db.run(
                    ProxyTable.table
                      .filter(_.IP === addr.IP)
                      .filter(_.port === addr.port)
                      .update(proxy))
                }
              }
            } else {
              badProxy(addr, db)
            }
          } else {
            badProxy(addr, db)
          }
        } catch {
          case _: Throwable =>
        }
      }.recover {
        case _: Exception =>
      }
    val list: Seq[Future[Any]] = allProxies.map(addr => run(addr))
    //val list = for (addr <- allProxies) yield run(addr)
    val fList = Future.sequence(list)
    Await.result(fList, Duration.Inf)
    println("FINISHED!")
    db.close()
    parse(dbURL)
  }
}
