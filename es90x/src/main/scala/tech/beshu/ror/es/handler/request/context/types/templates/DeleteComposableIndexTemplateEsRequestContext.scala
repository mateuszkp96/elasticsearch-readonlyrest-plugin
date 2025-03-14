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
package tech.beshu.ror.es.handler.request.context.types.templates

import cats.data.NonEmptyList
import cats.implicits.*
import org.elasticsearch.action.admin.indices.template.delete.{DeleteIndexTemplateRequest, TransportDeleteComposableIndexTemplateAction}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.DeletingIndexTemplates
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.*

class DeleteComposableIndexTemplateEsRequestContext(actionRequest: TransportDeleteComposableIndexTemplateAction.Request,
                                                    esContext: EsContext,
                                                    clusterService: RorClusterService,
                                                    override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[TransportDeleteComposableIndexTemplateAction.Request, DeletingIndexTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: TransportDeleteComposableIndexTemplateAction.Request): DeletingIndexTemplates = {
    NonEmptyList.fromList(request.getNames) match {
      case Some(patterns) => DeletingIndexTemplates(patterns)
      case None => throw RequestSeemsToBeInvalid[DeleteIndexTemplateRequest]("No template name patterns found")
    }
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case DeletingIndexTemplates(namePatterns) =>
        actionRequest.updateNames(namePatterns)
        ModificationResult.Modified
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass.show}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  implicit class DeleteComposableIndexTemplateActionRequestOps(request: TransportDeleteComposableIndexTemplateAction.Request) {

    def getNames: List[TemplateNamePattern] = {
      on(request)
        .call("names")
        .get[Array[String]]
        .asSafeList
        .flatMap(TemplateNamePattern.fromString)
    }

    def updateNames(names: NonEmptyList[TemplateNamePattern]): Unit = {
      on(request).set("names", names.toList.map(_.value.value).toArray)
    }
  }
}
