package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.passes.Pass5InterproceduralParameterPreservation;
import dk.camelot64.kickc.passes.Pass5PointerReloadElimination;
import dk.camelot64.kickc.passes.Pass5UnnecesaryLoadElimination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Package 5B: ZP-01, ZP-02, ZP-03, ZP-04.
 */
public class TestPackage5B {

   @Test
   void testZp01_ForwardCfgMeetEliminatesPointerReloadAtJoinLabel() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      CpuOpcode ldyAbs = cpu.getOpcode("ldy", CpuAddressingMode.ABS, false);
      CpuOpcode styZp = cpu.getOpcode("sty", CpuAddressingMode.ZP, false);
      CpuOpcode ldyImm = cpu.getOpcode("ldy", CpuAddressingMode.IMM, false);
      CpuOpcode ldaImm = cpu.getOpcode("lda", CpuAddressingMode.IMM, false);
      CpuOpcode ldaZp = cpu.getOpcode("lda", CpuAddressingMode.ZP, false);
      CpuOpcode staIzy = cpu.getOpcode("sta", CpuAddressingMode.IZY, false);
      CpuOpcode beqRel = cpu.getOpcode("beq", CpuAddressingMode.REL, false);
      CpuOpcode rtsNon = cpu.getOpcode("rts", CpuAddressingMode.NON, false);

      chunk.addLine(new AsmInstruction(ldyAbs, "p", null, null));
      chunk.addLine(new AsmInstruction(styZp, "$fe", null, null));
      chunk.addLine(new AsmInstruction(ldyAbs, "p+1", null, null));
      chunk.addLine(new AsmInstruction(styZp, "$ff", null, null));
      chunk.addLine(new AsmInstruction(ldaZp, "active", null, null));
      chunk.addLine(new AsmInstruction(beqRel, "__b2", null, null));
      chunk.addLine(new AsmInstruction(ldyImm, "0", null, null));
      chunk.addLine(new AsmInstruction(ldaImm, "1", null, null));
      chunk.addLine(new AsmInstruction(staIzy, "$fe", null, null));
      chunk.addLine(new AsmLabel("__b2"));
      chunk.addLine(new AsmInstruction(ldyAbs, "p", null, null));
      chunk.addLine(new AsmInstruction(styZp, "$fe", null, null));
      chunk.addLine(new AsmInstruction(ldyAbs, "p+1", null, null));
      chunk.addLine(new AsmInstruction(styZp, "$ff", null, null));
      chunk.addLine(new AsmInstruction(ldyImm, "1", null, null));
      chunk.addLine(new AsmInstruction(ldaImm, "0", null, null));
      chunk.addLine(new AsmInstruction(staIzy, "$fe", null, null));
      chunk.addLine(new AsmInstruction(rtsNon, null, null, null));
      chunk.addLine(new AsmScopeEnd());

