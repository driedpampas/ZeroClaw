/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.data.repository

import android.content.SharedPreferences
import org.eu.nl.syu.zeroclaw.data.local.dao.ConnectedChannelDao
import org.eu.nl.syu.zeroclaw.data.local.entity.toEntity
import org.eu.nl.syu.zeroclaw.data.local.entity.toModel
import org.eu.nl.syu.zeroclaw.model.ChannelType
import org.eu.nl.syu.zeroclaw.model.ConnectedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ChannelConfigRepository] backed by Room for non-secret config and
 * EncryptedSharedPreferences for secret values.
 *
 * Secrets are keyed as `"channel_{channelId}_{fieldKey}"` in the encrypted
 * preferences file to avoid collisions and enable per-channel cleanup.
 *
 * @param dao Room DAO for connected channel entities.
 * @param securePrefs EncryptedSharedPreferences instance for secret storage.
 */
class RoomChannelConfigRepository(
    private val dao: ConnectedChannelDao,
    private val securePrefs: SharedPreferences,
) : ChannelConfigRepository {
    override val channels: Flow<List<ConnectedChannel>> =
        dao.observeAll().map { entities ->
            entities.mapNotNull { it.toModel() }
        }

    override suspend fun getById(id: String): ConnectedChannel? = dao.getById(id)?.toModel()

    override suspend fun existsForType(type: ChannelType): Boolean = dao.getByType(type.name) != null

    override suspend fun save(
        channel: ConnectedChannel,
        secrets: Map<String, String>,
    ) {
        dao.upsert(channel.toEntity())
        val editor = securePrefs.edit()
        secrets.forEach { (key, value) ->
            editor.putString(secretKey(channel.id, key), value)
        }
        editor.apply()
    }

    override suspend fun delete(id: String) {
        val entity = dao.getById(id)
        dao.deleteById(id)
        if (entity != null) {
            val model = entity.toModel()
            if (model != null) {
                val editor = securePrefs.edit()
                model.type.fields
                    .filter { it.isSecret }
                    .forEach { field ->
                        editor.remove(secretKey(id, field.key))
                    }
                editor.apply()
            }
        }
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
    }

    override fun getSecrets(channelId: String): Map<String, String> {
        val all = securePrefs.all
        val prefix = "channel_${channelId}_"
        return all.entries
            .filter { it.key.startsWith(prefix) }
            .associate { (key, value) ->
                key.removePrefix(prefix) to (value as? String).orEmpty()
            }
    }

    override suspend fun getEnabledWithSecrets(): List<Pair<ConnectedChannel, Map<String, String>>> {
        val enabledChannels = dao.getAllEnabled().mapNotNull { it.toModel() }
        val allSecrets = securePrefs.all
        return enabledChannels.map { channel ->
            val prefix = "channel_${channel.id}_"
            val secrets =
                allSecrets.entries
                    .filter { it.key.startsWith(prefix) }
                    .associate { (key, value) ->
                        key.removePrefix(prefix) to (value as? String).orEmpty()
                    }
            val merged = channel.configValues + secrets
            channel to merged
        }
    }

    /**
     * Builds the encrypted preferences key for a secret field.
     *
     * @param channelId Channel identifier.
     * @param fieldKey Field key from the channel type spec.
     * @return Composite key string.
     */
    private fun secretKey(
        channelId: String,
        fieldKey: String,
    ): String = "channel_${channelId}_$fieldKey"
}
