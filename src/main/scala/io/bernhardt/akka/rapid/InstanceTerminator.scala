package io.bernhardt.akka.rapid

import com.amazonaws.services.ec2.model.AmazonEC2Exception

/**
 * At scale, those t2 instances become really expensive
 */
object InstanceTerminator extends App {

  def tryTryAgain(): Unit = {
    try {
      terminate()
    } catch {
      case _: AmazonEC2Exception =>
        println("Sleeping 10 seconds")
        Thread.sleep(10000)
        tryTryAgain()
    }
  }

  tryTryAgain()


  private def terminate(): Unit = {
    ec2Api.foreach { api =>
      api.instances.filter(i => i.instance.name != "akka-template" && (i.instance.state.getName == "stopped" || i.instance.state.getName == "running")).grouped(50).foreach { group =>
        println(s"Terminating instances ${group.map(_.instanceId).mkString(" ")}")
        api.terminate(group :_*)
      }
    }
  }

}
