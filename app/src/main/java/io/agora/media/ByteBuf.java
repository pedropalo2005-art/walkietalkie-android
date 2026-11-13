package io.agora.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;

public class ByteBuf {
    private static final int INITIAL_CAPACITY = 1024;
    ByteBuffer buffer = ByteBuffer.allocate(INITIAL_CAPACITY).order(ByteOrder.LITTLE_ENDIAN);

    public ByteBuf() {
    }

    public ByteBuf(byte[] bytes) {
        this.buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    public byte[] asBytes() {
        byte[] out = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(out, 0, out.length);
        return out;
    }

    private void ensureCapacity(int additionalLength) {
        int requiredLength = buffer.position() + additionalLength;
        if (requiredLength <= buffer.capacity()) {
            return;
        }

        int capacity = buffer.capacity();
        while (capacity < requiredLength) {
            capacity *= 2;
        }

        ByteBuffer expanded = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        buffer.flip();
        expanded.put(buffer);
        buffer = expanded;
    }

    public ByteBuf put(short v) {
        ensureCapacity(Short.BYTES);
        buffer.putShort(v);
        return this;
    }

    public ByteBuf put(byte[] v) {
        put((short) v.length);
        ensureCapacity(v.length);
        buffer.put(v);
        return this;
    }

    public ByteBuf put(int v) {
        ensureCapacity(Integer.BYTES);
        buffer.putInt(v);
        return this;
    }

    public ByteBuf put(long v) {
        ensureCapacity(Long.BYTES);
        buffer.putLong(v);
        return this;
    }

    public ByteBuf put(String v) {
        return put(v.getBytes());
    }

    public ByteBuf putIntMap(TreeMap<Short, Integer> extra) {
        put((short) extra.size());

        for (Map.Entry<Short, Integer> pair : extra.entrySet()) {
            put(pair.getKey());
            put(pair.getValue());
        }

        return this;
    }

    public short readShort() {
        return buffer.getShort();
    }

    public int readInt() {
        return buffer.getInt();
    }

    public byte[] readBytes() {
        short length = readShort();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    public String readString() {
        byte[] bytes = readBytes();
        return new String(bytes);
    }

    public TreeMap<Short, Integer> readIntMap() {
        TreeMap<Short, Integer> map = new TreeMap<>();
        short length = readShort();

        for (short i = 0; i < length; ++i) {
            short k = readShort();
            Integer v = readInt();
            map.put(k, v);
        }

        return map;
    }
}
