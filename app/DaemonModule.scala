
import play.api._
import javax.inject._
import play.api.inject._
import scala.concurrent._
import akka.actor._
import akka.pattern.ask
import akka.pattern.gracefulStop
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import org.slf4j.{ LoggerFactory, MDC }

object Manager {
  case object Start
  case object Status
  case object ShutdownChildActor
  case object ChildActor
}

class ManagerActor(name: String) extends Actor with ActorLogging {
  import Manager._

  val childActor: ActorRef = name match {
    case "test1" =>
      context.watch(context.actorOf(Props[Test1Actor], "Test1Actor"))
    case "test2" =>
      context.watch(context.actorOf(Props[Test2Actor], "Test2Actor"))
  }

  def receive = {
    case ChildActor =>
      childActor ! "done"
    case ShutdownChildActor =>
      // PoisonPillはキューに停止命令を出す
      // actorはPoisonPillが処理されるまで処理を続けるため
      // 今回の停止処理では stop を使った
      // childActor ! PoisonPill
      context.stop(childActor)
      context.become(shuttingDown)
  }

  var isAliveChildActor = true

  def shuttingDown: Receive = {
    case Status =>
      log.info("stopping  ChildActor")
      sender ! isAliveChildActor.toString
    case Terminated(`childActor`) =>
      isAliveChildActor = false
      log.info("catch stopped CPCLogicActor on ManagerActor")
  }

  override def postStop() = {
    log.info("stopped ManagerActor")
  }
}

// play 2.4 で利用していたコード
//class DaemonModule extends Module {
//  def bindings(environment: Environment, configuration: Configuration) = {
//    Seq(
//      bind[DaemonInterface].toProvider[DaemonProvider].eagerly
//    )
//  }
//}
//@Singleton
//class DaemonProvider @Inject() (configuration: Configuration, lifecycle: ApplicationLifecycle) extends Provider[DaemonInterface] {
//  lazy val get = new Daemon(configuration, lifecycle)
//}
//trait DaemonInterface

@Singleton
class Daemon @Inject() (configuration: Configuration, lifecycle: ApplicationLifecycle) {

  // 設定ファイルの値を取得する
  val config = configuration.underlying
  val daemonProcess: String = config.getString("daemon-process")

  MDC.put("actorName", daemonProcess)

  val logger = LoggerFactory.getLogger(daemonProcess)
  // actorの開始
  val system = ActorSystem("DaemonSystem")

  // ManagerActorを通して、他のActorを呼び出す
  val managerActor: ActorRef = system.actorOf(Props(classOf[ManagerActor], daemonProcess))

  // スケジューラ調整
  implicit val executionContext = system.dispatchers.lookup("daemon-pinned-dispatcher")
  daemonProcess match {
    case "test1" =>
      system.scheduler.schedule(Duration.Zero, 5 seconds, managerActor, Manager.ChildActor)
    case "test2" =>
      system.scheduler.schedule(Duration.Zero, 10 seconds, managerActor, Manager.ChildActor)
  }

  // Play ストップ時にGraceful Stopにする
  lifecycle.addStopHook(() => Future.successful {

    // 子アクターを停止
    managerActor ! Manager.ShutdownChildActor
    implicit val timeout = Timeout(5 seconds)
    // managerActorのステータスの値の値の確認を行う
    var keepRunning = true
    while (keepRunning) {
      val r = Await.result(managerActor ? Manager.Status, timeout.duration).asInstanceOf[String]
      if (r == "false") {
        keepRunning = false
      }
      Thread.sleep(1000)
    }

    // 親アクターを停止
    try {
      val stopped: Future[Boolean] = gracefulStop(managerActor, 5 seconds)
      Await.result(stopped, 6 seconds)
      logger.info("stop managerActor success")
    } catch {
      // actorが５秒以内に停止しなかった場合
      case e: akka.pattern.AskTimeoutException =>
        logger.error("error stop managerActor", e)
    }
    system.terminate()
    MDC.clear()
  })
}