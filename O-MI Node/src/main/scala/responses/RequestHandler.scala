package responses

import parsing.Types._
import parsing.Types.OmiTypes._
import parsing.Types.OdfTypes._
import parsing.Types.Path
import parsing.xmlGen
import parsing.xmlGen.scalaxb
import database._
import CallbackHandlers.sendCallback

import scala.util.{Try, Success, Failure}
import scala.concurrent.duration._
import scala.concurrent.{Future, Await, ExecutionContext, TimeoutException}

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.event.LoggingAdapter
import akka.util.Timeout
import akka.pattern.ask

import scala.xml.NodeSeq
import scala.collection.JavaConversions.iterableAsScalaIterable
import java.sql.Timestamp
import java.util.Date
import xml._
import scala.collection.mutable.Buffer

class RequestHandler(val  subscriptionHandler: ActorRef)(implicit val dbConnection: DB) {

  import scala.concurrent.ExecutionContext.Implicits.global
  private def date = new Date()

  def handleRequest(request: OmiRequest)(implicit ec: ExecutionContext): (NodeSeq, Int) = {
    if (request.callback.nonEmpty){
      // TODO: Can't cancel this callback
      Future{ runGeneration(request) } map { case (xml : NodeSeq, code: Int) =>
      sendCallback(request.callback.get.toString, xml)
    }
    (
      xmlFromResults(
        1.0,
        Result.simpleResult("200", Some("OK, callback job started"))
        ),
        200
      )

    } else {
      runGeneration(request)
    }
  }

  def runGeneration(request: OmiRequest)(implicit ec: ExecutionContext): (NodeSeq, Int) = {
    val timeout = if (request.ttl > 0) request.ttl.seconds else Duration.Inf
    val responseFuture = Future{xmlFromRequest(request)}
    Try {
      Await.result(responseFuture, timeout)
    } match {
      case Success((xml: NodeSeq, code : Int)) => (xml, code)

      case Failure(e: TimeoutException) => 
      (
        xmlFromResults(
          1.0,
          Result.simpleResult("500", Some("TTL timeout, consider increasing TTL or is the server overloaded?"))
        ),
        500
      )
      case Failure(e: RequestHandlingException) => 
      actionOnInternalError(e)
      (
        xmlFromResults(
          1.0,
          Result.simpleResult(e.errorCode.toString, Some( e.getMessage()))
        ),
        501
      )
      case Failure(e) => 
      actionOnInternalError(e)
      (
        xmlFromResults(
          1.0,
          Result.simpleResult("501", Some( "Internal server error: " + e.getMessage()))
        ),
        501
      )
    }
  }

  def actionOnInternalError: Throwable => Unit = { _ => /*noop*/ }

  def xmlFromRequest(request: OmiRequest) : (NodeSeq, Int) = request match {
    case read : ReadRequest =>{
      handleRead(read)
    }
    case poll : PollRequest =>{
      handlePoll(poll)
    }
    case subscription : SubscriptionRequest =>{
      handleSubscription(subscription)
    }
    case write : WriteRequest =>{
      ( notImplemented, 505 )
    }
    case response : ResponseRequest =>{
      ( notImplemented, 505 )
    }
    case cancel : CancelRequest =>{
      handleCancel(cancel)
    }
    case subdata : SubDataRequest =>{
      val objects : OdfObjects = dbConnection.getSubData(subdata.sub.id.get)
      ( xmlFromResults( 1.0, Result.pollResult(subdata.sub.id.toString,objects) ), 200 )
    }
    case _ =>{
      ( xmlFromResults( 1.0, Result.simpleResult("500", Some( "Unknown request." ) ) ), 500)
    }
  }

  private val scope =scalaxb.toScope(
    None -> "odf.xsd",
    Some("omi") -> "omi.xsd",
    Some("xs") -> "http://www.w3.org/2001/XMLSchema",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )
  def wrapResultsToResponseAndEnvelope(ttl: Double, results: xmlGen.RequestResultType* ) = {
    OmiGenerator.omiEnvelope( ttl, "response", OmiGenerator.omiResponse( results:_* ) )
  }

  def xmlFromResults(ttl: Double, results: xmlGen.RequestResultType* ) = {
    xmlMsg( wrapResultsToResponseAndEnvelope(ttl,results:_*) )
  }

  def xmlMsg( envelope: xmlGen.OmiEnvelope) = {
    scalaxb.toXML[xmlGen.OmiEnvelope]( envelope, Some("omi"), Some("omiEnvelope"), scope )
  }


  def handleRead(read: ReadRequest) : (NodeSeq, Int) = {
      val objects: OdfObjects = dbConnection.getNBetween(getLeafs(read.odf), read.begin, read.end, read.newest, read.oldest )
      (
        xmlFromResults(
          1.0,
          Result.readResult(objects)
        ),
        200
      )
  }

  def handlePoll( poll : PollRequest ) : (NodeSeq, Int ) ={
    (
      xmlFromResults(
        1.0,
        poll.requestIds.map{
          id => 
          val objects : OdfObjects = dbConnection.getSubData(id)
          Result.pollResult( id.toString, objects ) 
        }.toSeq : _*
      ),
      200
    )
  } 

