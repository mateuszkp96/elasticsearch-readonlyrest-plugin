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
package tech.beshu.ror.acl

import monix.eval.Task
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.request.RequestContext

object DisabledAcl extends Acl {
  override def handle(requestContext: RequestContext): Task[AclHandlingResult] = Task.now(passRequestResult)

  private val passRequestResult: AclHandlingResult = new AclHandlingResult {
    override val history: Vector[Block.History] = Vector.empty
    override val handlingResult: Result = Result.PassedThrough
  }
}