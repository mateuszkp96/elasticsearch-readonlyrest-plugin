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
package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import java.util.{List => JList}
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest, GetIndexResponse}
import org.elasticsearch.cluster.metadata.AliasMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.types.utils.FilterableAliasesMap._
import tech.beshu.ror.utils.ScalaOps._

import scala.language.postfixOps

class GetIndexEsRequestContext(actionRequest: GetIndexRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[GetIndexRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: GetIndexRequest): Set[ClusterIndexName] = {
    request
      .indices().asSafeList
      .flatMap(ClusterIndexName.fromString)
      .toSet
  }

  override protected def update(request: GetIndexRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    request.indices(filteredIndices.map(_.stringify).toList: _*)
    ModificationResult.UpdateResponse(filterAliases(_, allAllowedIndices))
  }

  private def filterAliases(response: ActionResponse,
                            allAllowedAliases: NonEmptyList[ClusterIndexName]): Task[ActionResponse] = {
    response match {
      case getIndexResponse: GetIndexResponse =>
        val reflectionBasedGetIndexResponse = new ReflectionBasedGetIndexResponse(getIndexResponse)
        reflectionBasedGetIndexResponse.setAliases(
          getIndexResponse.aliases().filterOutNotAllowedAliases(allowedAliases = allAllowedAliases)
        )
        Task.now(reflectionBasedGetIndexResponse.underlying)
      case other =>
        logger.error(s"${id.show} Unexpected response type - expected: [${classOf[GetIndexResponse].getSimpleName}], was: [${other.getClass.getSimpleName}]")
        Task.now(ReflectionBasedGetIndexResponse.createEmpty())
    }
  }
}

private class ReflectionBasedGetIndexResponse(val underlying: GetIndexResponse) {

  import org.joor.Reflect.on

  def getAliases: ImmutableOpenMap[String, JList[AliasMetaData]] = underlying.getAliases

  def setAliases(aliases: ImmutableOpenMap[String, JList[AliasMetaData]]): Unit = {
    on(underlying).set("aliases", aliases)
  }
}
private object ReflectionBasedGetIndexResponse {
  import org.joor.Reflect.onClass

  def createEmpty(): GetIndexResponse = {
    onClass(classOf[GetIndexResponse])
      .create()
      .get[GetIndexResponse]()
  }
}