package com.twilio.guardrail

import _root_.io.swagger.models._
import cats.Id
import cats.data.NonEmptyList
import cats.free.Free
import cats.instances.all._
import cats.syntax.all._
import com.twilio.guardrail.generators.ScalaParameter
import com.twilio.guardrail.protocol.terms.server.{ ServerTerm, ServerTerms }

import scala.collection.JavaConverters._
import scala.meta._

case class Servers(servers: List[Server])
case class Server(pkg: List[String], extraImports: List[Import], src: List[Stat])
case class ServerRoute(path: String, method: HttpMethod, operation: Operation)
case class RenderedRoutes(
    routes: Term,
    methodSigs: List[Decl.Def],
    supportDefinitions: List[Defn],
    handlerDefinitions: List[Stat]
)

object ServerGenerator {
  import NelShim._

  type ServerGenerator[A] = ServerTerm[A]

  def formatClassName(str: String): String   = s"${str.capitalize}Resource"
  def formatHandlerName(str: String): String = s"${str.capitalize}Handler"

  def fromSwagger[F[_]](context: Context, swagger: Swagger, frameworkImports: List[Import])(
      protocolElems: List[StrictProtocolElems]
  )(implicit S: ServerTerms[F]): Free[F, Servers] = {
    import S._

    val paths: List[(String, Path)] =
      Option(swagger.getPaths).map(_.asScala.toList).getOrElse(List.empty)
    val basePath: Option[String] = Option(swagger.getBasePath)

    for {
      routes           <- extractOperations(paths)
      classNamedRoutes <- routes.traverse(route => getClassName(route.operation).map(_ -> route))
      groupedRoutes = classNamedRoutes
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toList
      extraImports <- getExtraImports(context.tracing)
      servers <- groupedRoutes.traverse {
        case (className, routes) =>
          val resourceName = formatClassName(className.lastOption.getOrElse(""))
          val handlerName =
            formatHandlerName(className.lastOption.getOrElse(""))
          for {
            responseDefinitions <- routes.flatTraverse {
              case sr @ ServerRoute(path, method, operation) =>
                for {
                  responseDefinitions <- generateResponseDefinitions(operation, protocolElems)
                } yield responseDefinitions
            }
            renderedRoutes   <- generateRoutes(className, resourceName, basePath, context.tracing, protocolElems)(routes)
            handlerSrc       <- renderHandler(formatHandlerName(className.lastOption.getOrElse("")), renderedRoutes.methodSigs, renderedRoutes.handlerDefinitions)
            extraRouteParams <- getExtraRouteParams(context.tracing)
            classSrc         <- renderClass(resourceName, handlerName, renderedRoutes.routes, extraRouteParams, responseDefinitions, renderedRoutes.supportDefinitions)
          } yield {
            Server(className, frameworkImports ++ extraImports, handlerSrc +: classSrc)
          }
      }
    } yield Servers(servers)
  }
}
