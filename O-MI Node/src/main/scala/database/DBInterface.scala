package database
import scala.language.postfixOps

import slick.driver.H2Driver.api._
import slick.jdbc.meta.MTable
import java.sql.Timestamp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection.JavaConversions.iterableAsScalaIterable

import java.io.File

import parsing.Types._
import parsing.Types.OdfTypes._



package object database {

  private var setEventHooks: List[Seq[Path] => Unit] = List()

  /**
   * Set hooks are run when new data is saved to database.
   * @param f Function that takes the updated paths as parameter.
   */
  def attachSetHook(f: Seq[Path] => Unit) =
    setEventHooks = f :: setEventHooks
  def getSetHooks = setEventHooks

  private var histLength = 10
  /**
   * Sets the historylength to desired length
   * default is 10
   * @param newLength new length to be used
   */
  def setHistoryLength(newLength: Int) {
    histLength = newLength
  }
  def historyLength = histLength

  val dbConfigName = "h2-conf"

}
import database._



/**
 * Old way of using single connection
 */
object singleConnection extends DB {
  val db = Database.forConfig(dbConfigName)
  initialize()

  def destroy() = {
    println("[WARN] Destroying db connection, to drop the database: remove db file!")
    db.close()
  }
}



/**
 * Database class for sqlite. Actually uses config parameters through forConfig in singleConnection.
 * To be used during actual runtime.
 */
class SQLiteConnection extends DB {
  //override val db = singleConnection.db
  val db = Database.forConfig(dbConfigName)

  def destroy() = {
     dropDB()
     db.close()

     // Try to remove the db file
     val confUrl = slick.util.GlobalConfig.driverConfig(dbConfigName).getString("url")
     // XXX: trusting string operations
     val dbPath = confUrl.split(":").last

     val fileExt = dbPath.split(".").lastOption.getOrElse("")
     if (fileExt == "sqlite3" || fileExt == "db")
       new File(dbPath).delete()
  }
}



/**
 * Database class to be used during tests instead of production db to prevent
 * problems caused by overlapping test data.
 * Uses h2 named in-memory db
 * @param name name of the test database, optional. Data will be stored in memory
 */
class TestDB(val name:String = "") extends DB
{
  println("Creating TestDB: " + name)
  val db = Database.forURL(s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver",
    keepAliveConnection=true)
  initialize()

  /**
  * Should be called after tests.
  */
  def destroy() = {
    println("Removing TestDB: " + name)
    db.close()
  }
}




/**
 * Database trait used by db classes.
 * Contains a public high level read-write interface for the database tables.
 */
trait DB extends DBReadWrite with DBBase {
  def asReadOnly: DBReadOnly = this
  def asReadWrite: DBReadWrite = this
}






/**
 * Read only restricted interface methods for db tables
 */
trait DBReadOnly extends DBBase with OmiNodeTables {
  protected def findParent(childPath: Path): DBIOAction[DBNode,NoStream,Effect.Read] = (
    if (childPath.length == 0)
      hierarchyNodes filter (_.path === childPath)
    else
      hierarchyNodes filter (_.path === Path(childPath.init))
    ).result.head


  /**
   * Used to get metadata from database for given path
   * @param path path to sensor whose metadata is requested
   * 
   * @return metadata as Option[String], none if no data is found
   */
  def getMetaData(path: Path): Option[String] = runSync(getMetaDataI(path) map (_ map (_.metadata))) // TODO: clean codestyle

  protected def getMetaDataI(path: Path): DBIOAction[Option[DBMetaData], NoStream, Effect.Read] = {
    getWithHieracrhyQ[DBMetaData, DBMetaDatasTable](path, metadatas).result.map(_.headOption)
  }



  /**
   * Returns array of DBSensors for given subscription id.
   * Array consists of all sensor values after beginning of the subscription
   * for all the sensors in the subscription
   * returns empty array if no data or subscription is found
   *
   * @param id subscription id that is assigned during saving the subscription
   * @param testTime optional timestamp value to indicate end time of subscription,
   * should only be needed during testing. Other than testing None should be used
   *
   * @return Array of DBSensors
   */
  def getSubData(id: Int, testTime: Option[Timestamp]): OdfObjects = ???
  //OLD: def getSubData(id: Int, testTime: Option[Timestamp]): Array[DBSensor] = ???
    /*{
      var result = Buffer[DBSensor]()
      var subQuery = subs.filter(_.ID === id)
      var info: (Timestamp, Double) = (null, 0.0) //to gather only needed info from the query
      var paths = Array[String]()

      var str = runSync(subQuery.result)
      if (str.length > 0) {
        var sub = str.head
        info = (sub._3, sub._5)
        paths = sub._2.split(";")
      }
      paths.foreach {
        p =>
          result ++= DataFormater.FormatSubData(Path(p), info._1, info._2, testTime)
      }
      result.toArray
    }*/

