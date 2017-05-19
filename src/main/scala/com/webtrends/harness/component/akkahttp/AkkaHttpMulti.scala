package com.webtrends.harness.component.akkahttp

import akka.http.scaladsl.model.{HttpMethod, HttpMethods}
import akka.http.scaladsl.server.Directives.{entity, provide, path => p, _}
import akka.http.scaladsl.server.{Directive1, PathMatcher}
import com.webtrends.harness.command.{BaseCommand, CommandBean}
import com.webtrends.harness.component.akkahttp.methods.AkkaHttpMethod

/**
  * Use this class to create a command that can handle any number of endpoints with any
  * number of HTTP methods in a single class
  */
trait AkkaHttpMulti extends AkkaHttpBase { this: BaseCommand =>
  // Map of endpoint names as keys to endpoint info
  def allPaths: List[Endpoint]

  // Convenient method for when your path will not have any segments
  def emptyPath(pth: String) = p(pth) & provide(new AkkaHttpPathSegments {})

  // Method that is called for each endpoint object on addition, can override to do special logic
  def endpointExtraProcessing(end: Endpoint): Unit = {}

  // TODO: Add support for inputs of specific types, instead of treating every segment as a string
  // Method that adds all routes from allPaths
  override def createRoutes() = {
    allPaths.foreach { endpoint =>
      var segCount = 0
      // Split the path into segments and map those to their akka http objects
      val segs = endpoint.url.split("/").filter(_.nonEmpty).toSeq
      try {
        // Combine all segments into an akka path
        val dir = segs.tail.foldLeft(segs.head.asInstanceOf[Any]) { (x, y) =>
          y match {
            case s1: String if s1.startsWith("$") =>
              segCount += 1
              (x match {
                case p: String => p / Segment
                case p: PathMatcher[Tuple1[String]] => p / Segment
                case p: PathMatcher[(String, String)] => p / Segment
                case p: PathMatcher[(String, String, String)] => p / Segment
                case p: PathMatcher[(String, String, String, String)] => p / Segment
              }).asInstanceOf[PathMatcher[_]]
            case s1: String =>
              (x match {
                case p: String => p / s1
                case p: PathMatcher[Tuple1[String]] => p / s1
                case p: PathMatcher[(String, String)] => p / s1
                case p: PathMatcher[(String, String, String)] => p / s1
                case p: PathMatcher[(String, String, String, String)] => p / s1
              }).asInstanceOf[PathMatcher[_]]
          }
        }
        // Add holders for query params if applicable
        currentPath = segCount match {
          case 0 => emptyPath(endpoint.url)
          case 1 => p(dir.asInstanceOf[PathMatcher[Tuple1[String]]]).as(Holder1)
          case 2 => p(dir.asInstanceOf[PathMatcher[(String, String)]]).as(Holder2)
          case 3 => p(dir.asInstanceOf[PathMatcher[(String, String, String)]]).as(Holder3)
          case 4 => p(dir.asInstanceOf[PathMatcher[(String, String, String, String)]]).as(Holder4)
          case 5 => p(dir.asInstanceOf[PathMatcher[(String, String, String, String, String)]]).as(Holder5)
        }
        // Can override this method to do something else with the endpoint
        endpointExtraProcessing(endpoint)
        addRoute(commandInnerDirective(new CommandBean, endpoint.url, AkkaHttpMethod.httpMethod(endpoint.method)))
      } catch {
        case ex: Throwable =>
          log.error(s"Error adding path ${endpoint.url}", ex)
          throw ex
      }
    }
  }

  // Is changed to get past having to pass arguments to httpPath
  var currentPath: Directive1[AkkaHttpPathSegments] = p("default") & provide(new AkkaHttpPathSegments {})
  override def httpPath = {
    currentPath
  }

  // Overriding this so that child classes won't have to worry about it
  override def path = ""

  // Used to set entity, won't need to override
  def maxSizeBytes: Long = 1.024e6.toLong
  override def beanDirective(bean: CommandBean, url: String = "", method: HttpMethod = HttpMethods.GET): Directive1[CommandBean] = {
    val entityClass = allPaths.find(e => url == e.url && method == e.method).flatMap(_.unmarshaller)
    if (entityClass.isDefined) {
      val ev: Manifest[AnyRef] = Manifest.classType(entityClass.get)
      val unmarsh = AkkaHttpBase.unmarshaller[AnyRef](ev)
      (withSizeLimit(maxSizeBytes) & entity(as[AnyRef](unmarsh))).flatMap { entity =>
        bean.addValue(CommandBean.KeyEntity, entity)
        super.beanDirective(bean, url, method)
      }
    } else super.beanDirective(bean, url, method)
  }
}

// Class that holds Endpoint info to go in allPaths, url is the endpoint's path and any query params
// can be input with $param, e.g. /endpoint/$param/accounts, method is an HTTP method, e.g. GET,
// unmarshaller is a case class that can hold the extract of the json body input
case class Endpoint(url: String, method: HttpMethod, unmarshaller: Option[Class[_]] = None)
