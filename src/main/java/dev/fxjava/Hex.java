package dev.fxjava;

/** Java 11-compatible lowercase hexadecimal encoding. */
final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() { }

    static String encode(byte[] bytes) {
        return encode(bytes, 0, bytes.length);
    }

    static String encode(byte[] bytes, int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < fromIndex || toIndex > bytes.length) {
            throw new IndexOutOfBoundsException("range [" + fromIndex + ", " + toIndex
                    + ") for length " + bytes.length);
        }
        char[] encoded = new char[(toIndex - fromIndex) * 2];
        int output = 0;
        for (int index = fromIndex; index < toIndex; index++) {
            int value = bytes[index] & 0xff;
            encoded[output++] = DIGITS[value >>> 4];
            encoded[output++] = DIGITS[value & 0x0f];
        }
        return new String(encoded);
    }
}
