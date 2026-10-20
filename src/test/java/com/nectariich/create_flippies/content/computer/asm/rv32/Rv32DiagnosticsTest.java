package com.nectariich.create_flippies.content.computer.asm.rv32;

import com.nectariich.create_flippies.content.computer.asm.AsmDiagnostic;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.asm;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.errors;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.oneError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Range checks and operand errors, with their lines, columns and messages (spec 5.1, 8.2). */
class Rv32DiagnosticsTest {

    private static void at(AsmDiagnostic d, int line, int column, String message) {
        assertEquals(line, d.line(), d::toString);
        assertEquals(column, d.column(), d::toString);
        assertEquals(message, d.message());
        assertEquals("<source>", d.fileName());
        assertEquals(Severity.ERROR, d.severity());
    }

    @Test
    void branchOutOfRange() {
        at(oneError("beq a0, a1, 5000", 1, ""), 1, 13, "branch target out of range (5000 bytes, limit +-4096)");
        at(oneError("nop\n  beq a0, a1, far\n.space 4996\nfar:", 2, ""), 2, 15,
                "branch target out of range (5000 bytes, limit +-4096)");
        oneError("bnez a0, -4098", 1, "branch target out of range (-4098 bytes");
        oneError("beq a0, a1, 3", 1, "branch target offset 3 is odd");
    }

    @Test
    void branchNotMultipleOfFourWarns() {
        AsmResult r = asm("beq a0, a1, 6");
        assertTrue(r.ok());
        assertEquals(1, r.diagnostics().size());
        assertEquals(Severity.WARNING, r.diagnostics().get(0).severity());
        assertTrue(r.diagnostics().get(0).message().contains("not a multiple of 4"));
    }

    @Test
    void jumpOutOfRange() {
        oneError("jal 0x100000", 1, "jump target out of range (1048576 bytes, limit +-1048576)");
        oneError("j -1048578", 1, "jump target out of range");
        oneError("j far\n.space 0x100000\nfar:", 1, "jump target out of range (1048580 bytes");
    }

    @Test
    void immediateRanges() {
        at(oneError("addi a0, a0, 4096", 1, ""), 1, 14, "immediate 4096 does not fit in 12 signed bits");
        oneError("addi a0, a0, -2049", 1, "immediate -2049 does not fit in 12 signed bits");
        oneError("lw a0, 2048(sp)", 1, "immediate 2048 does not fit in 12 signed bits");
        oneError("sw a0, -2049(sp)", 1, "immediate -2049 does not fit");
        at(oneError("slli a0, a0, 32", 1, ""), 1, 14, "shift amount 32 out of range (0..31)");
        oneError("srai a0, a0, -1", 1, "shift amount -1 out of range");
        oneError("lui a0, 0x100000", 1, "does not fit in 20 bits");
        oneError("lui a0, -1", 1, "does not fit in 20 bits");
        oneError("csrrwi a0, mstatus, 32", 1, "immediate 32 out of range (0..31)");
        oneError("csrr a0, 4096", 1, "CSR number 4096 out of range (0..4095)");
        oneError("csrr a0, nosuchcsr", 1, "unknown CSR 'nosuchcsr'");
        oneError(".byte 256", 1, "value 256 does not fit in 1 byte");
        oneError(".half -32769", 1, "value -32769 does not fit in 2 bytes");
        oneError(".word 0x100000000", 1, "does not fit in 4 bytes");
    }

    @Test
    void operandErrors() {
        at(oneError("add a0, a1, x32", 1, ""), 1, 13, "unknown register 'x32'");
        at(oneError("add a0, a1", 1, ""), 1, 1, "'add' expects 3 operands, got 2");
        at(oneError("   ad a0, a1, a2", 1, ""), 1, 4, "unknown instruction 'ad'");
        oneError("add a0, a1, 5", 1, "expected a register, got '5'");
        oneError("add a0, , a1", 1, "missing operand");
        oneError("sw a0, a1", 1, "expected a memory operand");
        oneError("ret a0", 1, "'ret' expects 0 operands, got 1");
        oneError("fence rx, w", 1, "bad fence operand 'rx'");
        oneError("addi a0, a0, (1", 1, "missing ')'");
        oneError(".bss\nnop", 2, "instructions are not allowed in .bss");
        oneError(".option rvc", 1, "C extension not supported");
    }

    @Test
    void symbolErrors() {
        at(oneError("j loop2", 1, ""), 1, 3, "undefined symbol 'loop2'");
        AsmDiagnostic d = oneError("x: nop\nnop\nnop\nx: nop", 4, "symbol 'x' already defined on line 1");
        assertEquals(1, d.column());
        oneError(".equ a, 1\n.equ a, 2", 2, "symbol 'a' already defined on line 1");
        oneError("b: nop\n.set b, 2", 2, "already defined on line 1");
        oneError("bnez a0, 1b", 1, "no preceding local label 1");
        oneError("bnez a0, 1f", 1, "no following local label 1");
        oneError("__bss_end: nop", 1, "predefined");
        assertTrue(errors(".equ a, b\n.equ b, a\n.word a").stream()
                .allMatch(x -> x.message().startsWith("circular definition of")));
    }

    @Test
    void severalErrorsAllReportedInOrder() {
        List<AsmDiagnostic> e = errors("""
                nop
                addi a0, a0, 5000
                ad x1
                nop
                add a0, a1, x99
                j nowhere
                """);
        assertEquals(4, e.size(), e::toString);
        assertEquals(2, e.get(0).line());
        assertEquals(3, e.get(1).line());
        assertEquals(5, e.get(2).line());
        assertEquals(6, e.get(3).line());
    }

    @Test
    void errorCountIsCapped() {
        String src = "ad x1\n".repeat(150);
        List<AsmDiagnostic> e = errors(src);
        assertEquals(101, e.size());
        assertTrue(e.get(100).message().contains("too many errors"));
    }
}
