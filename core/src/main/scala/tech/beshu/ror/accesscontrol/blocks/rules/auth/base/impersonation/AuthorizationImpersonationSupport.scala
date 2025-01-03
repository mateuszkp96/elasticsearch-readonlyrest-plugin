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
package tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation

import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.domain.{Group, RequestId, User}
import tech.beshu.ror.utils.uniquelist.UniqueList

private[rules] trait AuthorizationImpersonationSupport extends ImpersonationSupport

private[rules] trait SimpleAuthorizationImpersonationSupport extends AuthorizationImpersonationSupport {
  this: AuthorizationRule =>

  protected def impersonation: Impersonation

  protected def mockedGroupsOf(user: User.Id,
                               mocksProvider: MocksProvider)
                              (implicit requestId: RequestId): Groups
}
object SimpleAuthorizationImpersonationSupport {
  sealed trait Groups
  object Groups {
    final case class Present(groups: UniqueList[Group]) extends Groups
    case object CannotCheck extends Groups
  }
}

trait AuthorizationImpersonationCustomSupport extends AuthorizationImpersonationSupport