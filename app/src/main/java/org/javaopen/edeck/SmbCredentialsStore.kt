package org.javaopen.edeck

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmbCredentials(val domain: String, val username: String, val password: String)

object SmbCredentialsStore {
    private const val PREFS = "smb_connections"
    private const val LAST_SOURCE = "last_source"
    private const val KEY_ALIAS = "org.javaopen.edeck.smb.credentials"
    private const val KEYSTORE = "AndroidKeyStore"

    fun lastSource(context: Context): String? = preferences(context).getString(LAST_SOURCE, null)

    fun credentialsFor(context: Context, address: String): SmbCredentials? = runCatching {
        val sourceKey = sourceKey(address) ?: return null
        val prefs = preferences(context)
        val iv = prefs.getString("$sourceKey.iv", null) ?: return null
        val encrypted = prefs.getString("$sourceKey.value", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(sourceKey.toByteArray(Charsets.UTF_8))
        val values = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            .toString(Charsets.UTF_8).split('\u0000', limit = 3)
        if (values.size != 3) return null
        SmbCredentials(values[0], values[1], values[2])
    }.getOrNull()

    fun credentialsForResource(context: Context, resourceAddress: String): SmbCredentials? {
        credentialsFor(context, resourceAddress)?.let { return it }
        val recentSmbAddress = (RecentSourceStore.load(context) as? RecentSource.Smb)?.address
        val candidateRoots = listOfNotNull(recentSmbAddress, lastSource(context)).distinct()
        return candidateRoots.firstNotNullOfOrNull { root ->
            if (isResourceUnderShare(root, resourceAddress)) credentialsFor(context, root) else null
        }
    }

    private fun isResourceUnderShare(shareAddress: String, resourceAddress: String): Boolean = runCatching {
        fun asUri(address: String) = Uri.parse(address.trim().let {
            if (it.startsWith("smb://", true)) it else "smb://$it"
        })
        val root = asUri(shareAddress)
        val resource = asUri(resourceAddress)
        if (!root.scheme.equals("smb", true) || !resource.scheme.equals("smb", true)) return false
        if (!root.host.equals(resource.host, true) || root.port != resource.port) return false
        val rootPath = root.encodedPath.orEmpty().trimEnd('/')
        val resourcePath = resource.encodedPath.orEmpty()
        rootPath.isEmpty() || resourcePath == rootPath || resourcePath.startsWith("$rootPath/")
    }.getOrDefault(false)

    fun remember(context: Context, address: String, credentials: SmbCredentials) {
        val sourceKey = sourceKey(address) ?: return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(sourceKey.toByteArray(Charsets.UTF_8))
        val plain = listOf(credentials.domain, credentials.username, credentials.password).joinToString("\u0000")
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        preferences(context).edit()
            .putString(LAST_SOURCE, address.trim().let { if (it.startsWith("smb://", true)) it else "smb://$it" })
            .putString("$sourceKey.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$sourceKey.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun sourceKey(address: String): String? = runCatching {
        val uri = Uri.parse(address.trim().let { if (it.startsWith("smb://", true)) it else "smb://$it" })
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.encodedPath.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
        val canonical = "smb://$host$port$path"
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
