
import akka.actor._

class Test1Actor extends Actor with ActorLogging {

  def receive = {
    case msg: String => {
      println("start test1")
      Thread.sleep(5000)
      println("done test1")
      log.info("done Test1Actor")
    }
  }

  /**
    *
    * 停止時の処理
    */
  override def postStop() = {
    log.info("stopped Test1Actor")
  }
}