  def handleSubscription( subscription: SubscriptionRequest ) : ( NodeSeq, Int) ={
    implicit val timeout= Timeout( 10.seconds ) // NOTE: ttl will timeout from elsewhere
    val subFuture = subscriptionHandler ? NewSubscription(subscription)
    var returnCode = 200
    (
      xmlFromResults(
        1.0,
        Await.result(subFuture, Duration.Inf) match {
          case -1 => 
            returnCode = 501
            Result.internalError("Internal server error when trying to create subscription")
          case id: Int =>
            Result.subscriptionResult(id.toString) 
        }
      ),
      returnCode
    )
  }

  def handleCancel( cancel: CancelRequest ) : (NodeSeq, Int) = {
    implicit val timeout= Timeout( 10.seconds ) // NOTE: ttl will timeout from elsewhere
    var returnCode = 200
    val jobs = cancel.requestId.map { id =>
      Try {
        val parsedId = id.toInt
        subscriptionHandler ? RemoveSubscription(parsedId)
      }
    }
    (
      xmlFromResults(
        1.0,
        jobs.map {
          case Success(removeFuture) =>
          // NOTE: ttl will timeout from OmiService
            Await.result(removeFuture, Duration.Inf) match {
              case true => Result.success
              case false =>{ 
                returnCode = 404
                Result.notFound
              }
              case _ =>{
                returnCode = 501
                Result.internalError()
              }
            }
            case Failure(n: NumberFormatException) =>{
              returnCode = 400
              Result.simpleResult(returnCode.toString, Some("Invalid requestId"))
            }
            case Failure(e : RequestHandlingException) =>{ 
              returnCode = e.errorCode
              Result.simpleResult(returnCode.toString, Some(e.msg))
            }
            case Failure(e) =>{
              returnCode = 501
              Result.internalError("Internal server error, when trying to cancel subscription: " + e.toString)
            }
        }.toSeq:_*
      ),
      returnCode
    )
  }

  def unauthorized = xmlFromResults(
    1.0,
    Result.unauthorized
  )
  def notImplemented = xmlFromResults(
    1.0,
    Result.notImplemented 
  )
  def parseError(err: ParseError*) =
  xmlFromResults(
    1.0,
    Result.simpleResult("400", Some( err.map{e => e.msg}.mkString("\n")))
  )
  def internalError(e: Throwable) =
  xmlFromResults(
    1.0,
    Result.simpleResult("501", Some( "Internal server error: " + e.getMessage()))
  )
  def timeOutError = xmlFromResults(
    1.0,
    Result.simpleResult("500", Some("TTL timeout, consider increasing TTL or is the server overloaded?"))
  )
/**
  * Generates ODF containing only children of the specified path's (with path as root)
  * or if path ends with "value" it returns only that value.
  *
  * @param orgPath The path as String, elements split by a slash "/"
  * @return Some if found, Left(string) if it was a value and Right(xml.Node) if it was other found object.
  */
  def generateODFREST(orgPath: Path)(implicit dbConnection: DB): Option[Either[String, xml.Node]] = {

    // Removes "/value" from the end; Returns (normalizedPath, isValueQuery)
    def restNormalizePath(path: Path): (Path, Int) = path.lastOption match {
      case Some("value") => (path.init, 1) 
      case Some("MetaData") => (path.init, 2) 
      case _             => (path, 0)
    }

    // safeguard
    assert(!orgPath.isEmpty, "Undefined url data discovery: empty path")

    val (path, wasValue) = restNormalizePath(orgPath)


    dbConnection.get(path) match {
      case Some(sensor: DBSensor) =>
      if (wasValue == 1){
        return Some(Left(sensor.value))
      }else if (wasValue == 2){
        val metaData = dbConnection.getMetaData(path)
        if(metaData.isEmpty)
          return Some(Left("No metadata found."))
        else
          return Some(Right(XML.loadString(metaData.get)))

      }else{
        return Some(Right(
          <InfoItem name={ sensor.path.last }>
            <value dateTime={ sensor.time.toString.replace(' ', 'T') }>
              { sensor.value }
            </value>
            {
              val metaData = dbConnection.getMetaData(path)
              if(metaData.nonEmpty)
                <MetaData/>
            }
          </InfoItem>))
      }
      case Some(sensormap: DBObject) =>
      var resultChildren = Buffer[xml.Node]()

      for (item <- sensormap.childs) {
        dbConnection.get(item.path) match {
          case Some(sensor: DBSensor) => {
            resultChildren += <InfoItem name={ sensor.path.last }/>
          }

          case Some(subobject: DBObject) => {
            resultChildren += <Object><id>{ subobject.path.last }</id></Object>
          }

          case None => return None
        }
      }

      resultChildren = resultChildren.sortBy(_.mkString) //InfoItems are meant to come first

      val mapId = sensormap.path.lastOption.getOrElse("")
      val xmlReturn =
      if (mapId == "Objects") {
        <Objects>{ resultChildren }</Objects>
      } else {
        resultChildren.prepend(<id>{ mapId }</id>)
        <Object>{ resultChildren }</Object>
      }

      return Some(Right(xmlReturn))

      case None => return None
    }
  }
  case class RequestHandlingException(errorCode: Int, msg: String) extends Exception(msg)
}