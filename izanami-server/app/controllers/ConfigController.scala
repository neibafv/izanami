package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.apikey.Apikey
import domains.config.{Config, ConfigInstances, ConfigService}
import domains.{Import, ImportResult, IsAllowed, Key}
import env.Env
import libs.functional.EitherTSyntax
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

import scala.concurrent.Future

class ConfigController[F[_]: Effect](configStore: ConfigService[F],
                                     system: ActorSystem,
                                     AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                     val cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import ConfigInstances._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
      configStore
        .getByIdLike(patternsSeq, page, nbElementPerPage)
        .map { r =>
          Ok(
            Json.obj(
              "results" -> Json.toJson(r.results),
              "metadata" -> Json.obj(
                "page"     -> page,
                "pageSize" -> nbElementPerPage,
                "count"    -> r.count,
                "nbPages"  -> r.nbPages
              )
            )
          )
        }
    }

  def tree(patterns: String): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import ConfigInstances._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns ++ patterns.split(",")
      configStore
        .getByIdLike(patternsSeq)
        .map {
          case (_, config) =>
            config.id.jsPath.write[JsValue].writes(config.value)
        }
        .fold(Json.obj()) { (acc, js) =>
          acc.deepMerge(js.as[JsObject])
        }
        .map(json => Ok(json))
        .runWith(Sink.head)
    }

  def create(): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import ConfigInstances._

    for {
      config <- ctx.request.body.validate[Config] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _ <- IsAllowed[Config].isAllowed(config)(ctx.auth) |> liftBooleanTrue(
            Unauthorized(AppErrors.error("error.forbidden").toJson)
          )
      event <- configStore.create(config.id, config) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(config))

  }

  def get(id: String): Action[Unit] = AuthAction.asyncEitherT(parse.empty) { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      _ <- Key.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      config <- configStore.getById(key) |> liftFOption[Result, Config](NotFound)
    } yield Ok(Json.toJson(config))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import ConfigInstances._
    for {
      config <- ctx.request.body.validate[Config] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _ <- IsAllowed[Config].isAllowed(config)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- configStore.update(Key(id), config.id, config) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(config))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      current <- configStore.getById(key) |> liftFOption[Result, Config](NotFound)
      _ <- IsAllowed[Config].isAllowed(current)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- configStore
                .update(key, current.id, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      config <- configStore.getById(key) |> liftFOption[Result, Config](NotFound)
      _ <- IsAllowed[Config].isAllowed(config)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      deleted <- configStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(config))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncEitherT { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      for {
        deletes <- configStore.deleteAll(allPatterns) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[AnyContent] = AuthAction.asyncF { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    configStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    import ConfigInstances._
    val source = configStore
      .getByIdLike(ctx.authorizedPatterns)
      .map { case (_, data) => Json.toJson(data) }
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "configs.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(configStore.importData)
      .map {
        case r if r.isError => BadRequest(Json.toJson(r))
        case r              => Ok(Json.toJson(r))
      }
      .recover {
        case e: Throwable =>
          Logger.error("Error importing file", e)
          InternalServerError
      }
      .runWith(Sink.head)
  }

}
