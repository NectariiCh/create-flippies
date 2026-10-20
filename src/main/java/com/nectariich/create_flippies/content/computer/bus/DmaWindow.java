package com.nectariich.create_flippies.content.computer.bus;

/**
 * Bytes {@code [offset, offset + length)} of {@code array}, the memory behind a DMA transfer
 * (spec 1.2). A device copies straight into the array and then calls {@code onWrite}, which marks
 * VRAM pages dirty or tells the bus's RAM write listener. Reading out of the window (a disk
 * WRITE) needs no call.
 */
public record DmaWindow(byte[] array, int offset, int length, Runnable onWrite) { }
