package com.nectariich.create_flippies.content.circuit_breaker;

/**
 * How many poles a breaker breaks, which is also how many modules of rail it
 * takes up: DIN gear is a pole to a module and the whole standard is built on it.
 *
 * The poles are ganged. Every export puts one bar across all of its levers, so
 * there is one moving part however many poles are under it, and a breaker is
 * either made or not — never made on one line and open on another. An overload on
 * any one pole therefore opens all of them, which is what a real multi-pole MCB
 * does and the reason it exists: a motor that has lost one phase is worse off
 * than one that has lost all three.
 */
public enum CircuitBreakerPoles {

    SP(1),
    DP(2),
    TP(3);

    private final int poles;

    CircuitBreakerPoles(int poles) {
        this.poles = poles;
    }

    public int poles() {
        return poles;
    }

    /** One module to a pole, so also the breaker's width in pixels. */
    public int modules() {
        return poles;
    }

    /** Two per pole: a line at the top of the breaker and a load at the bottom. */
    public int terminalCount() {
        return poles * 2;
    }

    /** The {@code 1m_sp} part of an id, matching what the exports are called. */
    public String suffix() {
        return modules() + "m_" + name().toLowerCase();
    }
}
