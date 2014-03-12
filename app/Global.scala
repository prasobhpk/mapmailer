import actors.ProcessCPOCsvEntry
import akka.actor.Props
import com.google.common.base.{Splitter, CharMatcher}
import com.google.common.util.concurrent.{FutureCallback, JdkFutureAdapters, Futures}
import models.{PostcodeUnit, Party}
import models.csv.CodePointOpenCsvEntry
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.model.dataformat.BindyType
import org.apache.camel.{FailedToStartRouteException, Exchange, Processor}
import org.joda.time.DateTime
import play.api._
import play.api.mvc.WithFilters
import play.filters.gzip.GzipFilter
import play.libs.Akka
import play.modules.reactivemongo.ReactiveMongoPlugin
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.indexes.IndexType.{Geo2D, Ascending}
import reactivemongo.api.indexes.Index
import reactivemongo.bson.BSONDocument
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.Some
import uk.co.coen.capsulecrm.client._
import play.api.Play.current
import ExecutionContext.Implicits.global
import scala.util.control.Exception._
import play.modules.reactivemongo.json.BSONFormats._
import scala.concurrent.duration._

object Global extends WithFilters(new GzipFilter()) with GlobalSettings {
  val camelContext = new DefaultCamelContext()

  override def onStart(app: Application) {
    val processActor = Akka.system.actorOf(Props[ProcessCPOCsvEntry], name = "processCPOCsvEntry")

    // import postcodes
    camelContext.addRoutes(new RouteBuilder {
      override def configure() {
        from(app.configuration.getString("cpo.from").get).unmarshal.bindy(BindyType.Csv, "models.csv").split(body).process(new Processor {
          override def process(exchange: Exchange) {
            val csvEntryMap = mapAsScalaMap[String, CodePointOpenCsvEntry](exchange.getIn.getBody.asInstanceOf[java.util.Map[String, CodePointOpenCsvEntry]])

            for (entry <- csvEntryMap.values) {
              processActor ! entry
            }
          }
        })
      }
    })

    try {
      camelContext.start()
    } catch {
      case e: FailedToStartRouteException => Logger.warn(e.getMessage)
    }

    val pcuCollection: BSONCollection = ReactiveMongoPlugin.db.collection[BSONCollection]("pcu")
    val partyCollection: BSONCollection = ReactiveMongoPlugin.db.collection[BSONCollection]("parties")

    pcuCollection.indexesManager.ensure(Index(List("pc" -> Ascending)))
    partyCollection.indexesManager.ensure(Index(List("loc" -> Geo2D)))

    app.configuration.getString("capsulecrm.url") match {
      case Some(url) =>
        importParties(app, pcuCollection, partyCollection, CParty.listAll().get())
        Akka.system().scheduler.schedule(5 minutes, 5 minutes) {
          importParties(app, pcuCollection, partyCollection, CParty.listModifiedSince(new DateTime().minusHours(1)).get())
        }
      case _ =>
    }
  }

  def importParties(app: Application, pcuCollection: BSONCollection, partyCollection: BSONCollection, parties: CParties) {
    val groupsToIgnore = app.configuration.getStringList("groups.ignore").get
    val skipImport = app.configuration.getStringList("groups.skipImport").get
    val groupsToCollapseIfContains = app.configuration.getStringList("groups.collapseIfContains").get

    parties.foreach {
      party =>
        if (party.firstEmail() != null && party.firstAddress() != null && party.firstAddress().zip != null) {
          Futures.addCallback(JdkFutureAdapters.listenInPoolThread(party.listTags()), new FutureCallback[CTags] {
            override def onSuccess(ctags: CTags) = {
              val tags = allCatch.opt(ctags.tags.map(_.name).toList).getOrElse(Nil)

              val groups = if (party.isInstanceOf[COrganisation]) {
                tags
              } else {
                val jobTitleGroups = allCatch.opt(Splitter.on(CharMatcher.anyOf(",&")).trimResults().omitEmptyStrings()
                  .split(party.asInstanceOf[CPerson].jobTitle).toList).getOrElse(Nil)

                jobTitleGroups ::: tags
              }
                .diff(groupsToIgnore)
                .map(group => allCatch.opt(groupsToCollapseIfContains.filter(group.toLowerCase.contains(_)).maxBy(_.length)).getOrElse(group))
                .distinct

              if (!groups.exists(skipImport.toSet)) {
                val postcode = CharMatcher.WHITESPACE.removeFrom(party.firstAddress().zip).toUpperCase
                val groupsToSave = dedupe(groups.diff(skipImport), (a:String, b:String) => a.toLowerCase == b.toLowerCase).map(CharMatcher.JAVA_LETTER.retainFrom(_))
                  .filter(_.length > 1)
                  .map(_.capitalize)

                pcuCollection.find(BSONDocument("pc" -> postcode)).one[PostcodeUnit].map {
                  case Some(postcodeUnit) =>
                    partyCollection.find(BSONDocument("cid" -> party.id.toString)).one[Party].map {
                      case Some(existingParty) =>
                        partyCollection.insert(existingParty.copy(
                          party.id.toString,
                          party.getName,
                          party.firstEmail().emailAddress,
                          if (party.firstWebsite(WebService.URL) != null) Some(party.firstWebsite(WebService.URL).webAddress) else None,
                          party.firstAddress().zip.toUpperCase,
                          party.isInstanceOf[COrganisation],
                          postcodeUnit.location,
                          groupsToSave
                        ))

                      case None =>
                        partyCollection.insert(Party(
                          party.id.toString,
                          party.getName,
                          party.firstEmail().emailAddress,
                          if (party.firstWebsite(WebService.URL) != null) Some(party.firstWebsite(WebService.URL).webAddress) else None,
                          party.firstAddress().zip.toUpperCase,
                          party.isInstanceOf[COrganisation],
                          postcodeUnit.location,
                          groupsToSave
                        ))
                    }

                  case None => Logger.info(s"Unable to find location for party ${party.getName} with postcode ${postcode}")
                }
              }
            }

            override def onFailure(failure: Throwable) = Logger.error(failure.getMessage, failure)
          })
        }
    }
  }

  def dedupe[T](elements: List[T], predicate: (T, T) => Boolean = (a: T, b: T) => { a == b }): List[T] = {
    @tailrec def recur(raw: List[T], deduped: List[T]): List[T] =
      raw match {
        case Nil => deduped // no more raw elements to process, just return the final deduped list
        case head :: tail => recur(tail,
          deduped.exists(predicate(_, head)) match {
            case true => deduped  // element is in the list, just use the deduped list as-is
            case false => head :: deduped // not in the list, so add it.
          })
      }

    recur(elements, Nil).reverse
  }

  override def onStop(app: Application) = {
    camelContext.stop()
  }
}