package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Backend;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relaxation loop for variable-length backends (x86, 68000 later), with a toy backend:
 * {@code jmp target} is 2 bytes when the target is within +-100 bytes, else 4.
 */
class RelaxationTest {

    private static final class ToyBackend implements IsaBackend {
        @Override public LexerSyntax syntax() { return Rv32Backend.INSTANCE.syntax(); }
        @Override public ByteOrder byteOrder() { return ByteOrder.LITTLE_ENDIAN; }
        @Override public int instructionAlignment() { return 1; }
        @Override public byte[] nopPadding() { return new byte[] {(byte) 0x90}; }
        @Override public boolean alignIsPowerOfTwo() { return true; }
        @Override public boolean relaxes() { return true; }

        @Override
        public int size(Statement st, AsmContext ctx) {
            if (st.mnemonic().equals("nop"))
                return 1;
            if (!st.mnemonic().equals("jmp"))
                throw new AsmError(st.column(), "unknown instruction '" + st.mnemonic() + "'");
            OptionalLong t = ctx.known(st.operand(0).expr());
            if (t.isEmpty())
                return 2;   // optimistic until the target is placed
            long d = t.getAsLong() - ctx.dot();
            return d >= -100 && d <= 100 ? 2 : 4;
        }

        @Override
        public void encode(Statement st, AsmContext ctx, Emitter out) {
            if (st.mnemonic().equals("nop")) {
                out.u8(0x90);
                return;
            }
            long d = ctx.evalAbs(st.operand(0).expr()) - ctx.dot();
            if (st.size() == 2) {
                out.u8(0xEB);
                out.u8((int) d);
            } else {
                out.u8(0xE9);
                out.u8((int) d);
                out.u16((int) (d >> 8));
            }
        }

        @Override
        public OptionalLong relocationOperator(String name, Expr arg, AsmContext ctx) {
            return OptionalLong.empty();
        }
    }

    @Test
    void sizesGrowUntilStable() {
        AsmResult r = new Assembler(new ToyBackend(), AsmOptions.at(0x100)).assemble(List.of(new AsmSource("t.s", """
                a:  jmp far
                    jmp near
                near:
                    .space 97
                far: jmp a
                """)));
        assertTrue(r.ok(), r::toString);
        AsmImage img = r.image();
        // The first jmp needs 4 bytes (far is 4 + 2 + 97 = 103 away); the second stays short;
        // the last one (103 back) also grows.
        assertEquals(0x106, img.symbols().get("near"));
        assertEquals(0x167, img.symbols().get("far"));
        byte[] b = img.segments().get(0).bytes();
        assertEquals(0x167 + 4 - 0x100, b.length);
        assertEquals(0xE9, b[0] & 0xFF);
        assertEquals(0xEB, b[4] & 0xFF);
        assertEquals(2, b[5]);
        assertEquals(0xE9, b[0x67] & 0xFF);
    }
}
