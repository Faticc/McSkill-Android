package net.kdt.pojavlaunch.authenticator.mcskill;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Stores mcskill account passwords encrypted at rest, keyed by username, for silent session refresh. */
public class McSkillCredentialStore {
    private static final String PREFS_FILE_NAME = "mcskill_credentials";

    private final SharedPreferences prefs;

    public McSkillCredentialStore(Context context) throws GeneralSecurityException, IOException {
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
