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
package tech.beshu.ror.unit.acl.blocks.definitions.ldap.implementations

import cats.data.EitherT
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, LdapHost}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapAuthorizationService, LdapService}
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, PlainTextSecret, User}
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList
import tech.beshu.ror.utils.{SingletonLdapContainers, WithDummyRequestIdSupport}

import java.time.Clock
import scala.concurrent.duration.*
import scala.language.postfixOps

class UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFilteringWhenUserIdAttributeIsUidTests
  extends UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFilteringTests {

  override protected val userIdAttribute: UserIdAttribute = UserIdAttribute.CustomAttribute("uid")
  override protected val morganUserId: User.Id = User.Id("morgan")
  override protected val userSpeakerUserId: User.Id = User.Id("userSpeaker")
  override protected val devitoUserId: User.Id = User.Id("devito")
}

class UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFilteringWhenUserIdAttributeIsCnTests
  extends UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFilteringTests {

  override protected val userIdAttribute: UserIdAttribute = UserIdAttribute.OptimizedCn
  override protected val morganUserId: User.Id = User.Id("Morgan Freeman")
  override protected val userSpeakerUserId: User.Id = User.Id("UserSpeaker (ext)")
  override protected val devitoUserId: User.Id = User.Id("Danny DeVito")
}

abstract class UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFilteringTests
  extends AnyWordSpec
    with BeforeAndAfterAll
    with Inside
    with Eventually
    with WithDummyRequestIdSupport {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  "An LdapAuthorizationService with server side groups filtering" should {
    "has method to provide user groups" which {
      "returns non empty set of groups" when {
        "user has groups" in {
          eventually {
            peopleAndGroupsLdapAuthorizationService.groupsOf(morganUserId, Set(GroupIdLike.from("*All"))).runSyncUnsafe() should be {
              UniqueList.of(group("groupAll", "Group All"))
            }
          }
        }
      }
      "resolve nested groups properly" in {
        eventually {
          usersAndRolesLdapAuthorizationService.groupsOf(userSpeakerUserId, Set(GroupIdLike.from("*ers*"))).runSyncUnsafe() should be {
            UniqueList.of(group("developers", "Developers group"), group("speakers (external)", "Speakers group"))
          }
        }
      }
      "resolve nested groups but with some limitations" in {
        eventually {
          usersAndRolesLdapAuthorizationService.groupsOf(userSpeakerUserId, Set(GroupIdLike.from("dev*"))).runSyncUnsafe() should be {
            // first call returns no groups (the 'developers' group is the nested one), so we cannot resolve them with totally
            UniqueList.empty[Group]
          }
        }
      }
      "returns empty set of groups" when {
        "user has no groups" in {
          eventually {
            peopleAndGroupsLdapAuthorizationService.groupsOf(devitoUserId, Set(GroupIdLike.from("dev*"))).runSyncUnsafe() should be {
              UniqueList.empty[Group]
            }
          }
        }
        "there is no user with given name" in {
          eventually {
            peopleAndGroupsLdapAuthorizationService.groupsOf(User.Id("unknown"), Set(GroupIdLike.from("dev*"))).runSyncUnsafe() should be {
              UniqueList.empty[Group]
            }
          }
        }
      }
    }
  }

  private def peopleAndGroupsLdapAuthorizationService = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("ldap2")
    val ldapConnectionConfig = createLdapConnectionConfig(ldapId)
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), userIdAttribute)
      ))
      authorizationService <- EitherT(
        UnboundidLdapAuthorizationService
          .create(
            id = ldapId,
            ldapUsersService = usersService,
            poolProvider = ldapConnectionPoolProvider,
            connectionConfig = ldapConnectionConfig,
            groupsSearchFilter = UserGroupsSearchFilterConfig(
              mode = DefaultGroupSearch(
                Dn("ou=Groups,dc=example,dc=com"),
                GroupSearchFilter("(cn=*)"),
                GroupAttribute(GroupIdAttribute("cn"), GroupNameAttribute("o")),
                UniqueMemberAttribute("uniqueMember"),
                groupAttributeIsDN = true,
                serverSideGroupsFiltering = true
              ),
              nestedGroupsConfig = None
            )
          )
      )
    } yield authorizationService.asInstanceOf[LdapAuthorizationService.WithGroupsFiltering]
    result.valueOrThrowIllegalState()
  }

  private def usersAndRolesLdapAuthorizationService = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("ldap2")
    val ldapConnectionConfig = createLdapConnectionConfig(ldapId)
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=Users,dc=example,dc=com"), userIdAttribute)
      ))
      authorizationService <- EitherT(
        UnboundidLdapAuthorizationService
          .create(
            id = ldapId,
            ldapUsersService = usersService,
            poolProvider = ldapConnectionPoolProvider,
            connectionConfig = ldapConnectionConfig,
            groupsSearchFilter = UserGroupsSearchFilterConfig(
              mode = DefaultGroupSearch(
                Dn("ou=Roles,dc=example,dc=com"),
                GroupSearchFilter("(cn=*)"),
                GroupAttribute(GroupIdAttribute("cn"), GroupNameAttribute("o")),
                UniqueMemberAttribute("uniqueMember"),
                groupAttributeIsDN = true,
                serverSideGroupsFiltering = true
              ),
              nestedGroupsConfig = Some(NestedGroupsConfig(
                nestedLevels = positiveInt(1),
                Dn("ou=Roles,dc=example,dc=com"),
                GroupSearchFilter("(cn=*)"),
                UniqueMemberAttribute("uniqueMember"),
                GroupAttribute(GroupIdAttribute("cn"), GroupNameAttribute("o")),
              ))
            )
          )
      )
    } yield authorizationService.asInstanceOf[LdapAuthorizationService.WithGroupsFiltering]
    result.valueOrThrowIllegalState()
  }

  private def createLdapConnectionConfig(poolName: LdapService.Name) = {
    LdapConnectionConfig(
      poolName = poolName,
      connectionMethod = ConnectionMethod.SingleServer(
        LdapHost
          .from(s"ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}")
          .get
      ),
      poolSize = positiveInt(1),
      connectionTimeout = Refined.unsafeApply(5 seconds),
      requestTimeout = Refined.unsafeApply(5 seconds),
      trustAllCerts = false,
      bindRequestUser = BindRequestUser.CustomUser(
        Dn("cn=admin,dc=example,dc=com"),
        PlainTextSecret("password")
      ),
      ignoreLdapConnectivityProblems = false
    )
  }

  protected def userIdAttribute: UserIdAttribute

  protected def morganUserId: User.Id

  protected def userSpeakerUserId: User.Id

  protected def devitoUserId: User.Id
}
