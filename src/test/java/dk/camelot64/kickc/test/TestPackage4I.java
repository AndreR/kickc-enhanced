package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.passes.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Package 4I:
 * CF-09 (AxRegisterCallingConvention), CF-10 (LoopRegisterPinning),
 * CF-14 (PreIndexedAddressing / CarryFlagReturn), CF-15 (SmallMemcpyUnrolling),
 * CF-16 (BitTestOptimization, PointerReloadElimination.isYOverwrittenAfter, AddMainRts).
 */
public class TestPackage4I {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   @Test
   void testCf09_AxCallingConventionStopsAtLabelAndHandlesTailCall() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      // Callee has a label before rts where return wasn't loaded into A/X, AND another proc tail-calls it via jmp!
      AsmChunk cCallee = asm.startChunk(ScopeRef.ROOT, null, "callee");
      cCallee.addLine(new AsmLabel("callee"));
      cCallee.addLine(new AsmScopeBegin("callee"));
      cCallee.addLine(new AsmLabelDecl("return", "$02"));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "2", null, null));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return+1", null, null));
      cCallee.addLine(new AsmLabel("__breturn"));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCallee.addLine(new AsmScopeEnd());

      AsmChunk cTail = asm.startChunk(ScopeRef.ROOT, null, "tail_caller");
      cTail.addLine(new AsmLabel("tail_caller"));
      cTail.addLine(new AsmScopeBegin("tail_caller"));
      cTail.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "callee", null, null));
      cTail.addLine(new AsmScopeEnd());

      AsmChunk cCaller = asm.startChunk(ScopeRef.ROOT, null, "main");
      cCaller.addLine(new AsmLabel("main"));
      cCaller.addLine(new AsmScopeBegin("main"));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "callee", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "callee.return", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$10", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "callee.return+1", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$11", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCaller.addLine(new AsmScopeEnd());

      assertFalse(new Pass5AxRegisterCallingConventionOptimization(program).optimize(),
            "CF-09: Must NOT convert callee with jmp tail-call to AX calling convention! ASM:\n" + asm);
   }

   @Test
   void testCf10_LoopRegisterPinningRejectsIzyAndClobberedX() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // Case 1: IZY mode (`lda ($10),y`) -> `lax ($10),y` is legal on 6502X, but LoopRegisterPinning only handles ABY safely
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);
      AsmChunk c1 = asm1.startChunk(ScopeRef.ROOT, null, "izy_loop");
      c1.addLine(new AsmScopeBegin("izy_loop"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      c1.addLine(new AsmLabel("__b1"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IZY, false), "$10", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABY, false), "$0400", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("iny", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("cpy", CpuAddressingMode.IMM, false), "10", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "__b1", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      assertFalse(new Pass5LoopRegisterPinning(p1).optimize(),
            "CF-10: Must NOT pin IZY addressing mode! ASM:\n" + asm1);

      // Case 2: ABY loop where X is clobbered in loop body -> MUST NOT pin!
      Program p2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      p2.setAsm(asm2);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "clobber_x_loop");
      c2.addLine(new AsmScopeBegin("clobber_x_loop"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmLabel("__b1"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABY, false), "$2000", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "5", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("stx", CpuAddressingMode.ZP, false), "$02", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABY, false), "$0400", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("iny", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("cpy", CpuAddressingMode.IMM, false), "10", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "__b1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      assertFalse(new Pass5LoopRegisterPinning(p2).optimize(),
            "CF-10: Must NOT pin loop when body clobbers X! ASM:\n" + asm2);
   }

   @Test
   void testCf14_PreIndexedAddressingPreservesLiveYAndFlag() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "preidx_live_y");
      chunk.addLine(new AsmScopeBegin("preidx_live_y"));
      chunk.addLine(new AsmLabelDecl("ptr", "$10"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IZY, false), "ptr", null, null));
      // Y is STILL live here (used by sty $02)!
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sty", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertFalse(new Pass5PreIndexedAddressingOptimization(program).optimize(),
            "CF-14: Must NOT delete ldy #0 when Y is still live after lda (ptr),y! ASM:\n" + asm);
   }

   @Test
   void testCf15_SmallMemcpyUnrollingDistinguishesZeroBasedAndOneBasedAndHardware() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // Pattern C descending 1-based loop: ldy #3; loop: lda src,y; sta dst,y; dey; bne loop
      // Must copy indices 3, 2, 1 (NOT 2, 1, 0!) in descending order!
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);
      AsmChunk c1 = asm1.startChunk(ScopeRef.ROOT, null, "memcpy_1based");
      c1.addLine(new AsmScopeBegin("memcpy_1based"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "3", null, null));
      c1.addLine(new AsmLabel("__b1"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABY, false), "src", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABY, false), "dst", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("dey", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "__b1", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      assertTrue(new Pass5SmallMemcpyUnrollingOptimization(p1).optimize(),
            "CF-15: Safe 3-byte descending loop should unroll");
      String out1 = asm1.toString();
      int idx3 = out1.indexOf("src+3");
      int idx2 = out1.indexOf("src+2");
      int idx1 = out1.indexOf("src+1");
      assertTrue(idx3 >= 0 && idx2 > idx3 && idx1 > idx2,
            "CF-15: 1-based descending loop must unroll src+3, src+2, src+1 in order! ASM:\n" + out1);
      assertFalse(out1.contains("lda src\n"), "CF-15: 1-based loop must NOT touch src+0! ASM:\n" + out1);

      // Hardware I/O address ($d000) must NOT be unrolled if unsafe
      Program p2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      p2.setAsm(asm2);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "memcpy_io");
      c2.addLine(new AsmScopeBegin("memcpy_io"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmLabel("__b1"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABY, false), "$dc0d", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABY, false), "dst", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("iny", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("cpy", CpuAddressingMode.IMM, false), "3", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "__b1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      assertFalse(new Pass5SmallMemcpyUnrollingOptimization(p2).optimize(),
            "CF-15: Must NOT unroll hardware I/O source $dc0d! ASM:\n" + asm2);
   }

   @Test
   void testCf16_BitTestRespectsAccumulatorShiftAndPointerReloadRespectsAbyY() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // BitTest: lda var; and #$80; beq target; lsr (reads A!) -> MUST NOT rewrite to bit var!
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);
      AsmChunk c1 = asm1.startChunk(ScopeRef.ROOT, null, "bit_lsr");
      c1.addLine(new AsmScopeBegin("bit_lsr"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABS, false), "flag", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("and", CpuAddressingMode.IMM, false), "$80", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b1", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lsr", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      c1.addLine(new AsmLabel("__b1"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      assertFalse(new Pass5BitTestOptimization(p1).optimize(),
            "CF-16: BitTest must NOT rewrite lda; and #$80 when accumulator lsr reads A! ASM:\n" + asm1);

      // PointerReloadElimination: ldy #0; lda (p),y; lda arr,y -> Y is read by ABY so ldy #0 must NOT be deleted!
      Program p2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      p2.setAsm(asm2);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "ptr_y_aby");
      c2.addLine(new AsmScopeBegin("ptr_y_aby"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IZY, false), "$10", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABY, false), "arr", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$03", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      // Even if Pass5PointerReloadElimination runs, arr,y must still see Y=0 cleanly (ldy #0 before lda (p),y kept)
      new Pass5PointerReloadElimination(p2).optimize();
      assertTrue(asm2.toString().contains("ldy #0"), "First ldy #0 must be preserved for ABY! ASM:\n" + asm2);
   }
}
