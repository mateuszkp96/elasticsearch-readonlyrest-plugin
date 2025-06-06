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
package tech.beshu.ror.es.handler.request.context.types.utils

import org.elasticsearch.cluster.metadata.AliasMetadata
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.{Conversion, Matchable}
import tech.beshu.ror.es.handler.request.context.types.utils.FilterableAliasesMap.AliasesMap
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

class FilterableAliasesMap(val value: AliasesMap) extends AnyVal {

  import FilterableAliasesMap.{conversion, matchable}

  def filterOutNotAllowedAliases(allowedAliases: Iterable[ClusterIndexName]): AliasesMap = {
    filter(value.asSafeMap.toList, allowedAliases).toMap.asJava
  }

  private def filter(responseIndicesNadAliases: List[(String, java.util.List[AliasMetadata])],
                     allowedAliases: Iterable[ClusterIndexName]) = {
    if (allowedAliases.isEmpty) List.empty
    else {
      val matcher = PatternsMatcher.create(allowedAliases.stringify)
      responseIndicesNadAliases
        .map { case (indexName, aliasesList) =>
          val filteredAliases = matcher.filter(aliasesList.asSafeList)
          (indexName, filteredAliases.toList.asJava)
        }
    }
  }

}

object FilterableAliasesMap {
  private implicit val conversion: PatternsMatcher[String]#Conversion[AliasMetadata] = Conversion.from(_.alias())
  private implicit val matchable: Matchable[String] = Matchable.caseSensitiveStringMatchable

  type AliasesMap = java.util.Map[String, java.util.List[AliasMetadata]]

  implicit def toFilterableAliasesMap(map: AliasesMap): FilterableAliasesMap = new FilterableAliasesMap(map)
}