  //ODL: def getSubData(id: Int): Array[DBSensor] = getSubData(id, None)
  def getSubData(id: Int): OdfObjects = getSubData(id, None)

  /**
   * Used to get data from database based on given path.
   * returns Some(DBSensor) if path leads to sensor and if
   * path leads to object returns Some(DBObject). DBObject has
   * variable childs of type Array[DBItem] which stores object's childs.
   * object.childs(0).path to get first child's path
   * if nothing is found for given path returns None
   *
   * @param path path to search data from
   *
   * @return either Some(DBSensor),Some(DBObject) or None based on where the path leads to
   */
  def get(path: Path): Option[ Either[ DBNode,DBValue ] ] = {
    val valueResult = runSync(
      getValuesQ(path).sortBy( _.timestamp.desc ).result
    )

    valueResult.headOption match {
      case Some(value) =>
        Some( Right(value) )
      case None =>
        val node = runSync( getHierarchyNodeI(path) )
        node.headOption map {value =>
          Left(node.get)
        }
    }
  }

  def getQ(single: OdfElement): OdfElement = ???

  // Used for compiler type trickery by causing type errors
  trait Hole // TODO: RemoveMe!

  //Helper for getting values with path
  protected def getValuesQ(path: Path) = getWithHieracrhyQ[DBValue, DBValuesTable](path, latestValues)

  protected def getWithHieracrhyQ[I, T <: HierarchyFKey[I]](path: Path, table: TableQuery[T]): Query[T,I,Seq] =
    for{
      (hie, value) <- hierarchyNodes.filter(_.path === path) join table on (_.id === _.hierarchyId )
    } yield(value)

  protected def getHierarchyNodeI(path: Path): DBIOAction[Option[DBNode], NoStream, Effect.Read] = 
    hierarchyNodes.filter(_.path === path).result.map(_.headOption)

  protected def getHierarchyNodeI(id: Int): DBIOAction[Option[DBNode], NoStream, Effect.Read] =
    hierarchyNodes.filter(_.id === id).result.map(_.headOption)


  /**
   * Used to get sensor values with given constrains. first the two optional timestamps, if both are given
   * search is targeted between these two times. If only start is given,all values from start time onwards are
   * targeted. Similiarly if only end is given, values before end time are targeted.
   *    Then the two Int values. Only one of these can be present. fromStart is used to select fromStart number
   * of values from the begining of the targeted area. Similiarly from ends selects fromEnd number of values from
   * the end.
   * All parameters except path are optional, given only path returns all values in the database for that path
   *
   * @param path path as Path object
   * @param start optional start Timestamp
   * @param end optional end Timestamp
   * @param fromStart number of values to be returned from start
   * @param fromEnd number of values to be returned from end
   * @return query for the requested values
   */
  protected def getNBetweenInfoItemQ(
    path: Path,
    begin: Option[Timestamp],
    end: Option[Timestamp],
    newest: Option[Int],
    oldest: Option[Int]
  ): Query[DBValuesTable,DBValue,Seq] = {
    val timeFrame = ( end, begin ) match {
      case (None, Some(startTime)) => 
        getValuesQ(path).filter{ value =>
          value.timestamp >= startTime
        }
      case (Some(endTime), None) => 
        getValuesQ(path).filter{ value =>
          value.timestamp <= endTime
        }
      case (Some(endTime), Some(startTime)) => 
        getValuesQ(path).filter{ value =>
          value.timestamp >= startTime &&
          value.timestamp <= endTime
        }
      case (None, None) =>
        getValuesQ(path)
    }
    val query = 
      if( newest.nonEmpty ) {
        timeFrame.sortBy( _.timestamp.desc ).take(newest.get)
      } else if ( oldest.nonEmpty ) {
        timeFrame.sortBy( _.timestamp.asc ).take( oldest.get ) // XXX: Will have unconsistent ordering
      } else {
        timeFrame.sortBy( _.timestamp.desc )
      }
    query
  }

