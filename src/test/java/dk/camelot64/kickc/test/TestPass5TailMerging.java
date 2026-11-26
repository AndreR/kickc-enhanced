package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.passes.Pass5RoundContext;
import dk.camelot64.kickc.passes.Pass5TailMergingOptimization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Pass5TailMergingOptimization} (intra-scope suffix tail-merging,
 * cross-procedure shared epilogues, segment isolation, and local-symbol safety).
 */
public class TestPass5TailMerging {

   private static AsmInstruction inst(AsmProgram asm, String mnem, CpuAddressingMode mode, String op1) {
      CpuOpcode opcode = asm.getTargetCpu().getCpu65xx().getOpcode(mnem, mode, false);
      assertNotNull(opcode, "Opcode not found: " + mnem + " " + mode);
      return new AsmInstruction(opcode, op1, null, null);
   }

   private static Program createProgram() {
      Program program = new Program();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);
      return program;
   }

   private static List<String> collectInstructions(AsmProgram asm) {
      List<String> out = new ArrayList<>();
      for(AsmChunk chunk : asm.getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmInstruction) {
               out.add(line.getAsm().trim());
            }
         }
      }
      return out;
   }

   private static int totalCodeBytes(AsmProgram asm) {
      int total = 0;
      for(AsmChunk chunk : asm.getChunks()) {
         total += chunk.getBytes();
      }
      return total;
   }

   @Test
   void testIntraScopeSuffixTailMerging() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "tail_merge_intra");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "flag"));
      asm.addLine(inst(asm, "beq", CpuAddressingMode.REL, "__b1"));
      // Branch 1: different prefix, shared >= 4-byte suffix (sta $d020; ldx #0; rts = 6 bytes)
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$01"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "$d020"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      // Branch 2: different prefix, same shared suffix
      asm.addLine(new AsmLabel("__b1"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$02"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "$d020"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      int beforeBytes = totalCodeBytes(asm);
      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertTrue(opt.optimize(), "Expected intra-scope tail merge to optimize");
      int afterBytes = totalCodeBytes(asm);

      assertTrue(afterBytes < beforeBytes, "Expected code size reduction, before=" + beforeBytes + ", after=" + afterBytes);
      assertEquals(beforeBytes - 3, afterBytes, "Expected 6-byte suffix replaced by 3-byte jmp (saving 3 bytes)");

      List<String> insns = collectInstructions(asm);
      long rtsCount = insns.stream().filter(s -> s.equals("rts")).count();
      long jmpCount = insns.stream().filter(s -> s.startsWith("jmp __tail_")).count();
      assertEquals(1, rtsCount, "Only one canonical rts should remain: " + insns);
      assertEquals(1, jmpCount, "Duplicate suffix should be replaced with jmp __tail_N: " + insns);
      assertFalse(opt.optimize(), "Second pass should be a no-op");
   }

   @Test
   void testIntraScopeShortSuffixNotMerged() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "tail_merge_short");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "beq", CpuAddressingMode.REL, "__b1"));
      // 3-byte suffix: lda #0 (2) + rts (1) = 3 bytes (< 4) -> must NOT merge with 3-byte jmp
      asm.addLine(inst(asm, "inx", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmLabel("__b1"));
      asm.addLine(inst(asm, "iny", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertFalse(opt.optimize(), "3-byte suffix should not be merged because jmp is 3 bytes");
   }

   @Test
   void testIntraScopeStopsAtLabelBoundary() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "tail_merge_label_guard");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "beq", CpuAddressingMode.REL, "mid"));
      // Tail 1: sta $d020 (3) + rts (1) = 4 bytes, no internal label
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$01"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "$d020"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      // Tail 2: sta $d020 (3) BEFORE mid: and lda #$00 (2) + rts (1) AFTER mid:
      // Backward scan from second rts MUST stop at mid:, leaving only 3 bytes -> must NOT merge!
      asm.addLine(new AsmLabel("__b1"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "$d020"));
      asm.addLine(new AsmLabel("mid"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertFalse(opt.optimize(), "Must not merge across internal branch target label mid:");
   }

   @Test
   void testCrossProcedureSharedInterruptEpilogue() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();

      asm.startChunk(ScopeRef.ROOT, 0, "irq1");
      asm.addLine(new AsmScopeBegin("irq1"));
      asm.addLine(inst(asm, "inc", CpuAddressingMode.ABS, "$d020"));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tay", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tax", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rti", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      asm.startChunk(ScopeRef.ROOT, 1, "irq2");
      asm.addLine(new AsmScopeBegin("irq2"));
      asm.addLine(inst(asm, "inc", CpuAddressingMode.ABS, "$d021"));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tay", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tax", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rti", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      int beforeBytes = totalCodeBytes(asm);
      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertTrue(opt.optimize(), "Expected cross-procedure IRQ epilogue merge");
      int afterBytes = totalCodeBytes(asm);

      assertEquals(beforeBytes - 3, afterBytes, "6-byte epilogue replaced by 3-byte jmp saves 3 bytes");
      List<String> insns = collectInstructions(asm);
      long rtiCount = insns.stream().filter(s -> s.equals("rti")).count();
      long epilogueJmpCount = insns.stream().filter(s -> s.startsWith("jmp irq1.__epilogue_")).count();
      assertEquals(1, rtiCount, "Only one rti should remain across irq1/irq2: " + insns);
      assertEquals(1, epilogueJmpCount, "irq2 should jump to irq1.__epilogue_N: " + insns);
   }

   @Test
   void testCrossProcedureSharedRtsEpilogue() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();

      asm.startChunk(ScopeRef.ROOT, 0, "p1");
      asm.addLine(new AsmScopeBegin("proc1"));
      asm.addLine(inst(asm, "inc", CpuAddressingMode.ABS, "$d020"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      asm.startChunk(ScopeRef.ROOT, 1, "p2");
      asm.addLine(new AsmScopeBegin("proc2"));
      asm.addLine(inst(asm, "dec", CpuAddressingMode.ABS, "$d021"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      int beforeBytes = totalCodeBytes(asm);
      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertTrue(opt.optimize(), "Expected cross-procedure RTS epilogue merge");
      int afterBytes = totalCodeBytes(asm);

      assertEquals(beforeBytes - 2, afterBytes, "5-byte epilogue replaced by 3-byte jmp saves 2 bytes");
      List<String> insns = collectInstructions(asm);
      assertTrue(insns.stream().anyMatch(s -> s.startsWith("jmp proc1.__epilogue_")),
            "proc2 should jump to proc1.__epilogue_N: " + insns);
   }

   @Test
   void testCrossProcedureSegmentProtection() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();

      asm.startChunk(ScopeRef.ROOT, 0, "bank1_proc");
      asm.addLine(new AsmSegment("Bank1"));
      asm.addLine(new AsmScopeBegin("bank1_proc"));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tay", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tax", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rti", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      asm.startChunk(ScopeRef.ROOT, 1, "bank2_proc");
      asm.addLine(new AsmSegment("Bank2"));
      asm.addLine(new AsmScopeBegin("bank2_proc"));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tay", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "tax", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "pla", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rti", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertFalse(opt.optimize(), "Procedures in different segments must NEVER be cross-jumped");
      List<String> insns = collectInstructions(asm);
      assertEquals(2, insns.stream().filter(s -> s.equals("rti")).count(), "Both rti instructions must remain");
   }

   @Test
   void testCrossProcedureLocalSymbolNotMerged() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();

      asm.startChunk(ScopeRef.ROOT, 0, "p1");
      asm.addLine(new AsmScopeBegin("p1"));
      asm.addLine(new AsmLabelDecl("tmp", "$02"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "tmp"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      asm.startChunk(ScopeRef.ROOT, 1, "p2");
      asm.addLine(new AsmScopeBegin("p2"));
      asm.addLine(new AsmLabelDecl("tmp", "$04"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "tmp"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$00"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      assertFalse(opt.optimize(), "Epilogues referencing scope-local symbols must NOT merge across procedures");
   }

   @Test
   void testWithPass5RoundContext() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();

      asm.startChunk(ScopeRef.ROOT, 0, "ctx1");
      asm.addLine(new AsmScopeBegin("f1"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$10"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$20"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      asm.startChunk(ScopeRef.ROOT, 1, "ctx2");
      asm.addLine(new AsmScopeBegin("f2"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$10"));
      asm.addLine(inst(asm, "ldx", CpuAddressingMode.IMM, "$20"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      Pass5RoundContext ctx = new Pass5RoundContext(asm);
      Pass5TailMergingOptimization opt = new Pass5TailMergingOptimization(program);
      opt.setRoundContext(ctx);

      assertTrue(opt.optimize(), "Round 1 should merge f2 epilogue into f1");
      assertTrue(ctx.isScopeDirty("f1"));
      assertTrue(ctx.isScopeDirty("f2"));

      ctx.advanceRound();
      assertFalse(opt.optimize(), "Round 2 should be a no-op");
   }
}
