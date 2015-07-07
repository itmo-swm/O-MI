package types

import OdfTypes._

import java.sql.Timestamp
import java.lang.Iterable
import scala.collection.JavaConversions.asJavaIterable
import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.collection.JavaConversions.seqAsJavaList

import scala.concurrent.duration._

/** Object containing internal types used to represent O-MI request.
  *
  **/
object OmiTypes{


  /**
   * Trait that represents any Omi request. Provides some data that are common
   * for all omi requests.
   */
  sealed trait OmiRequest {
    def ttl: Duration
    def callback: Option[String]
    def hasCallback = callback.isDefined && callback.getOrElse("").nonEmpty
  }
  sealed trait PermissiveRequest
  sealed trait OdfRequest {
    def odf : OdfObjects
  }

  /**
   * Trait for subscription like classes. Offers a common interface for subscription types.
   */
  trait SubLike extends OmiRequest {
    // Note: defs can be implemented also as val and lazy val
    def interval: Duration
    def ttl: Duration
    def isIntervalBased  = interval >= 0.milliseconds
    def isEventBased = interval == -1.seconds
    def ttlToMillis: Long = ttl.toMillis
    def intervalToMillis: Long = interval.toMillis
    def isImmortal = ! ttl.isFinite
    require(interval == -1.seconds || interval >= 0.seconds, s"Invalid interval: $interval")
    require(ttl >= 0.seconds, s"Invalid ttl, should be positive (or +infinite): $interval")
  }

/** Request for getting data for current interval.
  * Used for subscription callbacks.
  **/
  case class SubDataRequest(sub: database.DBSub) extends OmiRequest {
    def ttl = sub.ttl
    def callback = sub.callback
  }

/** One-time-read request
  *
  **/
case class ReadRequest(
  ttl: Duration,
  odf: OdfObjects ,
  begin: Option[ Timestamp ] = None,
  end: Option[ Timestamp ] = None,
  newest: Option[ Int ] = None,
  oldest: Option[ Int ] = None,
  callback: Option[ String ] = None
) extends OmiRequest with OdfRequest

/** Poll request
  *
  **/
case class PollRequest(
  ttl: Duration,
  callback: Option[ String ] = None,
  requestIDs: Iterable[ Int ] = asJavaIterable(Seq.empty[Int])
) extends OmiRequest

/** Subscription request for startting subscription
  *
  **/
case class SubscriptionRequest(
  ttl: Duration,
  interval: Duration,
  odf: OdfObjects ,
  newest: Option[ Int ] = None,
  oldest: Option[ Int ] = None,
  callback: Option[ String ] = None
) extends OmiRequest with SubLike with OdfRequest

/** Write request
  *
  **/
case class WriteRequest(
  ttl: Duration,
  odf: OdfObjects,
  callback: Option[ String ] = None
) extends OmiRequest with OdfRequest with PermissiveRequest


/** Response request, contains result for other requests
  *
  **/
case class ResponseRequest(
  results: Iterable[OmiResult]  
) extends OmiRequest with PermissiveRequest{
      def callback = None
      def ttl = 0.seconds
   } 

/** Cancel request, for cancelling subscription.
  *
  **/
case class CancelRequest(
  ttl: Duration,
  requestID: Iterable[ Int ] = asJavaIterable(Seq.empty[Int])
) extends OmiRequest {
      def callback = None
    }

/** Result of a O-MI request
  *
  **/
case class OmiResult(
  value: String,
  returnCode: String,
  description: Option[String] = None,
  requestID: Iterable[ Int ] = asJavaIterable(Seq.empty[Int]),
  odf: Option[OdfTypes.OdfObjects] = None
) 

  type  OmiParseResult = Either[Iterable[ParseError], Iterable[OmiRequest]]
  def getRequests( omi: OmiParseResult ) : Iterable[OmiRequest] = 
    omi match{
      case Right(requests: Iterable[OmiRequest]) => requests
      case _ => asJavaIterable(Seq.empty[OmiRequest])
    }
  def getErrors( omi: OmiParseResult ) : Iterable[ParseError] = 
    omi match{
      case Left( pes: Iterable[ParseError]) => pes
      case _ => asJavaIterable(Seq.empty[ParseError])
    }
}
