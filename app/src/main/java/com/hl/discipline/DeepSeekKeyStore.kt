package com.hl.discipline

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay outside Store/JSON backups and are encrypted with this installation's key. */
internal object DeepSeekKeyStore {
    private const val ALIAS="discipline-deepseek-api"
    private fun prefs(context:Context)=context.getSharedPreferences("deepseek_private",Context.MODE_PRIVATE)
    fun hasKey(context:Context)=prefs(context).contains("cipher")
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(context:Context,value:String) {
        require(value.trim().length in 8..512 && value.none { it.isWhitespace() }) { "请填写有效的 API Key" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encoded=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs(context).edit().putString("cipher",Base64.encodeToString(encoded,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit()) { "API Key 保存失败" }
    }
    fun read(context:Context):String {
        val prefs=prefs(context)
        val encrypted=prefs.getString("cipher",null) ?: error("请先配置 DeepSeek API Key")
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),Charsets.UTF_8)
        } catch (_:Exception) { error("API Key 无法解密，请在此设备重新填写") }
    }
    fun clear(context:Context) { prefs(context).edit().clear().commit() }
}
