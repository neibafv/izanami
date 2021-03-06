package domains.abtesting.impl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import cats.effect.Effect
import com.datastax.driver.core.{Session, SimpleStatement}
import domains.abtesting._
import domains.events.EventStore
import env.{CassandraConfig, DbDomainConfig}
import play.api.Logger
import play.api.libs.json._
import store.Result.Result
import store.cassandra.Cassandra
import store.Result

import scala.concurrent.Future

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    CASSANDRA     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventCassandreService {
  def apply[F[_]: Effect](
      session: Session,
      config: DbDomainConfig,
      cassandraConfig: CassandraConfig,
      eventStore: EventStore[F]
  )(implicit actorSystem: ActorSystem): ExperimentVariantEventCassandreService[F] =
    new ExperimentVariantEventCassandreService(session, config, cassandraConfig, eventStore)
}

class ExperimentVariantEventCassandreService[F[_]: Effect](session: Session,
                                                           config: DbDomainConfig,
                                                           cassandraConfig: CassandraConfig,
                                                           eventStore: EventStore[F])(implicit actorSystem: ActorSystem)
    extends ExperimentVariantEventService[F] {

  private val namespaceFormatted = config.conf.namespace.replaceAll(":", "_")
  private val keyspace           = cassandraConfig.keyspace
  import Cassandra._
  import cats.implicits._
  import cats.effect.implicits._
  import domains.events.Events._

  implicit private val mat  = ActorMaterializer()
  implicit private val sess = session
  implicit private val es   = eventStore

  import actorSystem.dispatcher

  Logger.info(s"Creating table ${keyspace}.$namespaceFormatted if not exists")

  //Events table
  session
    .execute(
      s"""
         | CREATE TABLE IF NOT EXISTS ${keyspace}.$namespaceFormatted (
         |   experimentId text,
         |   variantId text,
         |   clientId text,
         |   namespace text,
         |   id text,
         |   value text,
         |   PRIMARY KEY ((experimentId, variantId), clientId, namespace, id)
         | )
         | """.stripMargin
    )

  private def saveToCassandra(id: ExperimentVariantEventKey, data: ExperimentVariantEvent) = {
    val query =
      s"INSERT INTO ${keyspace}.$namespaceFormatted (experimentId, variantId, clientId, namespace, id, value) values (?, ?, ?, ?, ?, ?) IF NOT EXISTS "
    Logger.debug(s"Running query $query")
    executeWithSession(
      query,
      id.experimentId.key,
      id.variantId,
      id.clientId,
      id.namespace,
      id.id,
      Json.stringify(ExperimentVariantEventInstances.format.writes(data))
    ).map(_ => Result.ok(data))
  }

  override def create(id: ExperimentVariantEventKey, data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] =
    for {
      result <- saveToCassandra(id, data) // add event
      _      <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
    } yield result

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] =
    experiment.variants.toList
      .traverse { variant =>
        executeWithSession(s" DELETE FROM ${keyspace}.$namespaceFormatted  WHERE experimentId = ? AND variantId = ?",
                           experiment.id.key,
                           variant.id)
          .map { r =>
            Result.ok(r.asInstanceOf[Any])
          }
      }
      .map(r => Result.ok(Done))
      .flatMap { r =>
        r.traverse(e => eventStore.publish(ExperimentVariantEventsDeleted(experiment)))
      }

  def getVariantResult(experiment: Experiment, variant: Variant): Source[VariantResult, NotUsed] = {
    val variantId: String = variant.id
    CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted WHERE experimentId = ? and variantId = ? ",
        experiment.id.key,
        variantId
      )
    ).map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat(ExperimentVariantEventInstances.format.reads(_).asOpt.toList)
      .via(ExperimentVariantEvent.eventAggregation(experiment))
  }

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    Source(experiment.variants.toList)
      .flatMapMerge(4, v => getVariantResult(experiment, v))

  override def listAll(patterns: Seq[String]) =
    CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted "
      )
    ).map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat(ExperimentVariantEventInstances.format.reads(_).asOpt.toList)
      .filter(e => e.id.key.matchPatterns(patterns: _*))

  override def check(): F[Unit] = executeWithSession("SELECT now() FROM system.local").map(_ => ())

}
