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
package tech.beshu.ror.es.handler.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ScalaOps._

class ReindexEsRequestContext(actionRequest: ReindexRequest,
                              esContext: EsContext,
                              aclContext: AccessControlStaticContext,
                              clusterService: RorClusterService,
                              override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ReindexRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ReindexRequest): Set[ClusterIndexName] = {
    val searchRequestIndices = request.getSearchRequest.indices.asSafeSet
    val indexOfIndexRequest = request.getDestination.index()

    (searchRequestIndices + indexOfIndexRequest)
      .flatMap(ClusterIndexName.fromString)
  }

  override protected def update(request: ReindexRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    val searchRequestIndices = actionRequest.getSearchRequest.indices().asSafeSet.flatMap(ClusterIndexName.fromString)
    val isSearchRequestComposedOnlyOfAllowedIndices = (searchRequestIndices -- filteredIndices.toList).isEmpty

    val indexOfIndexRequest = actionRequest.getDestination.index()
    val isDestinationIndexOnFilteredIndicesList = ClusterIndexName.fromString(indexOfIndexRequest).exists(filteredIndices.toList.contains(_))

    if (isDestinationIndexOnFilteredIndicesList && isSearchRequestComposedOnlyOfAllowedIndices) {
      Modified
    } else {
      if (!isDestinationIndexOnFilteredIndicesList) {
        logger.info(s"[${id.show}] Destination index of _reindex request is forbidden")
      }
      if (!isSearchRequestComposedOnlyOfAllowedIndices) {
        logger.info(s"[${id.show}] At least one index from sources indices list of _reindex request is forbidden")
      }
      ShouldBeInterrupted
    }
  }
}
