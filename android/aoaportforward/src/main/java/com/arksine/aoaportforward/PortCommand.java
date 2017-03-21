package com.arksine.aoaportforward;

import java.nio.ByteBuffer;

/**
 * Created by Eric on 3/19/2017.
 */

public enum PortCommand {
    NONE(new byte[]{(byte)0x00, (byte)0x00}),
    CONNECT_SOCKET(new byte[]{(byte)0x01, (byte)0x01}),
    DISCONNECT_SOCKET(new byte[]{(byte)0x02, (byte)0x01}),
    DATA_PACKET(new byte[]{(byte)0x03, (byte)0x01}),
    ACCESSORY_CONNECTED(new byte[]{(byte)0x04, (byte)0x01}),
    TERMINATE_ACCESSORY(new byte[]{(byte)0x05, (byte)0x0F});

    private static final PortCommand[] COMMAND_ARRAY = PortCommand.values();
    private final byte[] mBytes;

    PortCommand(byte[] b) {
        this.mBytes = b;
    }

    public byte[] getBytes() {
        return mBytes;
    }

    public short getValue() {
        return ByteBuffer.wrap(mBytes).getShort();
    }

    public static PortCommand getCommandFromOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= COMMAND_ARRAY.length) {
            return PortCommand.NONE;
        } else {
            return COMMAND_ARRAY[ordinal];
        }
    }

    public static PortCommand getCommandFromValue(short value) {
        for (PortCommand cmd : COMMAND_ARRAY) {
            if (cmd.getValue() == value) {
                return cmd;
            }
        }

        // Not found
        return PortCommand.NONE;
    }

    public static PortCommand getCommandFromBytes(byte[] bytes) {
        if (bytes.length != 2) {
            return PortCommand.NONE;
        } else {
            return getCommandFromValue(ByteBuffer.wrap(bytes).getShort());
        }
    }
}
