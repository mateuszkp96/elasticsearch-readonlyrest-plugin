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
package tech.beshu.ror.es.services

import java.util.function.Supplier

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.action.support.PlainActionFuture
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.RepositoriesMetadata
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.repositories.{RepositoriesService, RepositoryData}
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils._
import tech.beshu.ror.es.utils.GenericResponseListener
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

class EsServerBasedRorClusterService(clusterService: ClusterService,
                                     repositoriesServiceSupplier: Supplier[Option[RepositoriesService]],
                                     nodeClient: NodeClient)
  extends RorClusterService
    with Logging {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metadata.getIndicesLookup
    lookup.get(indexOrAlias.value.value).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    val indices = clusterService.state.metadata.getIndices
    indices
      .keysIt().asScala
      .flatMap { index =>
        val indexMetaData = indices.get(index)
        IndexName
          .fromString(indexMetaData.getIndex.getName)
          .map { indexName =>
            val aliases = indexMetaData.getAliases.asSafeKeys.flatMap(IndexName.fromString)
            (indexName, aliases)
          }
      }
      .toMap
  }

  override def allTemplates: Set[Template] = {
    legacyTemplates() ++ indexTemplates() ++ componentTemplates()
  }

  override def allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = {
    val repositoriesMetadata: RepositoriesMetadata = clusterService.state().metadata().custom(RepositoriesMetadata.TYPE)
    repositoriesMetadata
      .repositories().asSafeList
      .flatMap { repositoryMetadata =>
        RepositoryName
          .from(repositoryMetadata.name())
          .flatMap {
            case r: RepositoryName.Full => Some(r)
            case _ => None
          }
          .map { name => (name, snapshotsBy(name)) }
      }
      .toMap
  }

  private def snapshotsBy(repositoryName: RepositoryName) = {
    repositoriesServiceSupplier.get() match {
      case Some(repositoriesService) =>
        val repositoryData: RepositoryData = PlainActionFuture.get { fut: PlainActionFuture[RepositoryData] =>
          repositoriesService.getRepositoryData(RepositoryName.toString(repositoryName), fut)
        }
        repositoryData
          .getSnapshotIds.asSafeIterable
          .flatMap { sId =>
            SnapshotName
              .from(sId.getName)
              .flatMap {
                case SnapshotName.Wildcard => None
                case SnapshotName.All => None
                case SnapshotName.Pattern(_) => None
                case f: SnapshotName.Full => Some(f)
              }
          }
          .toSet
      case None =>
        logger.error("Cannot supply Snapshots Service. Please, report the issue!!!")
        Set.empty[SnapshotName.Full]
    }
  }

  private def legacyTemplates() = {
    val templates = clusterService.state.metadata().templates()
    templates
      .keysIt().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.fromList(
            templateMetaData.patterns().asScala.flatMap(IndexPattern.fromString).toList
          )
          aliases = templateMetaData.aliases().asSafeValues.flatMap(a => IndexName.fromString(a.alias()))
        } yield Template.LegacyTemplate(templateName, indexPatterns, aliases)
      }
      .toSet
  }

  private def indexTemplates() = {
    val templates = clusterService.state.metadata().templatesV2()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.fromList(
            templateMetaData.indexPatterns().asScala.flatMap(IndexPattern.fromString).toList
          )
          aliases = templateMetaData.template().asSafeSet
            .flatMap(_.aliases().asSafeMap.values.flatMap(a => IndexName.fromString(a.alias())).toSet)
        } yield Template.IndexTemplate(templateName, indexPatterns, aliases)
      }
      .toSet
  }

  private def componentTemplates() = {
    val templates = clusterService.state.metadata().componentTemplates()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          aliases = templateMetaData.template().aliases().asSafeMap.values.flatMap(a => IndexName.fromString(a.alias())).toSet
        } yield Template.ComponentTemplate(templateName, aliases)
      }
      .toSet
  }

  override def verifyDocumentAccessibility(document: Document,
                                           filter: Filter,
                                           id: RequestContext.Id): Task[DocumentAccessibility] = {
    val listener = new GenericResponseListener[SearchResponse]
    createSearchRequest(filter, document).execute(listener)

    listener.result
      .map(extractAccessibilityFrom)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify get request. Blocking document", ex)
          Inaccessible
      }
  }

  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: Filter,
                                              id: RequestContext.Id): Task[DocumentsAccessibilities] = {
    val listener = new GenericResponseListener[MultiSearchResponse]
    createMultiSearchRequest(filter, documents).execute(listener)

    listener.result
      .map(extractResultsFromSearchResponse)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify documents returned by multi get response. Blocking all returned documents", ex)
          blockAllDocsReturned(documents)
      }
      .map(results => zip(results, documents))
  }

  private def createSearchRequest(filter: Filter,
                                  document: Document): SearchRequestBuilder = {
    val wrappedQueryFromFilter = QueryBuilders.wrapperQuery(filter.value.value)
    val composedQuery = QueryBuilders
      .boolQuery()
      .filter(QueryBuilders.constantScoreQuery(wrappedQueryFromFilter))
      .filter(QueryBuilders.idsQuery().addIds(document.documentId.value))

    nodeClient
      .prepareSearch(document.index.value.value)
      .setQuery(composedQuery)
  }

  private def extractAccessibilityFrom(searchResponse: SearchResponse) = {
    if (searchResponse.getHits.getTotalHits.value == 0L) Inaccessible
    else Accessible
  }

  private def createMultiSearchRequest(definedFilter: Filter,
                                       documents: NonEmptyList[Document]) = {
    documents
      .map(createSearchRequest(definedFilter, _))
      .foldLeft(nodeClient.prepareMultiSearch())(_ add _)
  }

  private def blockAllDocsReturned(docsToVerify: NonEmptyList[Document]) = {
    List.fill(docsToVerify.size)(Inaccessible)
  }

  private def extractResultsFromSearchResponse(multiSearchResponse: MultiSearchResponse) = {
    multiSearchResponse
      .getResponses
      .map(resolveAccessibilityBasedOnSearchResult)
      .toList
  }

  private def resolveAccessibilityBasedOnSearchResult(mSearchItem: MultiSearchResponse.Item): DocumentAccessibility = {
    if (mSearchItem.isFailure) Inaccessible
    else if (mSearchItem.getResponse.getHits.getTotalHits.value == 0L) Inaccessible
    else Accessible
  }

  private def zip(results: List[DocumentAccessibility],
                  documents: NonEmptyList[Document]) = {
    documents.toList
      .zip(results)
      .toMap
  }
}