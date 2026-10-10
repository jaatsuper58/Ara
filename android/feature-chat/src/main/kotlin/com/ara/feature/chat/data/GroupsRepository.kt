package com.ara.feature.chat.data

import com.ara.core.common.AccountRepository
import com.ara.core.common.AppError
import com.ara.core.common.AppResult
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.GroupMemberEntity
import com.ara.core.net.api.AddMembersRequest
import com.ara.core.net.api.CreateGroupRequest
import com.ara.core.net.api.GroupsApi
import com.ara.core.net.api.PatchGroupRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group management (§7.7, Phase 8): the backend owns membership metadata;
 * the client caches members in the encrypted local DB and derives the local
 * group chat id deterministically so sender-key state stays consistent across
 * devices.
 */
@Singleton
class GroupsRepository @Inject constructor(
    private val groupsApi: GroupsApi,
    private val chatDao: ChatDao,
    private val accountRepository: AccountRepository,
) {

    fun chatIdFor(serverGroupId: String): String = "grp_$serverGroupId"

    suspend fun createGroup(name: String, memberIds: List<String>): AppResult<String> =
        withContext(Dispatchers.IO) {
            val me = accountRepository.current()?.araId
                ?: return@withContext AppResult.Failure(AppError("auth", "Not signed in"))
            try {
                val dto = groupsApi.createGroup(CreateGroupRequest(name = name.ifBlank { "Group" }))
                if (memberIds.isNotEmpty()) {
                    groupsApi.addMembers(dto.id, AddMembersRequest(memberIds.filter { it != me }))
                }
                // Re-fetch to get the authoritative member list + roles.
                persistGroup(groupsApi.group(dto.id), me)
                AppResult.Success(chatIdFor(dto.id))
            } catch (t: Throwable) {
                AppResult.Failure(AppError.network(t))
            }
        }

    suspend fun refresh(chatId: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val serverId = chatId.removePrefix("grp_")
        val me = accountRepository.current()?.araId ?: "me"
        try {
            persistGroup(groupsApi.group(serverId), me)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.network(t))
        }
    }

    suspend fun addMember(chatId: String, araId: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val serverId = chatId.removePrefix("grp_")
        val me = accountRepository.current()?.araId ?: "me"
        try {
            persistGroup(groupsApi.addMembers(serverId, AddMembersRequest(listOf(araId))), me)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.network(t))
        }
    }

    suspend fun removeMember(chatId: String, araId: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val serverId = chatId.removePrefix("grp_")
        val me = accountRepository.current()?.araId ?: "me"
        try {
            persistGroup(groupsApi.removeMember(serverId, araId), me)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.network(t))
        }
    }

    suspend fun rename(chatId: String, name: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val serverId = chatId.removePrefix("grp_")
        try {
            val dto = groupsApi.patchGroup(serverId, PatchGroupRequest(name = name))
            chatDao.byId(chatId)?.let { chatDao.upsert(it.copy(title = dto.name)) }
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.network(t))
        }
    }

    suspend fun leave(chatId: String): AppResult<Unit> {
        val me = accountRepository.current()?.araId
            ?: return AppResult.Failure(AppError("auth", "Not signed in"))
        val result = removeMember(chatId, me)
        if (result is AppResult.Success) {
            chatDao.removeMember(chatId, me)
            chatDao.delete(chatId)
        }
        return result
    }

    private suspend fun persistGroup(dto: com.ara.core.net.dto.GroupDto, me: String) {
        val chatId = chatIdFor(dto.id)
        val existing = chatDao.byId(chatId)
        chatDao.upsert(
            ChatEntity(
                id = chatId,
                type = "GROUP",
                title = dto.name,
                lastMessagePreview = existing?.lastMessagePreview,
                lastMessageAt = existing?.lastMessageAt ?: 0,
                unreadCount = existing?.unreadCount ?: 0,
                isPinned = existing?.isPinned ?: false,
                myRole = dto.members.firstOrNull { it.araId == dto.ownerId }?.let { "OWNER" } ?: "MEMBER",
                createdAt = dto.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        )
        chatDao.upsertMembers(
            dto.members.map {
                GroupMemberEntity(
                    chatId = chatId,
                    araId = it.araId,
                    displayName = it.araId,
                    role = it.role,
                    joinedAt = it.joinedAt,
                )
            }
        )
    }
}
