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
package tech.beshu.ror.es.handler.response

import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.index.get.GetResult
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, DocumentId, DocumentWithIndex}
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.response.FieldsFiltering.{MetadataDocumentFields, NewFilteredDocumentFields}

import scala.jdk.CollectionConverters.*

object DocumentApiOps {

  object GetApi {

    //it's ugly but I don't know better way to do it
    def doesNotExistResponse(original: GetResponse): GetResponse = {
      val exists = false
      val source = null
      val result = new GetResult(
        original.getIndex,
        original.getType,
        original.getId,
        original.getSeqNo,
        original.getPrimaryTerm,
        original.getVersion,
        exists,
        source,
        java.util.Collections.emptyMap(),
        java.util.Collections.emptyMap())
      new GetResponse(result)
    }

    implicit class GetResponseOps(val response: GetResponse) extends AnyVal {
      def asDocumentWithIndex: DocumentWithIndex = createDocumentWithIndex(response.getIndex, response.getId)

      def filterFieldsUsing(fieldsRestrictions: FieldsRestrictions): GetResponse = {
        val newSource = filterSourceFieldsUsing(fieldsRestrictions)
        val newFields = filterDocumentFieldsUsing(fieldsRestrictions)

        val newResult = new GetResult(
          response.getIndex,
          response.getType,
          response.getId,
          response.getSeqNo,
          response.getPrimaryTerm,
          response.getVersion,
          true,
          newSource,
          newFields.nonMetadataDocumentFields.value.asJava,
          newFields.metadataDocumentFields.value.asJava
        )
        new GetResponse(newResult)
      }

      private def filterSourceFieldsUsing(fieldsRestrictions: FieldsRestrictions) = {
        Option(response.getSourceAsMap)
          .map(_.asScala.toMap)
          .filter(_.nonEmpty)
          .map(source => FieldsFiltering.filterSource(source, fieldsRestrictions)) match {
          case Some(value) => value.bytes
          case None => response.getSourceAsBytesRef
        }
      }

      private def filterDocumentFieldsUsing(fieldsRestrictions: FieldsRestrictions) = {
        val (originalMetadataFields, originalNonMetadataFields) =
          response
            .getFields.asScala
            .toMap
            .partition(_._2.isMetadataField)

        val filteredNonMetadataFields = FieldsFiltering.filterNonMetadataDocumentFields(
          FieldsFiltering.NonMetadataDocumentFields(originalNonMetadataFields),
          fieldsRestrictions
        )

        NewFilteredDocumentFields(filteredNonMetadataFields, MetadataDocumentFields(originalMetadataFields))
      }
    }
  }

  object MultiGetApi {
    implicit class MultiGetItemResponseOps(val item: MultiGetItemResponse) extends AnyVal {
      def asDocumentWithIndex: DocumentWithIndex = createDocumentWithIndex(item.getIndex, item.getId)
    }
  }

  private def createDocumentWithIndex(indexStr: String, docId: String) = {
    val indexName = createIndexName(indexStr)
    val documentId = DocumentId(docId)
    DocumentWithIndex(indexName, documentId)
  }

  private def createIndexName(indexStr: String) = {
    ClusterIndexName
      .fromString(indexStr)
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
  }
}
