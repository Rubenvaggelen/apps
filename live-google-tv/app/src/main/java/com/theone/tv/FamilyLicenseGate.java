package com.theone.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * A standalone app installation must be individually approved by the Main owner.
 * Server checks prove possession of a per-installation Android Keystore P-256 private key.
 * Offline access is bounded to 24 hours in the same boot using an encrypted, device-local lease.
 * Without server validation, this class never grants a new license.
 */
final class FamilyLicenseGate {
    private static final String ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/devices.php?action=";
    private static final String SIGNING_ALIAS = "the-one-family-install-v1";
    private static final String CACHE_ALIAS = "the-one-family-lease-v1";
    private static final long MAX_OFFLINE_MS = 24L * 60L * 60L * 1000L;
    private final Activity activity;
    private final String scope;
    private final Runnable onGranted;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private volatile boolean closed;
    private volatile boolean granted;
    private volatile boolean busy;
    private volatile String approvedIdentity;
    private volatile long lastOnlineCheckElapsed;
    // Requests stay visible in Main. An awaiting app retries without a button press.
    private final Runnable pendingApprovalPoll = () -> {
        if (!closed && !granted) verifyAccess();
    };

    private final Runnable leaseWatch = new Runnable() {
        @Override public void run() {
            if (closed || !granted || activity.isFinishing()) return;
            String id = approvedIdentity;
            if (id == null || !cachedLeaseValid(id)) {
                expireSession("Offline-toestemming verlopen. Verbind met internet om opnieuw te controleren.");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastOnlineCheckElapsed >= 15L * 60L * 1000L && !busy) {
                lastOnlineCheckElapsed = now;
                revalidateInBackground(id);
            }
            main.postDelayed(this, 30000L);
        }
    };

    FamilyLicenseGate(Activity activity, String scope, Runnable onGranted) {
        this.activity = activity;
        this.scope = scope;
        this.onGranted = onGranted;
        this.prefs = activity.getSharedPreferences("family_license_v1", Context.MODE_PRIVATE);
    }

    void begin() {
        showStatus("Licentie controleren…", false);
        verifyAccess();
    }

