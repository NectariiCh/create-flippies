package com.nectariich.create_flippies.content.plc.mcu;

/**
 * What one MCU pin did over the last run window, as the board's output stage sees it.
 *
 * @param output        whether the pin was configured as an output (push-pull,
 *                      open-drain or alternate-function PWM) at the end of the window
 * @param levelAtEnd    the level it was driving at the end of the window
 * @param highFraction  the fraction of the window, 0..1, it spent driving high,
 *                      cycle-weighted over the whole window, so hardware PWM, tone()
 *                      and bit-banging all come out as their real average
 * @param pwmHz         the frequency of hardware PWM or tone() on the pin at the end
 *                      of the window, or 0 if it was being driven statically
 */
public record PinDrive(boolean output, boolean levelAtEnd, double highFraction, double pwmHz) {

    public static final PinDrive OFF = new PinDrive(false, false, 0, 0);
}
