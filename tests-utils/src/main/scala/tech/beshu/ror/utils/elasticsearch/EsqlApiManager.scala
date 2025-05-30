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
package tech.beshu.ror.utils.elasticsearch

import org.apache.http.HttpResponse
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.httpclient.RestClient
import org.apache.http.client.methods.HttpPost
import ujson.Arr

class EsqlApiManager(restClient: RestClient, esVersion: String)
  extends BaseManager(restClient, esVersion, true) {

  def execute(selectQuery: String): EsqlResult = {
    call(createSqlQueryRequest(selectQuery), new EsqlResult(_))
  }

  private def createSqlQueryRequest(query: String) = {
    val request = new HttpPost(restClient.from("_query", Map("format" -> "json")))
    request.setHeader("Content-Type", "application/json")
    request.setHeader("timeout", "50s")
    request.setEntity(new StringEntity(s"""{ "query": "$query" }"""))
    request
  }

  class EsqlResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val queryResult: Map[String, Vector[JSON]] = {
      val columns = responseJson("columns").arr.map(_.obj("name").str).toVector
      val rows = responseJson("values").arr.toVector.map(_.arr.toVector).transpose
      columns.zip(rows).toMap
    }

    lazy val columnNames: List[String] = responseJson("columns").arr.toList.map(_.obj("name").str)
    lazy val rows: Vector[JSON] = responseJson("values").arr.toVector
    lazy val columns: Vector[JSON] = responseJson("columns").arr.toVector

    def row(idx: Int): Arr = rows(idx).arr

    def rowValue(rowIdx: Int, valueIdx: Int): JSON = row(rowIdx).arr.toVector(valueIdx)

    def rowValue(rowIdx: Int, columnName: String): JSON = row(rowIdx).arr.toVector(columnIdxBy(columnName))

    def column(columnName: String): Vector[JSON] = rows.map(_.arr.toVector(columnIdxBy(columnName)))

    private def columnIdxBy(name: String) = columnNames.indexOf(name)
  }
}
