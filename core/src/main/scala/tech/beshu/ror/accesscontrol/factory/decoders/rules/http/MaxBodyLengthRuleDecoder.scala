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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.http

import io.circe.Decoder
import squants.information.Bytes
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.http.MaxBodyLengthRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.MaxBodyLengthRule.Settings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderOps
import tech.beshu.ror.implicits.*

object MaxBodyLengthRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[MaxBodyLengthRule] {

  override protected def decoder: Decoder[RuleDefinition[MaxBodyLengthRule]] = {
    Decoder
      .decodeLong
      .toSyncDecoder
      .emapE { value =>
        if (value >= 0) Right(Bytes(value))
        else Left(RulesLevelCreationError(Message(s"Invalid max body length: ${value.show}")))
      }
      .map(maxBodyLength => RuleDefinition.create(new MaxBodyLengthRule(Settings(maxBodyLength))))
      .decoder
  }
}
