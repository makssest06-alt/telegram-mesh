package org.telegram.messenger.mesh;

import android.util.Base64;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MeshCryptManager {

    private static final String TAG = "MeshCrypt";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String X25519 = "X25519";
    private static final String SHA256 = "SHA-256";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_SIZE = 12;
    private static final SecureRandom secureRandom = new SecureRandom();

    private final MeshConfig config;

    public MeshCryptManager(MeshConfig config) {
        this.config = config;
    }

    public static byte[] generateSharedSecret(PrivateKey privateKey, PublicKey publicKey) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance(X25519);
            ka.init(privateKey);
            ka.doPhase(publicKey, true);
            return ka.generateSecret();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Key agreement failed", e);
        }
    }

    public byte[] computeSessionKey(byte[] sharedSecret, byte[] salt) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance(SHA256);
            sha256.update(salt);
            sha256.update(sharedSecret);
            return sha256.digest("mesh-e2ee-key".getBytes());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public byte[] encryptMessage(byte[] plaintext, byte[] sessionKey, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(sessionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] tag = new byte[GCM_TAG_LENGTH / 8];
            System.arraycopy(ciphertext, ciphertext.length - tag.length, tag, 0, tag.length);
            return ciphertext;
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encryption failed", e);
        }
    }

    public byte[] decryptMessage(byte[] ciphertext, byte[] sessionKey, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(sessionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decryption failed", e);
        }
    }

    public byte[] hmacSha256(byte[] data, byte[] key) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_SHA256);
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(keySpec);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    public MeshMessage encryptMeshMessage(MeshMessage msg, byte[] sessionKey) {
        try {
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);
            byte[] plainPayload = msg.getPayload();
            byte[] ciphertext = encryptMessage(plainPayload, sessionKey, iv);
            byte[] hmac = hmacSha256(ciphertext, sessionKey);
            msg.setEncryptedPayload(ciphertext);
            msg.setIv(iv);
            msg.setHmac(hmac);
            msg.setPayload(null);
            msg.setE2ee(true);
            return msg;
        } catch (Exception e) {
            throw new RuntimeException("Mesh encryption failed", e);
        }
    }

    public MeshMessage decryptMeshMessage(MeshMessage msg, byte[] sessionKey) {
        try {
            byte[] iv = msg.getIv();
            byte[] ciphertext = msg.getEncryptedPayload();
            byte[] expectedHmac = hmacSha256(ciphertext, sessionKey);
            if (!MessageDigest.isEqual(expectedHmac, msg.getHmac())) {
                throw new SecurityException("HMAC mismatch — message tampered");
            }
            byte[] plaintext = decryptMessage(ciphertext, sessionKey, iv);
            msg.setPayload(plaintext);
            msg.setEncryptedPayload(null);
            msg.setIv(null);
            msg.setHmac(null);
            return msg;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Mesh decryption failed", e);
        }
    }
}
