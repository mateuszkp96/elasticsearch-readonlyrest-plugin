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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.RorKbnDefinitionsDecoder.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

class RorKbnAuthRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef],
                            globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthRule] {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthRule]] = {
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE { case (name, groupsLogic) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) => Right(RorKbnAuthRule.Settings(rorKbnDef, groupsLogic))
          case None => Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .map { settings =>
        RuleDefinition.create(new RorKbnAuthRule(settings, globalSettings.userIdCaseSensitivity))
      }
      .decoder
  }
}

private object RorKbnAuthRuleDecoder {

  private val nameAndGroupsSimpleDecoder: Decoder[(RorKbnDef.Name, Groups)] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(RorKbnDef.Name.apply)
      .map((_, Groups.NotDefined))

  private val nameAndGroupsExtendedDecoder: Decoder[(RorKbnDef.Name, Groups)] =
    Decoder
      .instance { c =>
        for {
          rorKbnDefName <- c.downField("name").as[RorKbnDef.Name]
          groupsLogicDecodingResult <- GroupsLogicDecoder.decoder[RorKbnAuthRule].apply(c)
        } yield (rorKbnDefName, groupsLogicDecodingResult)
      }
      .toSyncDecoder
      .emapE {
        case (name, groupsLogicDecodingResult) =>
          groupsLogicDecodingResult match {
            case GroupsLogicDecodingResult.Success(groupsLogic) =>
              Right((name, Groups.Defined(groupsLogic)))
            case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
              Right((name, Groups.NotDefined: Groups))
            case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(_, fields) =>
              val fieldsStr = fields.map(f => s"'$f'").mkString(" or ")
              Left(RulesLevelCreationError(Message(
                s"Please specify either $fieldsStr for ROR Kibana authorization rule '${name.show}'"
              )))
          }
      }
      .decoder

}
