/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.utils

import org.elasticsearch.common.path.PathTrie
import org.elasticsearch.core.RestApiVersion
import org.elasticsearch.rest.{RestController, RestHandler, RestRequest}
import org.joor.Reflect.on
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.ScalaOps.*

import java.util.function.UnaryOperator
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

class RestControllerOps(val restController: RestController) {

  def decorateRestHandlersWith(restHandlerDecorator: RestHandler => RestHandler): Unit = doPrivileged {
    val wrapper = on(restController).get[UnaryOperator[RestHandler]]("handlerWrapper")
    on(restController).set("handlerWrapper", new NewUnaryOperatorRestHandler(wrapper, restHandlerDecorator))

    val handlers = on(restController).get[PathTrie[Any]]("handlers")
    val updatedHandlers = new PathTreeOps(handlers).update(restHandlerDecorator)
    on(restController).set("handlers", updatedHandlers)
  }

  private final class PathTreeOps(pathTrie: PathTrie[Any]) {

    def update(restHandlerDecorator: RestHandler => RestHandler): PathTrie[Any] = {
      val root = on(pathTrie).get[pathTrie.TrieNode]("root")
      update(root, restHandlerDecorator)
      pathTrie
    }

    private def update(trieNode: pathTrie.TrieNode,
                       restHandlerDecorator: RestHandler => RestHandler): Unit = {
      Option(on(trieNode).get[Any]("value")).foreach { value =>
        MethodHandlersWrapper.updateWithWrapper(value, restHandlerDecorator)
      }
      Option(on(pathTrie).get[Any]("rootValue")).foreach { value =>
        MethodHandlersWrapper.updateWithWrapper(value, restHandlerDecorator)
      }
      val children = on(trieNode).get[java.util.Map[String, pathTrie.TrieNode]]("children").asSafeMap
      children.values.foreach { trieNode =>
        update(trieNode, restHandlerDecorator)
      }
    }
  }

  private object MethodHandlersWrapper {
    def updateWithWrapper(value: Any, restHandlerDecorator: RestHandler => RestHandler): Any = {
      val methodHandlers = on(value).get[java.util.Map[RestRequest.Method, java.util.Map[RestApiVersion, RestHandler]]]("methodHandlers").asScala.toMap
      val newMethodHandlers = methodHandlers
        .map { case (key, handlersMap) =>
          val newHandlersMap = handlersMap
            .asSafeMap
            .map { case (apiVersion, handler) =>
              (apiVersion, restHandlerDecorator(removeSecurityHandler(handler)))
            }
            .asJava
          (key, newHandlersMap)
        }
        .asJava
      on(value).set("methodHandlers", newMethodHandlers)
      value
    }
  }

  private class NewUnaryOperatorRestHandler(wrapper: UnaryOperator[RestHandler],
                                            restHandlerDecorator: RestHandler => RestHandler)
    extends UnaryOperator[RestHandler] {

    override def apply(restHandler: RestHandler): RestHandler = doPrivileged {
      restHandlerDecorator(removeSecurityHandler(wrapper.apply(restHandler)))
    }
  }

  private def removeSecurityHandler(handler: RestHandler) = {
    val toProcessing = handler match {
      case h: ChannelInterceptingRestHandlerDecorator => h.underlying
      case h => h
    }
    Try(on(toProcessing).get[RestHandler]("restHandler")) match {
      case Success(underlyingHandler) =>
        underlyingHandler
      case Failure(_) =>
        toProcessing
    }
  }
}

object RestControllerOps {

  implicit def toOps(restController: RestController): RestControllerOps = new RestControllerOps(restController)
}
