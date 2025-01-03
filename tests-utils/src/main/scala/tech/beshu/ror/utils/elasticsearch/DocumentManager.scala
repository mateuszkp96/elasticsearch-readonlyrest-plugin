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

import better.files.File
import cats.data.NonEmptyList
import org.apache.http.HttpResponse
import org.apache.http.client.methods.*
import org.apache.http.entity.{FileEntity, StringEntity}
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.DocumentManager.BulkAction
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import tech.beshu.ror.utils.misc.Version
import ujson.Value

class DocumentManager(restClient: RestClient, esVersion: String)
  extends BaseManager(restClient, esVersion, esNativeApi = true) {

  def mGet(query: JSON): MGetResult = {
    call(createMGetRequest(query), new MGetResult(_))
  }

  def mGet(query: JSON, queryParams: Map[String, String]): MGetResult = {
    call(createMGetRequest(query, queryParams), new MGetResult(_))
  }

  def get(index: String, id: Int): JsonResponse = {
    call(new HttpGet(restClient.from(createDocPathWithDefaultType(index, s"$id"))), new JsonResponse(_))
  }

  def get(index: String, id: Int, queryParams: Map[String, String]): JsonResponse = {
    val uri = restClient.from(createDocPathWithDefaultType(index, s"$id"), queryParams)
    call(new HttpGet(uri), new JsonResponse(_))
  }

  def createFirstDoc(index: String, content: JSON): JsonResponse = {
    call(createInsertDocRequest(createDocPathWithDefaultType(index, "1"), content, waitForRefresh = true), new JsonResponse(_))
  }

  def createDoc(index: String, id: Int, content: JSON): JsonResponse = {
    call(createInsertDocRequest(createDocPathWithDefaultType(index, s"$id"), content, waitForRefresh = true), new JsonResponse(_))
  }

  def createDoc(index: String, id: String, content: JSON): JsonResponse = {
    call(createInsertDocRequest(createDocPathWithDefaultType(index, id), content, waitForRefresh = true), new JsonResponse(_))
  }

  def createDoc(index: String, `type`: String, id: Int, content: JSON): JsonResponse = {
    call(createInsertDocRequest(createDocPathWithDefaultType(index, s"$id", Some(`type`)), content, waitForRefresh = true), new JsonResponse(_))
  }

  def createDoc(index: String, `type`: String, id: String, content: JSON): JsonResponse = {
    call(createInsertDocRequest(createDocPathWithDefaultType(index, s"$id", Some(`type`)), content, waitForRefresh = true), new JsonResponse(_))
  }

  def createDocWithGeneratedId(index: String, content: JSON): JsonResponse = {
    call(createInsertDocWithGeneratedIdRequest(s"/$index/_doc", content, waitForRefresh = true), new JsonResponse(_))
  }

  def deleteDoc(index: String, id: Int): JsonResponse = {
    call(createDeleteDocRequest(createDocPathWithDefaultType(index, s"$id"), waitForRefresh = true), new JsonResponse(_))
  }

  def deleteByQuery(index: String, query: JSON): JsonResponse = {
    call(createDeleteByQueryRequest(index, query), new JsonResponse(_))
  }

  def bulk(action: BulkAction, actions: BulkAction*): JsonResponse = {
    call(createBulkRequest(NonEmptyList.of(action, actions: _*)), new JsonResponse(_))
  }

  def bulk(actionsFile: File): JsonResponse = {
    call(createBulkRequest(actionsFile), new JsonResponse(_))
  }

  private def createDocPath(index: String, `type`: String, id: String) = {
    s"""/$index/${`type`}/$id"""
  }

  private def createDocPathWithDefaultType(index: String, id: String, fallbackType: Option[String] = None) = {
    if(Version.greaterOrEqualThan(esVersion, 7, 0, 0)) createDocPath(index, "_doc", id)
    else createDocPath(index, fallbackType.getOrElse("doc"), id)
  }

  def createDocAndAssert(index: String, `type`: String, id: Int, content: JSON): Unit = {
    val docPath = createDocPathWithDefaultType(index, s"$id", Some(`type`))
    val createDocResult = call(
      createInsertDocRequest(docPath, content, waitForRefresh = true),
      new JsonResponse(_)
    )
    if(!createDocResult.isSuccess || createDocResult.responseJson("result").str != "created") {
      throw new IllegalStateException(s"Cannot create document '$docPath'; returned: ${createDocResult.body}")
    }
  }

  private def createInsertDocRequest(docPath: String, content: JSON, waitForRefresh: Boolean): HttpPut = {
    val request = new HttpPut(restClient.from(docPath, waitForRefreshParam(waitForRefresh)))
    request.setHeader("timeout", "50s")
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(content)))
    request
  }

  private def createInsertDocWithGeneratedIdRequest(docPath: String, content: JSON, waitForRefresh: Boolean): HttpPost = {
    val request = new HttpPost(restClient.from(docPath, waitForRefreshParam(waitForRefresh)))
    request.setHeader("timeout", "50s")
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(content)))
    request
  }

  private def createDeleteDocRequest(docPath: String, waitForRefresh: Boolean) = {
    new HttpDelete(restClient.from(docPath, waitForRefreshParam(waitForRefresh)))
  }

  private def createDeleteByQueryRequest(index: String, query: JSON) = {
    val request = new HttpPost(restClient.from(s"$index/_delete_by_query"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(query)))
    request
  }

  private def waitForRefreshParam(waitForRefresh: Boolean) = {
    if (waitForRefresh) Map("refresh" -> "wait_for")
    else Map.empty[String, String]
  }

  private def createMGetRequest(query: JSON, queryParams: Map[String, String] = Map.empty): HttpUriRequest = {
    val request = new HttpGetWithEntity(restClient.from("_mget", queryParams))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(query)))
    request
  }

  private def createBulkRequest(actions: NonEmptyList[BulkAction]): HttpUriRequest = {
    val request = new HttpPost(restClient.from("_bulk"))
    request.addHeader("Content-Type", "application/json")

    val lines = actions
      .toList
      .flatMap {
        case BulkAction.Insert(index, id, document) => bulkCreateIndexDocEntry(index, id, document)
      } :+ "\n"

    val payload = (lines :+ "\n").foldLeft("") { case (acc, elem) => s"$acc\n$elem" }
    request.setEntity(new StringEntity(payload))
    request
  }

  private def createBulkRequest(ndJsonfile: File): HttpUriRequest = {
    val request = new HttpPost(restClient.from("_bulk"))
    request.addHeader("Content-Type", "application/x-ndjson")
    request.setEntity(new FileEntity(ndJsonfile.toJava))
    request
  }

  private def bulkCreateIndexDocEntry(index: String, docId: Int, document: JSON) = {
    if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      Seq(
        s"""{ "create" : { "_index" : "$index", "_id" : "$docId" } }""",
        ujson.write(document)
      )
    } else {
      Seq(
        s"""{ "create" : { "_index" : "$index", "_type" : "doc", "_id" : "$docId" } }""",
        ujson.write(document)
      )
    }
  }

  class MGetResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val docs: List[Value] = responseJson("docs").arr.toList
  }
}
object DocumentManager {

  sealed trait BulkAction
  object BulkAction {
    final case class Insert(index: String, id: Int, document: JSON) extends BulkAction
  }
}
