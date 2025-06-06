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
package tech.beshu.ror.unit.acl.blocks.rules.http

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.syntax.*

class MethodsRuleTests extends AnyWordSpec with MockFactory {

  "A MethodsRule" should {
    "match" when {
      "configured method is GET and request method is also GET" in {
        assertMatchRule(
          configuredMethods = NonEmptySet.of(Method.GET),
          requestMethod = Method.GET
        )
      }
      "configured methods are GET, POST, PUT and request method is GET" in {
        assertMatchRule(
          configuredMethods = NonEmptySet.of(Method.GET, Method.POST, Method.PUT),
          requestMethod = Method.GET
        )
      }
    }
    "not match" when {
      "configured methods are GET, POST, PUT but request method is DELETE" in {
        assertNotMatchRule(
          configuredMethods = NonEmptySet.of(Method.GET, Method.POST, Method.PUT),
          requestMethod = Method.DELETE
        )
      }
    }
  }

  private def assertMatchRule(configuredMethods: NonEmptySet[Method], requestMethod: Method) =
    assertRule(configuredMethods, requestMethod, isMatched = true)

  private def assertNotMatchRule(configuredMethods: NonEmptySet[Method], requestMethod: Method) =
    assertRule(configuredMethods, requestMethod, isMatched = false)

  private def assertRule(configuredMethods: NonEmptySet[Method], requestMethod: Method, isMatched: Boolean) = {
    val rule = new MethodsRule(MethodsRule.Settings(configuredMethods))
    val restRequest = mock[RestRequest]
    (() => restRequest.method).expects().returning(requestMethod)
    val requestContext = mock[RequestContext]
    (() => requestContext.restRequest).expects().returning(restRequest)
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }
}
