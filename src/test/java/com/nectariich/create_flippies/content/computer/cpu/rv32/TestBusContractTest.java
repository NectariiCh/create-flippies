package com.nectariich.create_flippies.content.computer.cpu.rv32;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.BusContractTest;
import com.nectariich.create_flippies.content.computer.bus.BusDevice;

/** The CPU tests' {@link TestBus} obeys the same bus contract as the machine's bus (spec 7.1). */
class TestBusContractTest extends BusContractTest {

    @Override
    protected Bus createBus(Layout layout, BusDevice device) {
        return new TestBus(layout.order(), layout.ramBase(), layout.ramSize(), layout.romBase(), layout.rom(),
                layout.vramBase(), layout.vramSize(), layout.deviceBase(), layout.deviceWindow(), device);
    }
}
