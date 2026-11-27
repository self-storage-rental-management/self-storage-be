package com.storagehub.common.text;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Repairs double-encoded UTF-8 mojibake (for example UTF-8 bytes decoded as Windows-1252
 * and re-encoded as UTF-8, such as "Kho ViÃ¡Â»â€¡t" -> "Kho Việt").
 */
public final class TextEncodingRepair {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TextEncodingRepair() {}

    public static String repairUtf8Mojibake(String value) {
        if (value == null || value.isEmpty() || isAscii(value)) {
            return value;
        }
        String current = value;
        for (int pass = 0; pass < 2; pass++) {
            String decoded = tryDecodePass(current);
            if (decoded == null || decoded.equals(current)) {
                break;
            }
            current = decoded;
        }
        return current;
    }

    private static String tryDecodePass(String value) {
        if (!looksLikeMojibake(value)) {
            return value;
        }
        var encoder = WINDOWS_1252.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(value));
            String decoded = decoder.decode(bytes).toString();
            return decoded.indexOf('\uFFFD') >= 0 ? value : decoded;
        } catch (CharacterCodingException ignored) {
            return value;
        }
    }

    private static boolean looksLikeMojibake(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u00C3' || c == '\u00C2' || c == '\u00C4' || c == '\u00C5'
                || c == '\u00C6' || c == '\u00E2' || c == '\u00BA' || c == '\u00BB') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
