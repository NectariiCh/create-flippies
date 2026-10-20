package com.nectariich.create_flippies.content.computer.bus;

/** The {@link Bus} contract run against the machine's {@link SystemBus}. */
class SystemBusContractTest extends BusContractTest {

    @Override
    protected Bus createBus(Layout layout, BusDevice device) {
        SystemBus bus = new SystemBus(layout.order());
        bus.setRam(new byte[layout.ramSize()], layout.ramBase());
        bus.setRom(layout.rom().clone(), layout.romBase());
        bus.setVram(new byte[layout.vramSize()], layout.vramBase(), new long[2]);
        bus.mapDevice(layout.deviceBase(), layout.deviceWindow(), device);
        return bus;
    }
}
