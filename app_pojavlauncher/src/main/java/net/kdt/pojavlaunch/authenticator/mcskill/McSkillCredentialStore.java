package net.kdt.pojavlaunch.authenticator.mcskill;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Stores mcskill account passwords encrypted at rest, keyed by username, for silent session refresh.
 *
 * <p>Requires API 23+. Below that, {@code androidx.security:security-crypto}'s {@code MasterKey} has
 * no Android Keystore backing and silently degrades to a cleartext Tink keyset sitting next to the
 * "encrypted" values — which would mean storing the password in the clear. Rather than provide a
 * store that only pretends to be encrypted, the constructor refuses to build one.
 */
public class McSkillCredentialStore {
    private static final String PREFS_FILE_NAME = "mcskill_credentials";

    /** Lowest API level on which {@code MasterKey} is actually Keystore-backed. */
    public static final int MIN_SUPPORTED_SDK = Build.VERSION_CODES.M;

    private final SharedPreferences prefs;

    /**
     * @return whether this device can store mcskill credentials with real at-rest encryption.
     * Callers that can show UI should check this before starting an mcskill login.
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= MIN_SUPPORTED_SDK;
    }

    public McSkillCredentialStore(Context context) throws GeneralSecurityException, IOException {
        if (!isSupported()) {
            throw new GeneralSecurityException("mcskill credential storage requires Android 6.0 (API "
                    + MIN_SUPPORTED_SDK + ") or newer; this device is API " + Build.VERSION.SDK_INT
                    + ", where EncryptedSharedPreferences has no Keystore-backed master key.");
        }
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public void storePassword(String username, String password) {
        prefs.edit().putString(username, password).apply();
    }

    public String getPassword(String username) {
        return prefs.getString(username, null);
    }

    public void clearPassword(String username) {
        prefs.edit().remove(username).apply();
    }
}