    /** An applicant name is required only when requesting access, never inferred from hardware. */
    static String normalizeApplicantName(String raw) {
        if (raw == null) return "";
        String name = raw.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 80) return "";
        for (int n = 0; n < name.length(); n++) {
            if (Character.isISOControl(name.charAt(n))) return "";
        }
        return name;
    }

    private void askApplicantName() {
        main.post(() -> {
            if (closed || granted || activity.isFinishing() || activity.isDestroyed()) return;
            main.removeCallbacks(pendingApprovalPoll);
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setPadding(32, 32, 32, 32);

            TextView title = new TextView(activity);
            title.setText("The One — toegang aanvragen");
            title.setTextSize(21f);
            title.setGravity(Gravity.CENTER);
            layout.addView(title);

            TextView description = new TextView(activity);
            description.setText("Wie vraagt toegang tot deze app? Vul je naam in. De eigenaar ziet deze naam bij de goedkeuringsaanvraag in Main.");
            description.setTextSize(16f);
            description.setPadding(0, 18, 0, 12);
            layout.addView(description);

            EditText nameInput = new EditText(activity);
            nameInput.setSingleLine(true);
            nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            nameInput.setHint("Jouw naam");
            nameInput.setText(prefs.getString("applicant_name", ""));
            layout.addView(nameInput);

            Button submit = new Button(activity);
            submit.setText("Toegang aanvragen");
            submit.setOnClickListener(v -> {
                String applicant = normalizeApplicantName(nameInput.getText().toString());
                if (applicant.isEmpty()) {
                    nameInput.setError("Vul je naam in (2 tot 80 tekens)");
                    nameInput.requestFocus();
                    return;
                }
                // Only the applicant name is stored locally; no PIN or owner credential.
                prefs.edit().putString("applicant_name", applicant).apply();
                showStatus("Aanvraag voor " + applicant + " versturen…", false);
                main.postDelayed(this::verifyAccess, 150L);
            });
            layout.addView(submit);
            activity.setContentView(layout);
            nameInput.requestFocus();
        });
    }


    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private KeyPair identity() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(SIGNING_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(SIGNING_ALIAS, null);
        return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
    }

    private String installationId(byte[] der) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder hex = new StringBuilder("fam-");
        for (int i = 0; i < 24; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
        return hex.toString();
    }

    private JSONObject proof(String action, KeyPair pair, String deviceId) throws Exception {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String encodedNonce = encode(nonce);
        String message = action + "|" + deviceId + "|" + scope + "|" + encodedNonce;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign((PrivateKey) pair.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("device_id", deviceId)
            .put("package", activity.getPackageName())
            .put("nonce", encodedNonce)
            .put("signature", encode(signer.sign()));
    }

    private JSONObject post(String action, JSONObject data) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT + action).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] json = data.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) { out.write(json); }
            int status = conn.getResponseCode();
            if (status == 400 || status == 401 || status == 403 || status == 404 || status == 409)
                throw new RejectedException("Registratie of toestemming geweigerd (HTTP " + status + ")");
            if (status != 200) throw new IOException("Licentieserver HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString());
            if (!response.optBoolean("ok")) throw new RejectedException("Licentieantwoord ongeldig");
            return response;
        } finally { conn.disconnect(); }
    }

    private static final class RejectedException extends Exception {
        RejectedException(String message) { super(message); }
    }

    private int bootCount() {
        try { return Settings.Global.getInt(activity.getContentResolver(), Settings.Global.BOOT_COUNT); }
        catch (Exception ignored) { return -1; }
    }

    private SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(CACHE_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(CACHE_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(CACHE_ALIAS, null);
    }

    private void saveLease(String identity) throws Exception {
        JSONObject lease = new JSONObject()
            .put("id", identity).put("package", activity.getPackageName())
            .put("scope", scope).put("boot", bootCount())
            .put("elapsed", SystemClock.elapsedRealtime());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        byte[] ciphertext = cipher.doFinal(lease.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        prefs.edit().putString("lease", encode(result)).apply();
    }

    private boolean cachedLeaseValid(String identity) {
        try {
            String encoded = prefs.getString("lease", "");
            if (encoded == null || encoded.isEmpty()) return false;
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length < 29) return false;
            byte[] iv = java.util.Arrays.copyOfRange(bytes, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(bytes, 12, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            JSONObject lease = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            long saved = lease.optLong("elapsed", -1L);
            long now = SystemClock.elapsedRealtime();
            int boot = bootCount();
            return FamilyLeaseClock.isValid(now, saved, boot, lease.optInt("boot", -2)) &&
                identity.equals(lease.optString("id")) &&
                activity.getPackageName().equals(lease.optString("package")) &&
                scope.equals(lease.optString("scope"));
        } catch (Exception ignored) { return false; }
    }

    private void clearLease() {
        prefs.edit().remove("lease").apply();
    }

    private void verifyAccess() {
        if (busy || closed) return;
        busy = true;
        worker.execute(() -> {
            try {
                KeyPair pair = identity();
                String id = installationId(pair.getPublic().getEncoded());
                String applicantName = normalizeApplicantName(prefs.getString("applicant_name", ""));
                // Existing approved users retain access. Names are required only for a
                // new/pending request; previously authorized installations are unchanged.
                if (applicantName.isEmpty()) {
                    try {
                        JSONObject existing = post("family_check", proof("check", pair, id));
                        if (existing.optBoolean("allowed", false)) {
                            saveLease(id);
                            unlock(id);
                        } else if (existing.optBoolean("pending", false)) {
                            askApplicantName();
                        } else {
                            showStatus("De aanvraag is geweigerd of het apparaat is geblokkeerd.", true);
                        }
                    } catch (RejectedException missing) {
                        if (missing.getMessage().contains("HTTP 404")) askApplicantName();
                        else showStatus(missing.getMessage(), true);
                    } catch (Exception network) {
                        if (cachedLeaseValid(id)) unlock(id);
                        else showStatus("Geen netwerkverbinding. Verbind met internet om toegang aan te vragen.", true);
                    }
                    return;
                }
                try {
                    JSONObject registration = proof("register", pair, id);
                    registration.put("public_key", encode(pair.getPublic().getEncoded()));
                    registration.put("person_name", applicantName);
                    registration.put("platform", "Android " + Build.VERSION.RELEASE);
                    registration.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
                    post("family_register", registration);
                    JSONObject status = post("family_check", proof("check", pair, id));
                    if (status.optBoolean("allowed", false)) {
                        saveLease(id);
                        unlock(id);
                    } else {
                        clearLease();
                        boolean pending = status.optBoolean("pending", false);
                        showStatus(pending
                            ? "Toestemming aangevraagd. Wacht op goedkeuring in Main. We controleren automatisch elke 30 seconden."
                            : "Geen toegang. Vraag de eigenaar om toestemming.", true, pending);
                    }
                } catch (RejectedException rejected) {
                    clearLease();
                    showStatus(rejected.getMessage(), true);
                } catch (Exception connectionError) {
                    if (cachedLeaseValid(id)) unlock(id);
                    else showStatus("Geen geldige licentie. Maak verbinding met internet om toestemming te controleren.", true);
                }
            } catch (Exception error) {
                showStatus("Apparaatbeveiliging is niet beschikbaar. Toegang geweigerd.", true);
            } finally { busy = false; }
        });
    }

    private void unlock(String id) {
        main.post(() -> {
            main.removeCallbacks(pendingApprovalPoll);
            if (closed || activity.isFinishing() || activity.isDestroyed() || granted) return;
            approvedIdentity = id;
            lastOnlineCheckElapsed = SystemClock.elapsedRealtime();
            granted = true;
            onGranted.run();
            main.removeCallbacks(leaseWatch);
            main.postDelayed(leaseWatch, 30000L);
        });
    }

    private void revalidateInBackground(String id) {
        if (busy || closed) return;
        busy = true;
        worker.execute(() -> {
            try {
                KeyPair pair = identity();
                JSONObject status = post("family_check", proof("check", pair, id));
                if (status.optBoolean("allowed", false)) {
                    saveLease(id);
                    lastOnlineCheckElapsed = SystemClock.elapsedRealtime();
                } else {
                    clearLease();
                    expireSession("Toestemming is ingetrokken. De app wordt afgesloten.");
                }
            } catch (RejectedException rejected) {
                clearLease();
                expireSession("Toegang geweigerd door de licentieserver.");
            } catch (Exception unavailable) {
                if (!cachedLeaseValid(id)) expireSession("Offline-toestemming verlopen.");
            } finally { busy = false; }
        });
    }

    private void expireSession(String reason) {
        main.post(() -> {
            if (closed || activity.isFinishing() || activity.isDestroyed()) return;
            android.widget.Toast.makeText(activity, reason, android.widget.Toast.LENGTH_LONG).show();
            activity.finish();
        });
    }

    private void showStatus(String message, boolean retry) {
        showStatus(message, retry, false);
    }

    private void showStatus(String message, boolean retry, boolean autoPoll) {
        main.post(() -> {
            main.removeCallbacks(pendingApprovalPoll);
            if (closed || granted || activity.isFinishing() || activity.isDestroyed()) return;
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setPadding(32, 32, 32, 32);
            TextView text = new TextView(activity);
            text.setText("The One — toegang\n\n" + message);
            text.setTextSize(19f);
            text.setGravity(Gravity.CENTER);
            layout.addView(text);
            if (retry) {
                Button button = new Button(activity);
                button.setText("Toestemming opnieuw controleren");
                button.setOnClickListener(v -> {
                    main.removeCallbacks(pendingApprovalPoll);
                    showStatus("Licentie controleren…", false);
                    verifyAccess();
                });
                layout.addView(button);
            }
            activity.setContentView(layout);
            if (autoPoll) main.postDelayed(pendingApprovalPoll, 30000L);
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacks(leaseWatch);
        main.removeCallbacks(pendingApprovalPoll);
        main.removeCallbacksAndMessages(null);
    }
}
