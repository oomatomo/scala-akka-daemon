import akka.actor._

class Test2Actor extends Actor with ActorLogging {

  def receive = {
    case msg: String => {
      println("start test2")
      Thread.sleep(10000)
      println("done test2")
      log.info("done Test2Actor")
    }
  }

  /**
    *
    * 停止時の処理
    */
  override def postStop() = {
    log.info("stopped Test2Actor")
  }
}