package ru.alexv.anyailive.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Ключи API живут только здесь: AES-GCM, ключ шифрования в Android Keystore (не покидает устройство).
 * В БД и в экспорт попадает лишь имя записи (key_ref).
 */
public class SecretStore {

    private static final String PREF = "secrets";
    private static final String ALIAS = "anyailive_master";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    public SecretStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** @return true, если секрет сохранён. */
    public boolean put(String ref, String secret) {
        if (ref == null || ref.isEmpty() || secret == null) {
            return false;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(secret.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            prefs.edit().putString(ref, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return секрет или null (нет записи либо ключ недоступен). Исключения не вылетают. */
    public String get(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        String stored = prefs.getString(ref, null);
        if (stored == null) {
            return null;
        }
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public void delete(String ref) {
        if (ref != null) {
            prefs.edit().remove(ref).apply();
        }
    }
}