  /**
   * Used to get sensor values with given constrains. first the two optional timestamps, if both are given
   * search is targeted between these two times. If only start is given,all values from start time onwards are
   * targeted. Similiarly if only end is given, values before end time are targeted.
   *    Then the two Int values. Only one of these can be present. fromStart is used to select fromStart number
   * of values from the begining of the targeted area. Similiarly from ends selects fromEnd number of values from
   * the end.
   * All parameters except the first are optional, given only the first returns all requested data
   *
   * @param requests SINGLE requests in a list (leafs in request O-DF); InfoItems, Objects and MetaDatas
   * @param start optional start Timestamp
   * @param end optional end Timestamp
   * @param fromStart number of values to be returned from start
   * @param fromEnd number of values to be returned from end
   * @return Combined results in a O-DF tree
   */
  def getNBetween(
    requests: Iterable[OdfElement],
    begin: Option[Timestamp],
    end: Option[Timestamp],
    newest: Option[Int],
    oldest: Option[Int]
  ): OdfObjects = {
    require( ! (newest.isDefined && oldest.isDefined),
      "Both newest and oldest at the same time not supported!")
    //val futureResults: Iterable[Future[Seq[OdfElement]]] = requests map {
    val futureResults = requests map {

      case obj @ OdfObject(path,items,objects,_,_) =>
        require(items.isEmpty && objects.isEmpty,
          "getNBetween requires leaf OdfElements from the request")

        db.run(
          getNBetweenSubTreeQ(path, begin, end, newest, oldest).result
        )
        ???

      case OdfInfoItem(path, values, _, _) =>
        val futureSeq = db.run(
          getNBetweenInfoItemQ(path, begin, end, newest, oldest).result
        )
        futureSeq map (_ map (_.toOdfValue))

        val futureOption = db.run(
          getMetaDataI(path)
        )
        futureOption map (_.toSeq)
        ???

      case odf: OdfElement =>
        assert(false, s"Non-supported query parameter: $odf")
        ???
        //case OdfObjects(_, _) => 
        //case OdfDesctription(_, _) => 
        //case OdfValue(_, _, _) => 
    }

    ???
  }

  def getSubTreeQ(path: Path): Query[Hole,Hole,Seq] = ???

  protected def getNBetweenSubTreeQ(
    path: Path,
    begin: Option[Timestamp],
    end: Option[Timestamp],
    newest: Option[Int],
    oldest: Option[Int]
  ): Query[Hole,DBValue,Seq] = {
    ???
  }
    
  /**
   * Used to get childs of an object with given path
   * @param path path to object whose childs are needed
   * @return Array[DBItem] of DBObjects containing childs
   *  of given object. Empty if no childs found or invalid path.
   */
  def getChilds(path: Path): Array[DBItem] = ???
    /*{
      var childs = Array[DBItem]()
      val objectQuery = for {
        c <- objects if c.parentPath === path
      } yield (c.path)
      var str = runSync(objectQuery.result)
      childs = Array.ofDim[DBItem](str.length)
      var index = 0
      str foreach {
        case (cpath: Path) =>
          childs(Math.min(index, childs.length - 1)) = DBObject(cpath)
          index += 1
      }
      childs
    }
    */





  /**
   * Checks whether given path exists on the database
   * @param path path to be checked
   * @return boolean whether path was found or not
   */
  protected def hasObject(path: Path): Boolean =
    runSync(hierarchyNodes.filter(_.path === path).exists.result)




  /**
   * getAllSubs is used to search the database for subscription information
   * Can also filter subscriptions based on whether it has a callback address
   * @param hasCallBack optional boolean value to filter results based on having callback address
   * 
   * None -> all subscriptions
   * Some(True) -> only with callback
   * Some(False) -> only without callback
   *
   * @return DBSub objects for the query as Array
   */
  def getAllSubs(hasCallBack: Option[Boolean]): Array[DBSub] = ??? 
  /*
    {
      val all = runSync(hasCallBack match {
        case Some(true) =>
          subs.filter(!_.callback.isEmpty).result
        case Some(false) =>
          subs.filter(_.callback.isEmpty).result
        case None =>
          subs.result
      })
      all map { elem =>
          val paths = elem._2.split(";").map(Path(_)).toVector
          DBSub(elem._1, paths, elem._4, elem._5, elem._6, Some(elem._3))
      }
    }
    */



