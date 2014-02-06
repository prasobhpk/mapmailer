import actors.ProcessCPOCsvEntry
import akka.actor.Props
import com.google.common.base.{Splitter, CharMatcher}
import models.csv.CodePointOpenCsvEntry
import models.{PostcodeUnit, Party}
import models.csv.CodePointOpenCsvEntry
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.model.dataformat.BindyType
import org.apache.camel.{Exchange, Processor}
import play.api._
import play.libs.Akka
import play.modules.reactivemongo.ReactiveMongoPlugin
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Geo2D, Ascending}
import reactivemongo.api.indexes.Index
import reactivemongo.bson.BSONDocument
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.Some
import uk.co.coen.capsulecrm.client._
import play.api.Play.current
import ExecutionContext.Implicits.global

object Global extends GlobalSettings {
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
    camelContext.start()

    def postcodeUnitCollection: BSONCollection = ReactiveMongoPlugin.db.collection[BSONCollection]("pcu")
    postcodeUnitCollection.indexesManager.ensure(Index(List("pc" -> Ascending)))

    def partyCollection: BSONCollection = ReactiveMongoPlugin.db.collection[BSONCollection]("parties")
    partyCollection.indexesManager.ensure(Index(List("loc" -> Geo2D)))

    app.configuration.getString("capsulecrm.url") match {
      case Some(url) =>
        // import parties from capsule
        CParty.listAll().get().foreach {
          party =>
            if (party.firstEmail() != null && party.firstAddress() != null && party.firstAddress().zip != null) {
              val postcode = CharMatcher.WHITESPACE.removeFrom(party.firstAddress().zip)
              val postcodeUnitOption = postcodeUnitCollection.find(BSONDocument("pc" -> postcode.toUpperCase)).one[PostcodeUnit]
              val tags = party.listTags.get().map(_.name).map(_.capitalize).toList.distinct

              val groups = if (party.isInstanceOf[COrganisation]) {
                tags
              } else {
                val person = party.asInstanceOf[CPerson]
                val jobTitleGroups =
                  if (person.jobTitle != null) Splitter.on(',').trimResults().omitEmptyStrings().split(person.jobTitle).map(_.capitalize).toList.distinct else Nil

                jobTitleGroups ::: tags
              }

              postcodeUnitOption.map {
                case Some(postcodeUnit) =>
                  partyCollection.insert(Party(
                    party.id.toString,
                    party.getName,
                    party.firstEmail().emailAddress,
                    postcode,
                    party.isInstanceOf[COrganisation],
                    postcodeUnit.location,
                    groups
                  ))
                case None => Logger.info(s"Unable to find location for party ${party.getName} with postcode ${postcode}")
              }
            }
        }
      case _ =>
    }
  }

  override def onStop(app: Application) = {
    camelContext.stop()
  }
}