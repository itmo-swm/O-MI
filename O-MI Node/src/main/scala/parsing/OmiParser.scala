package parsing

import sensorDataStructure._
import scala.xml._

object OmiParser extends Parser{
  private val implementedRequest = Seq("read","write","cancel", "response")
  private def parseODF(msg:Node) = {
    OdfParser.parse( new PrettyPrinter( 80, 2 ).format( msg ) )
  }
  def parse(xml_msg: String): Seq[ ParseMsg ] ={
    val root = XML.loadString( xml_msg )
    if( root.label != "Envelope" )
      Some( new ParseError( "XML's root isn't omi:Envelope" ) )

    if( root.headOption.isEmpty )
      Some( new ParseError( "omi:Envelope doesn't contain request" ) )

    val requests = root.child.filter( n => implementedRequest.contains( n.label ) )
    val ttl = ( root \ "@ttl" ).headOption.getOrElse{
      return Seq( new ParseError("No ttl present in O-MI Envelope" ) )  
    }.text
    parseNode(requests.head, ttl)

  }

  private def parseNode(node: Node, ttl: String ): Seq[ ParseMsg ]  = {
    //TODO: test for correct node.prefix ( node.prefix = "omi")
  node.label match {
      /*
        Write request 
      */
      case "write"  => {
        val msgformat = (node \ "@msgformat").headOption.getOrElse(
          return Seq( new ParseError( "No msgformat in write request" ) )  
        ).text
        msgformat match{ 
          case "odf" => {
            val msg = ( node \ "msg" ).headOption.getOrElse{
              return Seq( new ParseError( "No message node found in read node." ) ) 
            }
            val odf = parseODF( ( msg \ "Objects" ).headOption.getOrElse( 
              return Seq( new ParseError( "No Objects node found in msg node." ) ) 
            ) )
            val left = odf.filter(_.isLeft)
            val right = odf.filter(_.isRight)

            if ( left.isEmpty && !right.isEmpty ) {
              Seq( Write(ttl, right.map( _.right.get ) ) ) 
            } else if ( !left.isEmpty ) {
              left.map( _.left.get )  
            } else { Seq( ParseError( "No odf or errors found ln 46" ) ) }
          }
          case _ => Seq( new ParseError( "Unknown message format." ) ) 
        }        
      } 

      /*
        Read request 
      */
      case "read"  => {
        val msgformat = (node \ "@msgformat").headOption.getOrElse(
          return Seq( new ParseError( "No msgformat in read request" ) )  
        ).text

        msgformat match { 
          case "odf" => {
            val msg = ( node \ "msg" ).headOption.getOrElse{
              return Seq( new ParseError( "No message node found in read node." ) ) 
            }
            val interval = ( node \ "@interval" ).headOption
            val odf = parseODF( ( msg \ "Objects" ).headOption.getOrElse( 
              return Seq( new ParseError( "No Objects node found in msg node." ) ) 
            ) )
            val left = odf.filter(_.isLeft)
            val right = odf.filter(_.isRight)

            if ( left.isEmpty && !right.isEmpty ) {
              if ( interval.isEmpty ){
                Seq( OneTimeRead(ttl, right.map( _.right.get ) ) ) 
              } else {
                Seq( Subscription( ttl, interval.get.text, right.map( _.right.get ) ) ) 
              }
            } else if ( !left.isEmpty ) {
              left.map( _.left.get )  
            } else { Seq( ParseError( "No odf or errors found ln 78" ) ) }
          }

          case _ => Seq( new ParseError( "Unknown message format." ) ) 
        }
      }
      
      /*
        Cancel request 
      */
      case "cancel"  => Seq( new ParseError( "Unimplemented O-MI node." ) )  
      
      /*
        Response 
      */
      case "response"  => {
        parseNode( ( node \ "result" ).headOption.getOrElse( 
          return Seq( new ParseError( "No result node in response node" ) ) 
        ), ttl ) 
      }
    
      /*
        Response's Result 
      */
      case "result" => {
        val msgformat = (node \ "@msgformat").headOption.getOrElse(
          return Seq( new ParseError( "No return node in result node" ) )  
        ).text
        val returnValue = ( node \ "return" ).headOption.getOrElse(
          return Seq( new ParseError( "No return node in result node" ) )  
        ).text
        val msgOp = ( node \ "msg" ).headOption
        if(msgOp.isEmpty)
          return  Seq( Result(returnValue, None) ) 
        else{
          msgformat match{
            case "odf" => {
              val odf = parseODF( ( msgOp.get \ "Objects" ).headOption.getOrElse( 
                return Seq( new ParseError( "No Objects node found in msg node." ) ) 
              ) )
              val left = odf.filter(_.isLeft)
              val right = odf.filter(_.isRight)

              if ( left.isEmpty && !right.isEmpty ) {
                return Seq( Result( returnValue, Some( right.map( _.right.get ) ) ) ) 
              } else if ( !left.isEmpty ) {
                left.map( _.left.get )  
              } else { Seq( ParseError( "No odf or errors found ln 123" ) ) }

            }
            case _ => return Seq( new ParseError( "Unknown smgformat in result" ) ) 
          }
        }
      }
     
      /*
        Unknown node 
      */
      case _  => Seq( new ParseError( "Unknown node." ) )  
    }
  }

  private def errorsAndOdf( odf:Seq[ OdfParser.ParseResult ]) = odf.groupBy( _.isLeft )

}

abstract sealed trait ParseMsg
case class ParseError( msg:String ) extends ParseMsg
case class OneTimeRead( ttl:String, sensors: Seq[OdfParser.ODFNode]) extends ParseMsg
case class Write( ttl:String, sensors: Seq[OdfParser.ODFNode]) extends ParseMsg
case class Subscription( ttl:String, interval: String, sensors: Seq[OdfParser.ODFNode]) extends ParseMsg
case class Result(value: String ,parseMsgOp:Option[Seq[OdfParser.ODFNode]]) extends ParseMsg
