package info.dong4j.idea.plugin.sdk.qcloud.cos.internal.crypto;

import java.security.SecureRandom;

/**
 * COS cryptographic scheme that includes the content crypto scheme and key
 * wrapping scheme (for the content-encrypting-key).
 */
final class COSCryptoScheme {
    static final String AES = "AES"; 
    static final String RSA = "RSA"; 
    private static final SecureRandom srand = new SecureRandom();
    private final COSKeyWrapScheme kwScheme;

    private final ContentCryptoScheme contentCryptoScheme;

    private COSCryptoScheme(ContentCryptoScheme contentCryptoScheme,
            COSKeyWrapScheme kwScheme) {
        this.contentCryptoScheme = contentCryptoScheme;
        this.kwScheme = kwScheme;
    }

    SecureRandom getSecureRandom() { return srand; }
    
    ContentCryptoScheme getContentCryptoScheme() {
        return contentCryptoScheme;
    }

    COSKeyWrapScheme getKeyWrapScheme() { return kwScheme; }

    /**
     * Convenient method.
     */
    static boolean isAesGcm(String cipherAlgorithm) {
        return ContentCryptoScheme.AES_GCM.getCipherAlgorithm().equals(cipherAlgorithm);
    }

    static COSCryptoScheme from(CryptoMode mode) {
        switch (mode) {
        case AuthenticatedEncryption:
        case StrictAuthenticatedEncryption:
            return new COSCryptoScheme(ContentCryptoScheme.AES_GCM,
                    new COSKeyWrapScheme());
        default:
            throw new IllegalStateException();
        }
    }
}