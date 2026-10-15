package audiogear.db;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Curve points are stored as little-endian float32 arrays: 4 bytes per value, about 4 KB per curve. */
final class Blobs {
    private Blobs() {}

    static byte[] toBytes(double[] values) {
        if (values == null) return null;
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) b.putFloat((float) v);
        return b.array();
    }

    static double[] toDoubles(byte[] bytes) {
        if (bytes == null) return null;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] out = new double[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = b.getFloat();
        return out;
    }
}
