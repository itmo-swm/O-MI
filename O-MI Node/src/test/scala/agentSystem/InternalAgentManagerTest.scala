package agentSystem

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.mutable.Map
import akka.actor.{Actor, ActorSystem, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import akka.testkit._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.create.InterpolatedFragment
import org.specs2.mutable._
import com.typesafe.config.{ConfigFactory, Config}
import org.specs2.matcher._
import org.specs2.matcher.FutureMatchers._
import agentSystem._
import http.CLICmds._
import testHelpers.Actorstest



class InternalAgentManagerTest(implicit ee: ExecutionEnv) extends Specification {
  object TestManager{
    def props(  testAgents: scala.collection.mutable.Map[AgentName, AgentInfo]) : Props = {
      Props( new TestManager( testAgents ))
    } 
  }
 class TestManager( testAgents: scala.collection.mutable.Map[AgentName, AgentInfo]) extends BaseAgentSystem with InternalAgentManager{
   protected[this] val agents: scala.collection.mutable.Map[AgentName, AgentInfo] = testAgents
   protected[this] val settings : AgentSystemConfigExtension = http.Boot.settings
   def receive : Actor.Receive = {
     case  start: StartAgentCmd  => handleStart( start)
     case  stop: StopAgentCmd  => handleStop( stop)
     case  restart: ReStartAgentCmd  => handleRestart( restart )
     case ListAgentsCmd() => sender() ! agents.values.toSeq
   }
 }
 "InternalAgentManager should" >>{
   "return message when trying to" >> {
     "start allready running agent" >> startingRunningTest
     "stop allready stopped agent" >> stopingStoppedTest
     "command nonexistent agent" >> cmdToNonExistent
   }
   "successfully " >> {
     "stop running agent" >> stopingRunningTest
     "start stopped agent" >> startingStoppedTest
   }
   "return error message if agent fails to" >> {
     "stop" >> agentStopFailTest
     "start" >> agentStartFailTest
   }
 }  
 def AS = ActorSystem() 
 def emptyConfig = ConfigFactory.empty()
 def cmdToNonExistent = new Actorstest(AS){
   import system.dispatcher
   val name = "Nonexisting"
   val testAgents : Map[AgentName, AgentInfo] = Map.empty
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StartAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct =s"Could not find agent: $name"
   resF should beEqualTo(correct).await
 }

 def startingRunningTest = new Actorstest(AS){
   import system.dispatcher
   val name = "Running"
   val ref = ActorRef.noSender
   val clazz = "agentSystem.SSAgent"
   val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, true, Nil)
   val testAgents = Map( name -> agentInfo)
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StartAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct = s"Agent $name was already Running. 're-start' should be used to restart running Agents."
   resF should beEqualTo(correct).await
 }
 def stopingStoppedTest = new Actorstest(AS){
   import system.dispatcher
   val name = "Stopped"
   val ref = ActorRef.noSender
   val clazz = "agentSystem.SSAgent"
   val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, false, Nil)
   val testAgents = Map( name -> agentInfo)
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StopAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct = s"Agent $name was already stopped."
   resF should beEqualTo( correct ).await
 }


 def startingStoppedTest = new Actorstest(AS){
   import system.dispatcher
   val name = "StartSuccess"
   val ref = system.actorOf( Props( new SSAgent), name)
   val clazz = "agentSystem.FFAgent"
   val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, false, Nil)
   val testAgents = Map( name -> agentInfo)
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StartAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct = s"Agent $name started succesfully." 
   resF should beEqualTo( correct ).await
 }

 def stopingRunningTest = new Actorstest(AS){
   import system.dispatcher
   val name = "StopSuccess"
   val ref = system.actorOf( Props( new SSAgent), name)
   val clazz = "agentSystem.FFAgent"
   val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, true, Nil)
   val testAgents = Map( name -> agentInfo)
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StopAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct = s"Agent $name stopped succesfully." 
   resF should beEqualTo( correct ).await
 }
 
 def agentStopFailTest = new Actorstest(AS){
   import system.dispatcher
   val name = "Stopfail"
   val ref = system.actorOf( Props( new FFAgent), name)
   val clazz = "agentSystem.FFAgent"
   val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, true, Nil)
   val testAgents = Map( name -> agentInfo)
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StopAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct = s"agentSystem.CommandFailed: Test failure."
   resF should beEqualTo( correct ).await
 }
 def agentStartFailTest = new Actorstest(AS){
   import system.dispatcher
   val name = "Startfail"
   val ref = system.actorOf( Props( new FFAgent), name)
   val clazz = "agentSystem.FFAgent"
   val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, false, Nil)
   val testAgents = Map( name -> agentInfo)
   val manager = system.actorOf(TestManager.props(testAgents), "agent-manager") 
   val msg = StartAgentCmd(name)
   implicit val timeout = Timeout( 2.seconds)
   val resF = (manager ? msg).mapTo[Future[String]].flatMap(f => f)
   val correct = s"agentSystem.CommandFailed: Test failure."
   resF should beEqualTo( correct ).await
 }

}
