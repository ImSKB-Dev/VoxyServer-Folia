package com.dripps.voxyserver.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

public class PacketBuffer {
    private final ByteBuf buf;

    public PacketBuffer() {
        this.buf = Unpooled.buffer();
    }

    public PacketBuffer(ByteBuf buf) {
        this.buf = buf;
    }

    public PacketBuffer(byte[] bytes) {
        this.buf = Unpooled.wrappedBuffer(bytes);
    }

    public ByteBuf getBuf() {
        return buf;
    }

    public void writeVarInt(int value) {
        while ((value & -128) != 0) {
            buf.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public int readVarInt() {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = buf.readByte();
            value |= (currentByte & 127) << position;
            if ((currentByte & 128) == 0) break;
            position += 7;
            if (position >= 32) throw new RuntimeException("VarInt is too big");
        }
        return value;
    }

    public void writeUtf(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public String readUtf() {
        int len = readVarInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void writeLong(long value) {
        buf.writeLong(value);
    }

    public long readLong() {
        return buf.readLong();
    }

    public void writeByte(int value) {
        buf.writeByte(value);
    }

    public byte readByte() {
        return buf.readByte();
    }

    public void writeBoolean(boolean value) {
        buf.writeBoolean(value);
    }

    public boolean readBoolean() {
        return buf.readBoolean();
    }

    public void writeBytes(byte[] bytes) {
        buf.writeBytes(bytes);
    }

    public void readBytes(byte[] bytes) {
        buf.readBytes(bytes);
    }

    public byte[] toArray() {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    public int readableBytes() {
        return buf.readableBytes();
    }

    public void release() {
        if (buf.refCnt() > 0) {
            buf.release();
        }
    }
}