  /**
   * Returns DBSub object wrapped in Option for given id.
   * Returns None if no subscription data matches the id
   * @param id number that was generated during saving
   *
   * @return returns Some(BDSub) if found element with given id None otherwise
   */
  def getSub(id: Int): Option[DBSub] = runSync(getSubQ(id))

  protected def getSubQ(id: Int): DBIOAction[Option[DBSub],NoStream,Effect.Read] =
    subs.filter(_.id === id).result.map(_.headOption)
}




/**
 * Read-write interface methods for db tables.
 */
trait DBReadWrite extends DBReadOnly with OmiNodeTables {
  type ReadWrite = Effect with Effect.Write with Effect.Read

  /**
  * Initializing method, creates the file and tables.
  * This method blocks everything else in this object.
  *
  * Tries to guess if tables are not yet created by checking existing tables
  * This gives false-positive only when there is other tables present. In that case
  * manually clean the database.
  */
  def initialize() = this.synchronized {

    val setup = allSchemas.create    

    val existingTables = MTable.getTables

    runSync(existingTables).headOption match {
      case Some(table) =>
        //noop
        println("Not creating tables, found table: " + table.name.name)
      case None =>
        // run transactionally so there are all or no tables
        runSync(setup.transactionally)
    }
  }



  /**
  * Metohod to completely remove database. Tries to remove the actual database file.
  */
  def destroy(): Unit


  /**
   * Adds missing objects(if any) to hierarchy based on given path
   * @param path path whose hierarchy is to be stored to database
   */
  protected def addObjects(path: Path) {

    /** Query: Increase right and left values after value */
    def increaseAfterQ(value: Int) = {

      // NOTE: Slick 3.0.0 doesn't allow this query with its types, use sql instead
      //val rightValsQ = hierarchyNodes map (_.rightBoundary) filter (_ > value) 
      //val leftValsQ  = hierarchyNodes map (_.leftBoundary) filter (_ > value)
      //val rightUpdateQ = rightValsQ.map(_ + 2).update(rightValsQ)
      //val leftUpdateQ  =  leftValsQ.map(_ + 2).update(leftValsQ)

      DBIO.seq(
        sqlu"UPDATE HierarchyNodes SET rightBoundary = rightBoundary + 2 WHERE rightBoundary > ${value}",
        sqlu"UPDATE HierarchyNodes SET leftBoundary = leftBoundary + 2 WHERE leftBoundary > ${value}"
      )
    }


    def addNode(fullpath: Path): DBIOAction[Unit, NoStream, ReadWrite] =
        for {
          parent <- findParent(fullpath)

          insertRight = parent.rightBoundary
          left        = insertRight + 1
          right       = left + 1

          _ <- increaseAfterQ(insertRight)

          _ <- hierarchyNodes += DBNode(None, fullpath, left, right, fullpath.length, "", 0)

        } yield ()

    val parentsAndPath = path.getParentsAndSelf

    val foundPathsQ   = hierarchyNodes filter (_.path inSet parentsAndPath) map (_.path) result
    // difference between all and found
    val missingPathsQ: DBIOAction[Seq[Path],NoStream,Effect.Read]  = foundPathsQ map (parentsAndPath diff _)

    // Combine DBIOActions as a single action
    val addingAction = missingPathsQ flatMap {(missingPaths: Seq[Path]) =>
      DBIO.seq(
        missingPaths map addNode : _*
      )
    }

    // NOTE: transaction level probably could be reduced to increaseAfter + DBNode insert
    addingAction.transactionally
  }


  /**
   * Used to set values to database. If data already exists for the path, appends until historyLength
   * is met, otherwise creates new data and all the missing objects to the hierarchy.
   *  Does not remove excess rows if path is set ot buffer
   *
   *  @param data sensordata, of type DBSensor to be stored to database.
   *  @return boolean whether added data was new
   */
  def set(path: Path, timestam: Timestamp, value: String, valueType: String = ""): Boolean = ???
  /*{
      //search database for sensor's path
      val pathQuery = latestValues.filter(_.path === data.path)
      val buffering = runSync(buffered.filter(_.path === data.path).result).length > 0

      //appends a row to the latestvalues table
      val count = runSync(pathQuery.result).length
      runSync(DBIO.seq(latestValues += (data.path, data.value, data.time)))
      // Call hooks
      val argument = Seq(data.path)
      getSetHooks foreach { _(argument) }

      if (count > historyLength && !buffering) {
        //if table has more than historyLength and not buffering, remove excess data
        removeExcess(data.path)
        false
      } else if (count == 0) {
        //add missing objects for the hierarchy since this is a new path
        addObjects(data.path)
        true
      } else {
        //existing path and less than history length of data or buffering.
        false
      }
  }*/