      boolean changed = new Pass5PointerReloadElimination(program).optimize();
      String out = asm.toString();
      assertTrue(changed, "Expected redundant pointer reload at __b2 join label to be eliminated");
      int idxB2 = out.indexOf("__b2:");
      assertTrue(idxB2 >= 0);
      assertFalse(out.substring(idxB2).contains("sty.z $fe"),
            "Pointer reload into $fe at __b2 should be eliminated via CFG meet! ASM:\n" + out);
   }

   @Test
   void testZp02_InyDeyTrackingAndStrengthReduction() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      CpuOpcode ldyImm = cpu.getOpcode("ldy", CpuAddressingMode.IMM, false);
      CpuOpcode staIzy = cpu.getOpcode("sta", CpuAddressingMode.IZY, false);
      CpuOpcode rtsNon = cpu.getOpcode("rts", CpuAddressingMode.NON, false);

      chunk.addLine(new AsmInstruction(ldyImm, "0", null, null));
      chunk.addLine(new AsmInstruction(staIzy, "$10", null, null));
      chunk.addLine(new AsmInstruction(ldyImm, "1", null, null));
      chunk.addLine(new AsmInstruction(staIzy, "$10", null, null));
      chunk.addLine(new AsmInstruction(ldyImm, "2", null, null));
      chunk.addLine(new AsmInstruction(staIzy, "$10", null, null));
      chunk.addLine(new AsmInstruction(rtsNon, null, null, null));
      chunk.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();
      new Pass5UnnecesaryLoadElimination(program).optimize();
      String out = asm.toString();
      assertTrue(out.contains("iny"), "Sequential ldy #0; ... ldy #1; ... ldy #2 should strength-reduce to iny! ASM:\n" + out);
      assertFalse(out.contains("ldy #1"), "ldy #1 after ldy #0 should become iny! ASM:\n" + out);
      assertFalse(out.contains("ldy #2"), "ldy #2 after iny (Y=1) should become iny! ASM:\n" + out);
   }

   @Test
   void testZp03_HalfPointerReloadElimination() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      CpuOpcode ldaImm = cpu.getOpcode("lda", CpuAddressingMode.IMM, false);
      CpuOpcode staZp = cpu.getOpcode("sta", CpuAddressingMode.ZP, false);
      CpuOpcode ldyImm = cpu.getOpcode("ldy", CpuAddressingMode.IMM, false);
      CpuOpcode ldaIzy = cpu.getOpcode("lda", CpuAddressingMode.IZY, false);
      CpuOpcode rtsNon = cpu.getOpcode("rts", CpuAddressingMode.NON, false);

      // First pointer: $6000
      chunk.addLine(new AsmInstruction(ldaImm, "$00", null, null));
      chunk.addLine(new AsmInstruction(staZp, "$fe", null, null));
      chunk.addLine(new AsmInstruction(ldaImm, "$60", null, null));
      chunk.addLine(new AsmInstruction(staZp, "$ff", null, null));
      chunk.addLine(new AsmInstruction(ldyImm, "0", null, null));
      chunk.addLine(new AsmInstruction(ldaIzy, "$fe", null, null));
      // Second pointer: $6080 (same high byte $60!)
      chunk.addLine(new AsmInstruction(ldaImm, "$80", null, null));
      chunk.addLine(new AsmInstruction(staZp, "$fe", null, null));
      chunk.addLine(new AsmInstruction(ldaImm, "$60", null, null));
      chunk.addLine(new AsmInstruction(staZp, "$ff", null, null));
      chunk.addLine(new AsmInstruction(ldaIzy, "$fe", null, null));
      chunk.addLine(new AsmInstruction(rtsNon, null, null, null));
      chunk.addLine(new AsmScopeEnd());

      boolean changed = new Pass5PointerReloadElimination(program).optimize();
      String out = asm.toString();
      assertTrue(changed, "Expected redundant high-byte $ff reload to be eliminated");
      int firstFf = out.indexOf("sta.z $ff");
      int secondFf = out.indexOf("sta.z $ff", firstFf + 1);
      assertEquals(-1, secondFf, "Second sta.z $ff with identical #$60 should be eliminated! ASM:\n" + out);
   }

   @Test
   void testZp04_KnownZpPreservedAcrossConditionalBranchAndForwardedOnRead() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      CpuOpcode ldaImm = cpu.getOpcode("lda", CpuAddressingMode.IMM, false);
      CpuOpcode staZp = cpu.getOpcode("sta", CpuAddressingMode.ZP, false);
      CpuOpcode ldaZp = cpu.getOpcode("lda", CpuAddressingMode.ZP, false);
      CpuOpcode ldxZp = cpu.getOpcode("ldx", CpuAddressingMode.ZP, false);
      CpuOpcode beqRel = cpu.getOpcode("beq", CpuAddressingMode.REL, false);
      CpuOpcode rtsNon = cpu.getOpcode("rts", CpuAddressingMode.NON, false);

      chunk.addLine(new AsmInstruction(ldaImm, "0", null, null));
      chunk.addLine(new AsmInstruction(staZp, "$02", null, null));
      chunk.addLine(new AsmInstruction(ldxZp, "$03", null, null));
      chunk.addLine(new AsmInstruction(beqRel, "skip", null, null));
      chunk.addLine(new AsmInstruction(ldaZp, "$02", null, null));
      chunk.addLine(new AsmLabel("skip"));
      chunk.addLine(new AsmInstruction(rtsNon, null, null, null));
      chunk.addLine(new AsmScopeEnd());

      boolean changed = new Pass5InterproceduralParameterPreservation(program).optimize();
      String out = asm.toString();
      assertTrue(changed, "Expected knownZp to survive beq fallthrough and forward lda.z $02 -> lda #0");
      assertFalse(out.contains("lda.z $02"), "lda.z $02 should be forwarded to lda #0! ASM:\n" + out);
   }
}
