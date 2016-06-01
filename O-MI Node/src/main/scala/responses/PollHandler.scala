package responses

import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
//import scala.collection.JavaConverters._ //JavaConverters provide explicit conversion methods
//import scala.collection.JavaConversions.asJavaIterator
import scala.xml.NodeSeq
//import spray.http.StatusCode

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import responses.OmiGenerator._
import types.OdfTypes._
import types.OmiTypes._
import types._

trait PollHandler extends OmiRequestHandler{
  def subscriptionManager : ActorRef

  /** Method for handling PollRequest.
    * @param poll request
    * @return (xml response, HTTP status code)
    */
  def handlePoll(poll: PollRequest): Future[NodeSeq] = {
    val ttl = handleTTL(poll.ttl)
    implicit val timeout = Timeout(ttl) 
    val time = date.getTime
    val resultsFut =
      Future.sequence(poll.requestIDs.map { id =>

      val objectsF: Future[ Any /* Option[OdfObjects] */ ] = (subscriptionManager ? PollSubscription(id)).mapTo[Future[Option[OdfObjects]]].flatMap(n=>n)
      objectsF.recoverWith{
        case e: Throwable => Future.failed(new RuntimeException(
        s"Error when trying to poll subscription: ${e.getMessage}"))
      }

      objectsF.map{
        case Some(objects: OdfObjects) =>
          Results.poll(id.toString, objects)
        case None =>
          Results.notFoundSub(id.toString)
        //case Failure(e) =>
        //  throw new RuntimeException(
        //    s"Error when trying to poll subscription: ${e.getMessage}")
      }
    })
    val returnTuple = resultsFut.map(results =>
      xmlFromResults(
        1.0,
        results.toSeq: _*)
    )

    returnTuple
  }
}
