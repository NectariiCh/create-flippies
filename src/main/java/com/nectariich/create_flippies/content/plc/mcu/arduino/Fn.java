package com.nectariich.create_flippies.content.plc.mcu.arduino;

import com.nectariich.create_flippies.content.plc.mcu.Builtin;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;

/**
 * One builtin as the core declares it: a prototype, the flash the linker pulls in
 * when a sketch calls it, and the body.
 *
 * @param flashBytes  the code (and constant data) this function brings into the
 *                    image when referenced, on top of {@link ArduinoCore#coreFlashBytes()}
 */
record Fn(String prototype, int flashBytes, Body body) implements Builtin {

    @FunctionalInterface
    interface Body {
        void run(Invocation call);
    }

    @Override
    public void invoke(Invocation call) {
        body.run(call);
    }

    /** The function's name: the identifier just before the parameter list. */
    String name() {
        String head = prototype.substring(0, prototype.indexOf('(')).trim();
        int start = head.length();
        while (start > 0 && (Character.isLetterOrDigit(head.charAt(start - 1)) || head.charAt(start - 1) == '_'))
            start--;
        return head.substring(start);
    }
}
