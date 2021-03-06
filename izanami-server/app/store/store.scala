package store

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.Semigroup
import cats.data.Validated
import cats.effect.Effect
import cats.kernel.Monoid
import domains.events.EventStore
import domains.Key
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import store.Result.Result
import store.cassandra.CassandraJsonDataStore
import store.elastic.ElasticJsonDataStore
import store.leveldb.{DbStores, LevelDBJsonDataStore}
import store.memory.InMemoryJsonDataStore
import store.memorywithdb.{CacheEvent, InMemoryWithDbStore}
import store.mongo.MongoJsonDataStore
import store.redis.RedisJsonDataStore

object Result {

  case class ErrorMessage(message: String, args: String*)

  object ErrorMessage {
    implicit val format = Json.format[ErrorMessage]
  }

  case class AppErrors(errors: Seq[ErrorMessage] = Seq.empty,
                       fieldErrors: Map[String, List[ErrorMessage]] = Map.empty) {
    def ++(s: AppErrors): AppErrors =
      this.copy(errors = errors ++ s.errors, fieldErrors = fieldErrors ++ s.fieldErrors)
    def addFieldError(field: String, errors: List[ErrorMessage]): AppErrors =
      fieldErrors.get(field) match {
        case Some(err) =>
          AppErrors(errors, fieldErrors + (field -> (err ++ errors)))
        case None => AppErrors(errors, fieldErrors + (field -> errors))
      }

    def toJson: JsValue =
      AppErrors.format.writes(this)

    def isEmpty: Boolean = errors.isEmpty && fieldErrors.isEmpty
  }

  object AppErrors {
    import cats.syntax.semigroup._
    import cats.instances.all._

    implicit val format = Json.format[AppErrors]

    def fromJsError(jsError: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
      val fieldErrors = jsError.map {
        case (k, v) =>
          (k.toJsonString, v.map(err => ErrorMessage(err.message, err.args.map(_.toString): _*)).toList)
      }.toMap
      AppErrors(fieldErrors = fieldErrors)
    }

    def error(message: String): AppErrors =
      AppErrors(Seq(ErrorMessage(message)))

    def error(message: String, args: String*): AppErrors =
      AppErrors(Seq(ErrorMessage(message, args: _*)))

    private def optionCombine[A: Semigroup](a: A, opt: Option[A]): A =
      opt.map(a |+| _).getOrElse(a)

    private def mergeMap[K, V: Semigroup](lhs: Map[K, V], rhs: Map[K, V]): Map[K, V] =
      lhs.foldLeft(rhs) {
        case (acc, (k, v)) => acc.updated(k, optionCombine(v, acc.get(k)))
      }

    implicit val monoid: Monoid[AppErrors] = new Monoid[AppErrors] {
      override def empty = AppErrors()
      override def combine(x: AppErrors, y: AppErrors) = {
        val errors      = x.errors ++ y.errors
        val fieldErrors = mergeMap(x.fieldErrors, y.fieldErrors)
        AppErrors(errors, fieldErrors)
      }
    }
  }

  type ValidatedResult[+E] = Validated[AppErrors, E]
  type Result[+E]          = Either[AppErrors, E]
  def ok[E](event: E): Result[E]            = Right(event)
  def error[E](error: AppErrors): Result[E] = Left(error)
  def error[E](messages: String*): Result[E] =
    Left(AppErrors(messages.map(m => ErrorMessage(m))))
  def errors[E](errs: ErrorMessage*): Result[E] = Left(AppErrors(errs))
  def fieldError[E](field: String, errs: ErrorMessage*): Result[E] =
    Left(AppErrors(fieldErrors = Map(field -> errs.toList)))

  implicit class ResultOps[E](r: Result[E]) {
    def collect[E2](p: PartialFunction[E, E2]): Result[E2] =
      r match {
        case Right(elt) if p.isDefinedAt(elt) => Result.ok(p(elt))
        case Right(elt)                       => Result.error("error.result.filtered")
        case Left(e)                          => Result.error(e)
      }
  }
}

trait PagingResult[Data] {
  def results: Seq[Data]
  def page: Int
  def pageSize: Int
  def count: Int
  def nbPages: Double = Math.ceil(count.toFloat / pageSize)
}

case class JsonPagingResult[Data](jsons: PagingResult[JsValue])(implicit reads: Reads[Data])
    extends PagingResult[Data] {
  override def results: Seq[Data] = jsons.results.flatMap(json => reads.reads(json).asOpt)
  override def page: Int          = jsons.page
  override def pageSize: Int      = jsons.pageSize
  override def count: Int         = jsons.count
}

case class DefaultPagingResult[Data](results: Seq[Data], page: Int, pageSize: Int, count: Int)
    extends PagingResult[Data]

trait DataStore[F[_], Key, Data] {
  def create(id: Key, data: Data): F[Result[Data]]
  def update(oldId: Key, id: Key, data: Data): F[Result[Data]]
  def delete(id: Key): F[Result[Data]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: Key): F[Option[Data]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Data]]
  def getByIdLike(patterns: Seq[String]): Source[(Key, Data), NotUsed]
  def count(patterns: Seq[String]): F[Long]
}

trait JsonDataStore[F[_]] extends DataStore[F, Key, JsValue]

object JsonDataStore {
  def apply[F[_]: Effect](
      drivers: Drivers,
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      eventStore: EventStore[F],
      eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem, stores: DbStores[F]): JsonDataStore[F] =
    conf.`type` match {
      case InMemoryWithDb =>
        val dbType: DbType                  = conf.conf.db.map(_.`type`).orElse(izanamiConfig.db.inMemoryWithDb.map(_.db)).get
        val jsonDataStore: JsonDataStore[F] = storeByType(drivers, izanamiConfig, conf, dbType, applicationLifecycle)
        Logger.info(
          s"Loading InMemoryWithDbStore for namespace ${conf.conf.namespace} with underlying store ${dbType.getClass.getSimpleName}"
        )
        InMemoryWithDbStore[F](izanamiConfig.db.inMemoryWithDb.get,
                               conf,
                               jsonDataStore,
                               eventStore,
                               eventAdapter,
                               applicationLifecycle)
      case other =>
        storeByType(drivers, izanamiConfig, conf, other, applicationLifecycle)
    }

  private def storeByType[F[_]: Effect](
      drivers: Drivers,
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      dbType: DbType,
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem, stores: DbStores[F]): JsonDataStore[F] =
    dbType match {
      case InMemory => InMemoryJsonDataStore(conf)
      case Redis    => RedisJsonDataStore(drivers.redisClient.get, conf)
      case LevelDB  => LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, applicationLifecycle)
      case Cassandra =>
        CassandraJsonDataStore(drivers.cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf)
      case Elastic => ElasticJsonDataStore(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf)
      case Mongo   => MongoJsonDataStore(drivers.mongoApi.get, conf)
      case _       => throw new IllegalArgumentException(s"Unsupported store type $dbType")
    }

}
