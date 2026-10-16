package com.nectariich.create_flippies.content.plc;

import com.nectariich.create_flippies.content.plc.mcu.McuState;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Everything the menu's live pane shows about one PLC, as the server saw it on its
 * last tick.
 *
 * A snapshot rather than synced state because nearly all of it changes every tick —
 * CPU load, power, output duty — and none of it is wanted by anyone who does not have
 * the menu open. So it is sent to one player, on request, while their menu is open,
 * and never touches the host's synced tag. See {@code PlcActionPacket}.
 *
 * Arrays are per channel in {@link PlcBoard}'s {@code CH_} order (DI0..7, DO0..7,
 * STLED, RESET), except {@link #duty}, which is per output stage (DO0..7, STLED).
 *
 * @param state          what the MCU is doing
 * @param fault          the HardFault's kind, or empty
 * @param faultLine      the sketch line it faulted on, or 0
 * @param uptimeMillis   emulated time since the last reset
 * @param cpuLoad        fraction of the last window not spent waiting
 * @param speed          emulated time per real time over the last window
 * @param supplyVolts    VCC above GND
 * @param supply         where that sits against the operating range
 * @param hasProgram     whether an image is flashed
 * @param flashBytes     the image's size, or 0
 * @param staticRamBytes its .data and .bss, or 0
 * @param stackPeak      peak stack since reset
 * @param heapPeak       peak heap since reset
 * @param power          the board's power budget
 * @param inputsHigh     one bit per DI front end that reads ON at its terminal
 * @param resetAsserted  whether RESET holds the MCU
 * @param faults         one bit per latched channel
 * @param duty           per output stage
 * @param amps           per channel, through its wire
 * @param watts          per channel: front-end dissipation for an input, load
 *                       power plus driver loss for an output
 * @param compiling      whether a compile is in flight for this PLC
 * @param notRestored    whether the PLC was left blank against its owner's wishes: the
 *                       saved program failed to compile at load or was too long, or the
 *                       emulator threw
 * @param restoreError   why — the compiler's first error, or the reason — or empty
 */
public record PlcStatus(McuState state, String fault, int faultLine, long uptimeMillis, double cpuLoad, double speed,
        double supplyVolts, PlcBoard.SupplyCondition supply, boolean hasProgram, int flashBytes, int staticRamBytes,
        int stackPeak, int heapPeak, PlcBoard.Power power, int inputsHigh, boolean resetAsserted, int faults,
        float[] duty, float[] amps, float[] watts, boolean compiling, boolean notRestored, String restoreError) {

    /** What the menu shows before the first reply arrives. */
    public static PlcStatus empty() {
        return new PlcStatus(McuState.UNPOWERED, "", 0, 0, 0, 0, 0, PlcBoard.SupplyCondition.OFF, false, 0, 0, 0, 0,
                PlcBoard.Power.NONE, 0, false, 0, new float[PlcBoard.OUTPUTS], new float[PlcBoard.CHANNELS],
                new float[PlcBoard.CHANNELS], false, false, "");
    }

    /** The longest fault or error text carried, which bounds the packet. */
    private static final int MAX_TEXT = 512;

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(state);
        buffer.writeUtf(fault, MAX_TEXT);
        buffer.writeVarInt(faultLine);
        buffer.writeVarLong(uptimeMillis);
        buffer.writeFloat((float) cpuLoad);
        buffer.writeFloat((float) speed);
        buffer.writeFloat((float) supplyVolts);
        buffer.writeEnum(supply);
        buffer.writeBoolean(hasProgram);
        buffer.writeVarInt(flashBytes);
        buffer.writeVarInt(staticRamBytes);
        buffer.writeVarInt(stackPeak);
        buffer.writeVarInt(heapPeak);
        buffer.writeDouble(power.mcu());
        buffer.writeDouble(power.board());
        buffer.writeDouble(power.buckLoss());
        buffer.writeDouble(power.driverQuiescent());
        buffer.writeDouble(power.standby());
        buffer.writeDouble(power.inputs());
        buffer.writeDouble(power.driverLoss());
        buffer.writeDouble(power.delivered());
        buffer.writeVarInt(inputsHigh);
        buffer.writeBoolean(resetAsserted);
        buffer.writeVarInt(faults);
        writeFloats(buffer, duty, PlcBoard.OUTPUTS);
        writeFloats(buffer, amps, PlcBoard.CHANNELS);
        writeFloats(buffer, watts, PlcBoard.CHANNELS);
        buffer.writeBoolean(compiling);
        buffer.writeBoolean(notRestored);
        buffer.writeUtf(restoreError, MAX_TEXT);
    }

    public static PlcStatus decode(FriendlyByteBuf buffer) {
        McuState state = buffer.readEnum(McuState.class);
        String fault = buffer.readUtf(MAX_TEXT);
        int faultLine = buffer.readVarInt();
        long uptime = buffer.readVarLong();
        double cpuLoad = buffer.readFloat();
        double speed = buffer.readFloat();
        double supplyVolts = buffer.readFloat();
        PlcBoard.SupplyCondition supply = buffer.readEnum(PlcBoard.SupplyCondition.class);
        boolean hasProgram = buffer.readBoolean();
        int flashBytes = buffer.readVarInt();
        int staticRam = buffer.readVarInt();
        int stackPeak = buffer.readVarInt();
        int heapPeak = buffer.readVarInt();
        PlcBoard.Power power = new PlcBoard.Power(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble());
        int inputsHigh = buffer.readVarInt();
        boolean resetAsserted = buffer.readBoolean();
        int faults = buffer.readVarInt() & PlcBoard.ALL_CHANNELS;
        float[] duty = readFloats(buffer, PlcBoard.OUTPUTS);
        float[] amps = readFloats(buffer, PlcBoard.CHANNELS);
        float[] watts = readFloats(buffer, PlcBoard.CHANNELS);
        boolean compiling = buffer.readBoolean();
        boolean notRestored = buffer.readBoolean();
        String restoreError = buffer.readUtf(MAX_TEXT);
        return new PlcStatus(state, fault, faultLine, uptime, cpuLoad, speed, supplyVolts, supply, hasProgram,
                flashBytes, staticRam, stackPeak, heapPeak, power, inputsHigh, resetAsserted, faults, duty, amps,
                watts, compiling, notRestored, restoreError);
    }

    /** Truncates text to what {@link #encode} will carry. */
    public static String clip(String text) {
        if (text == null)
            return "";
        return text.length() <= MAX_TEXT / 4 * 3 ? text : text.substring(0, MAX_TEXT / 4 * 3);
    }

    private static void writeFloats(FriendlyByteBuf buffer, float[] values, int count) {
        for (int i = 0; i < count; i++)
            buffer.writeFloat(i < values.length ? values[i] : 0);
    }

    private static float[] readFloats(FriendlyByteBuf buffer, int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++)
            values[i] = buffer.readFloat();
        return values;
    }
}
