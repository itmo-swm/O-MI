
/**
  Copyright (c) 2015 Aalto University.

  Licensed under the 4-clause BSD (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at top most directory of project.

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
**/
package http

import java.net.InetSocketAddress

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import agentSystem.{AgentInfo, AgentName}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp
import akka.io.Tcp._
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import database.{EventSub, IntervalSub, PolledSub}
import responses.{RemoveSubscription, RequestHandler}
import types.Path

/** Object that contains all commands of InternalAgentCLI.
 */
object CLICmds
{
  case class ReStartAgentCmd(agent: String)
  case class StartAgentCmd(agent: String)
  case class StopAgentCmd(agent: String)
  case class ListAgentsCmd()
  case class ListSubsCmd()
  case class RemovePath(path: String)
}

import http.CLICmds._
object OmiNodeCLI{
  def props(
    sourceAddress: InetSocketAddress,
    agentSystem: ActorRef,
    subscriptionHandler: ActorRef,
    requestHandler: RequestHandler
  ) : Props = Props( new OmiNodeCLI( sourceAddress, agentSystem, subscriptionHandler, requestHandler ))
}
/** Command Line Interface for internal agent management. 
  *
  */
class OmiNodeCLI(
    sourceAddress: InetSocketAddress,
    agentLoader: ActorRef,
    subscriptionHandler: ActorRef,
    requestHandler: RequestHandler
  ) extends Actor with ActorLogging {

  val commands = """Current commands:
start <agent classname>
stop  <agent classname> 
list agents 
list subs 
remove <subsription id>
remove <path>
"""
  val ip = sourceAddress.toString.tail
  implicit val timeout : Timeout = 1.minute

  val commandTimeout = 1.minute

  private def help(): String = {
    log.info(s"Got help command from $ip")
    commands
  }
  private def listAgents(): String = {
    log.info(s"Got list agents command from $ip")
    val result = (agentLoader ? ListAgentsCmd())
      .map[String]{
        case agents: Seq[AgentInfo @unchecked] =>  // internal type 
          log.info("Received list of Agents. Sending ...")

          val colums = Vector("NAME","CLASS","RUNNING","OWNED COUNT", "CONFIG")
          val msg =
            f"${colums(0)}%-20s | ${colums(1)}%-40s | ${colums(2)} | ${colums(3)}%-11s | ${colums(3)}\n" +
            agents.map{
              case AgentInfo(name, classname, config, ref, running, ownedPaths) => 
                f"$name%-20s | $classname%-40s | $running%-7s | ${ownedPaths.size}%-11s | $config" 
            }.mkString("\n")

          msg +"\n"
        case _ => ""
      }
      .recover[String]{
        case a : Throwable =>
          log.warning("Failed to get list of Agents. Sending error message.")
          "Something went wrong. Could not get list of Agents.\n"
      }
    Await.result(result, commandTimeout)
  }
  private def listSubs(): String = {
    log.info(s"Got list subs command from $ip")
    val result = (subscriptionHandler ? ListSubsCmd())
      .map{
        case (intervals: Set[IntervalSub @unchecked],
              events: Set[EventSub] @unchecked,
              polls: Set[PolledSub] @unchecked) => // type arguments cannot be checked
          log.info("Received list of Subscriptions. Sending ...")

          val (idS, intervalS, startTimeS, endTimeS, callbackS, lastPolledS) =
            ("ID", "INTERVAL", "START TIME", "END TIME", "CALLBACK", "LAST POLLED")

          val intMsg= "Interval subscriptions:\n" + f"$idS%-10s | $intervalS%-20s | $startTimeS%-30s | $endTimeS%-30s | $callbackS\n" +
            intervals.map{ sub=>
              f"${sub.id}%-10s | ${sub.interval}%-20s | ${sub.startTime}%-30s | ${sub.endTime}%-30s | ${ sub.callback }"
            }.mkString("\n")

          val eventMsg = "Event subscriptions:\n" + f"$idS%-10s | $endTimeS%-30s | $callbackS\n" + events.map{ sub=>
              f"${sub.id}%-10s | ${sub.endTime}%-30s | ${ sub.callback }"
            }.mkString("\n")

          val pollMsg = "Poll subscriptions:\n" + f"$idS%-10s | $startTimeS%-30s | $endTimeS%-30s | $lastPolledS\n" +
            polls.map{ sub=>
              f"${sub.id}%-10s | ${sub.startTime}%-30s | ${sub.endTime}%-30s | ${ sub.lastPolled }"
            }.mkString("\n")

          s"$intMsg\n$eventMsg\n$pollMsg\n"
      }
      .recover{
        case a: Throwable  =>
          log.info("Failed to get list of Subscriptions.\n Sending ...")
          "Failed to get list of subscriptions.\n"
      }
    Await.result(result, commandTimeout)
  }
  private def startAgent(agent: AgentName): String = {
    log.info(s"Got start command from $ip for $agent")
    val result = (agentLoader ? StartAgentCmd(agent))
      .map{
        case msg: String =>
          msg +"\n"
      }
      .recover{
        case a : Throwable =>
          "Command failure unknown.\n"
      }
    Await.result(result, commandTimeout)
  }

  private def stopAgent(agent: AgentName): String = {
    log.info(s"Got stop command from $ip for $agent")
    val result = (agentLoader ? StopAgentCmd(agent))
      .map{
        case msg:String => 
          msg +"\n"
      }
      .recover{
        case a : Throwable =>
          "Command failure unknown.\n"
      }
    Await.result(result, commandTimeout)
  }

  private def remove(pathOrId: String): String = {
    log.info(s"Got remove command from $ip with parameter $pathOrId")

    if(pathOrId.forall(_.isDigit)){
      val id = pathOrId.toInt
      log.info(s"Removing subscription with id: $id")

      val result = (subscriptionHandler ? RemoveSubscription(id))
        .map{
          case true =>
            s"Removed subscription with $id successfully.\n"
          case false =>
            s"Failed to remove subscription with $id. Subscription does not exist or it is already expired.\n"
        }
        .recover{
          case a : Throwable =>
            "Command failure unknown.\n"
        }
      Await.result(result, commandTimeout)
    } else {
      log.info(s"Trying to remove path $pathOrId")
      if (requestHandler.handlePathRemove(Path(pathOrId))) {
          log.info(s"Successfully removed path")
          s"Successfully removed path $pathOrId\n"
      } else {
          log.info(s"Given path does not exist")
          s"Given path does not exist\n"
      }
    } //requestHandler isn't actor

  }

  private def send(receiver: ActorRef)(msg: String): Unit =
    receiver ! Write(ByteString(msg)) 


  def receive : Actor.Receive = {
    case Received(data) =>{ 
      val dataString : String = data.decodeString("UTF-8")

      val args = dataString.split("( |\n)").toVector
      args match {
        case Vector("help") => send(sender)(help())
        case Vector("list", "agents") => send(sender)(listAgents())
        case Vector("list", "subs") => send(sender)(listSubs())
        case Vector("start", agent) => send(sender)(startAgent(agent))
        case Vector("stop", agent)  => send(sender)(stopAgent(agent))
        case Vector("remove", pathOrId) => send(sender)(remove(pathOrId))
        case Vector(cmd @ _*) => 
          log.warning(s"Unknown command from $ip: "+ cmd.mkString(" "))
          send(sender)("Unknown command. Use help to get information of current commands.\n") 
      }
    }
    case PeerClosed =>{
      log.info(s"CLI disconnected from $ip")
      context stop self
    }
  }
}

class OmiNodeCLIListener(agentLoader: ActorRef, subscriptionHandler: ActorRef, requestHandler: RequestHandler)  extends Actor with ActorLogging{

  import Tcp._

  def receive : Actor.Receive={
    case Bound(localAddress) =>
    // TODO: do something?
    // It seems that this branch was not executed?

    case CommandFailed(b: Bind) =>
      log.warning(s"CLI connection failed: $b")
      context stop self

    case Connected(remote, local) =>
      val connection = sender()
      log.info(s"CLI connected from $remote to $local")

      val cli = context.system.actorOf(
        OmiNodeCLI.props( remote, agentLoader, subscriptionHandler, requestHandler ),
        "cli-" + remote.toString.tail)
        connection ! Register(cli)
    case _ => //noop?
  }

}