  /**
   * Used to store metadata for a sensor to database
   * @param path path to sensor
   * @param data metadata to be stored as string e.g a XML block as string
   * 
   */
  def setMetaData(path:Path,data:String): Unit = ???
  def setMetaData(hierarchyId:Int,data:String): Unit = ???
  /*{
    val qry = meta.filter(_.path === path).map(_.data)
    val count = runSync(qry.result).length
    if(count == 0)
    {
      runSync(meta += (path,data))
    }
    else
    {
      runSync(qry.update(data))
    }
  }
  */


  def RemoveMetaData(path:Path): Unit= ???
  /*{
    val qry = meta.filter(_.path === path)
    runSync(qry.delete)
  }*/


  /**
   * Used to set many values efficiently to the database.
   * @param data list of tuples consisting of path and TimedValue.
   */
  def setMany(data: List[(Path, OdfValue)]): Boolean = ??? /*{
    var add = Seq[(Path,String,Timestamp)]()  // accumulator: dbobjects to add

    // Reformat data and add missing timestamps
    data.foreach {
      case (path: Path, v: OdfValue) =>

         // Call hooks
        val argument = Seq(path)
        getSetHooks foreach { _(argument) }

        lazy val newTimestamp = new Timestamp(new java.util.Date().getTime)
        add = add :+ (path, v.value, v.timestamp.getOrElse(newTimestamp))
    }

    // Add to latest values in a transaction
    runSync((latestValues ++= add).transactionally)

    // Add missing hierarchy and remove excess buffering
    var onlyPaths = data.map(_._1).distinct
    onlyPaths foreach{p =>
        val path = Path(p)

        var pathQuery = objects.filter(_.path === path)
        val len = runSync(pathQuery.result).length
        if (len == 0) {
          addObjects(path)
        }

        var buffering = runSync(buffered.filter(_.path === path).result).length > 0
        if (!buffering) {
          removeExcess(path)
        }
    }
  }*/


  /**
   * Remove is used to remove sensor given its path. Removes all unused objects from the hierarchcy along the path too.
   *
   *
   * @param path path to to-be-deleted sensor. If path doesn't end in sensor, does nothing.
   * @return boolean whether something was removed
   */
  def remove(path: Path): Boolean = ??? /*{
    //search database for given path
    val pathQuery = latestValues.filter(_.path === path)
    var deleted = false
    //if found rows with given path remove else path doesn't exist and can't be removed
    if (runSync(pathQuery.result).length > 0) {
      runSync(pathQuery.delete)
      deleted = true;
    }
    if (deleted) {
      //also delete objects from hierarchy that are not used anymore.
      // start from sensors path and proceed upward in hierarchy until object that is shared by other sensor is found,
      //ultimately the root. path/to/sensor/temp -> path/to/sensor -> ..... -> "" (root)
      var testPath = path
      while (!testPath.isEmpty) {
        if (getChilds(testPath).length == 0) {
          //only leaf nodes have 0 childs. 
          var pathQueryObjects = objects.filter(_.path === testPath)
          runSync(pathQueryObjects.delete)
          testPath = testPath.dropRight(1)
        } else {
          //if object still has childs after we deleted one it is shared by other sensor, stop removing objects
          //exit while loop
          testPath = Path("")
        }
      }
    }
    return deleted
  }*/


  /**
   * Used to clear excess data from database for given path
   * for example after stopping buffering we want to revert to using
   * historyLength
   * @param path path to sensor as Path object
   *
   */
  private def removeExcess(path: Path) = ??? /*{
      var pathQuery = latestValues.filter(_.path === path)

      pathQuery.sortBy(_.timestamp).result flatMap { qry =>
        var count = qry.length

        if (count > historyLength) {
          val oldtime = qry.drop(count - historyLength).head._3
          pathQuery.filter(_.timestamp < oldtime).delete
        } else
          DBIO.successful(())
      }
    }*/


