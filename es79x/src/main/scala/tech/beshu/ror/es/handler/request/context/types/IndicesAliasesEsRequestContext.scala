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
import cats.implicits.*
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class IndicesAliasesEsRequestContext(actionRequest: IndicesAliasesRequest,
                                     esContext: EsContext,
                                     aclContext: AccessControlStaticContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[IndicesAliasesRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val originIndices = actionRequest
    .getAliasActions.asScala
    .flatMap { r =>
      r.indices.asSafeSet.flatMap(RequestedIndex.fromString) ++
        r.aliases.asSafeList.flatMap(RequestedIndex.fromString)
    }
    .toCovariantSet

  override protected def requestedIndicesFrom(request: IndicesAliasesRequest): Set[RequestedIndex[ClusterIndexName]] = originIndices

  override protected def update(request: IndicesAliasesRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    if (originIndices == filteredIndices.toList.toCovariantSet) {
      Modified
    } else {
      logger.error(s"[${id.show}] Write request with indices requires the same set of indices after filtering as at the beginning. Please report the issue.")
      ShouldBeInterrupted
    }
  }
}
