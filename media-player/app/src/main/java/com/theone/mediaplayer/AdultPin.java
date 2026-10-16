package com.theone.mediaplayer;

import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import android.util.Base64;

final class AdultPin {
    static boolean isConfigured(SharedPreferences prefs) { return prefs.contains("adult_pin_hash"); }
    static boolean validate(String pin) { return pin != null && pin.matches("[0-9]{4,8}") && !"1306".equals(pin); }
    static void save(SharedPreferences prefs, String pin) throws Exception {
        if(!validate(pin)) throw new IllegalArgumentException("Choose a different 4-8 digit PIN");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash=derive(pin,salt);
        prefs.edit().putString("adult_pin_salt",Base64.encodeToString(salt,Base64.NO_WRAP))
            .putString("adult_pin_hash",Base64.encodeToString(hash,Base64.NO_WRAP)).apply();
    }
    static boolean check(SharedPreferences prefs, String pin) {
        try {
            byte[] salt=Base64.decode(prefs.getString("adult_pin_salt",""),Base64.NO_WRAP);
            byte[] expected=Base64.decode(prefs.getString("adult_pin_hash",""),Base64.NO_WRAP);
            return MessageDigest.isEqual(expected,derive(pin,salt));
        } catch(Exception e) {return false;}
    }
    private static byte[] derive(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,120000,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally {spec.clearPassword();}
    }
}
