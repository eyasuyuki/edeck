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

    // Credentials stay encrypted in preferences rather than being stored in the recent
    // source list, which only needs a share address to restore the UI.
    fun credentialsFor(context: Context, address: String): SmbCredentials? = runCatching {
        val sourceKey = sourceKey(address) ?: return null
        val prefs = preferences(context)
        val iv = prefs.getString("$sourceKey.iv", null) ?: return null
        val encrypted = prefs.getString("$sourceKey.value", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        // Binding ciphertext to its canonical share key prevents a stored blob from being
        // silently accepted under a different server/share preference entry.
        cipher.updateAAD(sourceKey.toByteArray(Charsets.UTF_8))
        val values = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            .toString(Charsets.UTF_8).split('\u0000', limit = 3)
        if (values.size != 3) return null
        SmbCredentials(values[0], values[1], values[2])
    // Missing, invalidated, or undecryptable secrets are represented as unavailable so
    // source restoration can show a reconnect message instead of crashing the main screen.
    }.getOrNull()

    fun credentialsForResource(context: Context, resourceAddress: String): SmbCredentials? {
        credentialsFor(context, resourceAddress)?.let { return it }
        // Playback receives a file URI, while users save credentials against a share URI.
        // Search only known roots and try the longest matching root first so nested shares
        // with their own credentials take precedence over a broader parent share.
        val savedSmbAddresses = RecentSourceStore.sources(context).filterIsInstance<RecentSource.Smb>().map { it.address }
        val candidateRoots = (savedSmbAddresses + listOfNotNull(lastSource(context))).distinct()
            .sortedByDescending(String::length)
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
        // The slash boundary avoids treating /Media2 as a child of /Media.
        rootPath.isEmpty() || resourcePath == rootPath || resourcePath.startsWith("$rootPath/")
    }.getOrDefault(false)

    fun remember(context: Context, address: String, credentials: SmbCredentials) {
        val sourceKey = sourceKey(address) ?: return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(sourceKey.toByteArray(Charsets.UTF_8))
        // NUL cannot occur in these fields, so it gives a compact reversible encoding
        // without adding a JSON parser or storing the password as a separate clear value.
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
        // Case-insensitive host/share names and optional trailing slashes must resolve to
        // the same preference key even if the user entered the address differently.
        val canonical = "smb://$host$port$path"
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    // A malformed URI cannot identify a credential entry; do not fall back to a weaker
    // key that could accidentally match another host.
    }.getOrNull()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // AndroidKeyStore keeps the AES key outside app preferences/files, so a copied
        // preferences backup alone does not expose saved SMB passwords.
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