  /**
   * put the path to buffering table if it is not there yet, otherwise
   * increases the count on that item, to prevent removing buffered data
   * if one subscription ends and other is still buffering.
   *
   * @param path path as Path object
   * 
   */
  protected def startBuffering(path: Path): Unit = ??? /*{
    val pathQuery = buffered.filter(_.path === path)

    pathQuery.result flatMap { 
      case Seq() =>
        buffered += ((path, 1))
      case Seq(existingEntry) =>
        pathQuery.map(_.count) update (existingEntry.count + 1)
    }
  }*/


  /**
   * removes the path from buffering table or dimishes the count by one
   * also clear all buffered data if count is only 1
   * leaves only historyLength amount of data if count is only 1
   * 
   * @param path path as Path object
   */
  protected def stopBuffering(path: Path): Boolean = ??? /*{
    val pathQuery = buffered.filter(_.path === path)

    pathQuery.result flatMap { existingEntry =>
      if (existingEntry.count > 1)
        pathQuery.map(_.count) update (existingEntry.count - 1)
      else
        pathQuery.delete
    }
      val pathQuery = buffered.filter(_.path === path)
      val str = runSync(pathQuery.result)
      var len = str.length
      if (len > 0) {
        if (str.head.count > 1) {
          runSync(pathQuery.map(_.count).update(len - 1))
          false
        } else {
          runSync(pathQuery.delete)
          removeExcess(path)
          true
        }
      } else {
        false
      }
  }*/





    


  /**
   * Check whether subscription with given ID has expired. i.e if subscription has been in database for
   * longer than its ttl value in seconds.
   *
   * @param id number that was generated during saving
   *
   * @return returns boolean whether subscription with given id has expired
   */
  // TODO: Is this needed at all?
  // def isExpired(id: Int): Boolean = ???
  /*
    {
      //gets time when subscibe was added,
      // adds ttl amount of seconds to it,
      //and compares to current time
        val sub = runSync(subs.filter(_.ID === id).result).headOption
        if(sub != None)
        {
        if (sub.get._4 > 0) {
          val endtime = new Timestamp(sub.get._3.getTime + (sub.get._4 * 1000).toLong)
          new java.sql.Timestamp(new java.util.Date().getTime).after(endtime)
        } else {
          true
        }
        }
        else
        {
          true
        }
    }*/



  /**
   * Removes subscription information from database for given ID
   * @param id id number that was generated during saving
   *
   */
  def removeSub(id: Int): Boolean = ??? /*{
    
      var qry = subs.filter(_.ID === id)
      var toBeDeleted = runSync(qry.result)
      if (toBeDeleted.length > 0) {
        if (toBeDeleted.head._6 == None) {
          toBeDeleted.head._2.split(";").foreach { p =>
            stopBuffering(Path(p))
          }
        }
        db.run(qry.delete)
        return true
      } else {
        return false
      }
    
    false
  }*/
  def removeSub(sub: DBSub): Boolean = removeSub(sub.id.get)




  /**
   * Method to modify start time and ttl values of a subscription based on id
   * 
   * @param id id number of the subscription to be modified
   * @param newTime time value to be set as start time
   * @param newTTL new TTL value to be set
   */
  def setSubStartTime(id:Int,newTime:Timestamp,newTTL:Double) = ??? 
  /*{
    runWait(subs.filter(_.ID === id).map(p => (p.start,p.TTL)).update((newTime,newTTL)))
  }*/


  /**
   * Saves subscription information to database
   * adds timestamp at current time to keep track of expiring
   * adds unique id number to differentiate between elements and
   * to provide easy query parameter
   *
   * @param sub DBSub object to be stored
   *
   * @return id number that is used for querying the elements
   */
  def saveSub(sub: DBSub): Int = ??? 
  /*{
        val id = getNextId()
        if (sub.callback.isEmpty) {
          sub.paths.foreach {
            runSync(startBuffering(_))
          }
        }
        val insertSub =
          subs += (id, sub.paths.mkString(";"), sub.startTime, sub.ttl, sub.interval, sub.callback)
        runSync(DBIO.seq(
          insertSub
        ))

        //returns the id for reference
        id
    }
    */


}

