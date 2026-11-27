package com.chenan.flymebobo;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v603: AI API Key 本地加密储存（wei 定：开启时
 * 提示「本地加密储存」——话要算数，落盘必须是真
 * 密文）。AES-GCM，密钥由 AndroidKeyStore 生成
 * 并托管（别名 flymebobo_ai_key，永不离开系统
 * 密钥库）；密文格式 enc:v1:Base64(IV12+密文)。
 * 旧版明文（v600–v602 存的）decrypt 原样返回，
 * 兼容不丢；下次保存自动升级为密文。加密不可用
 * 时退回明文（功能优先，不锁死用户）。
 */
public class AiKey {

    private static final String PREFIX = "enc:v1:";
    private static final String ALIAS = "flymebobo_ai_key";
    private static final int IV_LEN = 12;

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e)
                        .getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT
                        | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(
                    plain.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(blob,
                    Base64.NO_WRAP);
        } catch (Throwable t) {
            return plain;
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] blob = Base64.decode(
                    stored.substring(PREFIX.length()),
                    Base64.NO_WRAP);
            if (blob.length <= IV_LEN) {
                return "";
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(blob, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, iv));
            byte[] pt = c.doFinal(blob, IV_LEN,
                    blob.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
