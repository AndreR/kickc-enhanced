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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Package 4H:
 * CF-03 (HighSpeedJumpTableSwitch), CF-04 (MultiEntryByteSkipping),
 * CF-07 / HOCH-07 (FixLongBranches), CF-08 (StaticRegisterValues backward multi-labels),
 * CF-13 (DuplicateBlockElimination with non-instruction lines).
 */
public class TestPackage4H {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   @Test
   void testCf03_JumpTableSwitchPreservesLiveXAndEmitsRealInstructions() {
      Program program = new Program();
      program.getOutputFileManager().setPrimaryCFile(java.nio.file.Paths.get("test_cf07.c"));
      program.getOutputFileManager().setCurrentDir(java.nio.file.Paths.get("."));
      program.getOutputFileManager().setBinaryExtension("prg");
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      // Build a 4-case dense switch where case 0 reads X via ABX (lda table,x) -> X is live!
      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "sw_live_x");
      chunk.addLine(new AsmScopeBegin("sw_live_x"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b2", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "2", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b3", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "3", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b4", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__breturn", null, null));
      chunk.addLine(new AsmLabel("__b1"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABX, false), "table", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmLabel("__b2"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmLabel("__b3"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmLabel("__b4"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmLabel("__breturn"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertFalse(new Pass5HighSpeedJumpTableSwitch(program).optimize(),
            "JumpTableSwitch must NOT clobber X when a case target reads X via ABX! ASM:\n" + asm);

      // Now test a safe 4-case dense switch (X dead in all targets) -> emits real AsmInstructions (not monolithic inline kickasm)
      Program program2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      program2.setAsm(asm2);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "sw_safe");
      c2.addLine(new AsmScopeBegin("sw_safe"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b2", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "2", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b3", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "3", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b4", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__breturn", null, null));
      c2.addLine(new AsmLabel("__b1"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "10", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmLabel("__b2"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "20", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmLabel("__b3"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "30", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmLabel("__b4"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "40", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmLabel("__breturn"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "0", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      assertTrue(new Pass5HighSpeedJumpTableSwitch(program2).optimize(),
            "Safe 4-case switch should be transformed into an RTS jump table");
      boolean hasRealBcs = false;
      for(AsmChunk ch : asm2.getChunks()) {
         for(AsmLine line : ch.getLines()) {
            assertFalse(line instanceof AsmInlineKickAsm,
                  "CF-03: Dispatch must emit real AsmInstructions, not AsmInlineKickAsm!");
            if(line instanceof AsmInstruction inst && "bcs".equals(inst.getCpuOpcode().getMnemonic())) {
               hasRealBcs = true;
               assertFalse(inst.isNoOptimize(), "bcs default branch must remain optimizable for FixLongBranches");
            }
         }
      }
      assertTrue(hasRealBcs, "Expected real bcs instruction in RTS jump table dispatch:\n" + asm2);
   }

   @Test
   void testCf04_MultiEntryByteSkippingBlocksIoDummyReadAndLiveFlags() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // Case 1: Second immediate is $dc -> .byte $2c + lda #$dc decodes as BIT $dcA9 (CIA I/O read!) -> MUST NOT optimize!
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);
      AsmChunk c1 = asm1.startChunk(ScopeRef.ROOT, null, "io_skip");
      c1.addLine(new AsmScopeBegin("io_skip"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__b2", null, null));
      c1.addLine(new AsmLabel("__b1"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "$dc", null, null));
      c1.addLine(new AsmLabel("__b2"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      assertFalse(new Pass5MultiEntryByteSkipping(p1).optimize(),
            "CF-04: Must NOT emit .byte $2c when skipped lda #$dc causes BIT $dcA9 hardware I/O read! ASM:\n" + asm1);

      // Case 2: Safe immediate $05, but N flag is live after __b2 (bmi target) -> MUST NOT optimize!
      Program p2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      p2.setAsm(asm2);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "flag_skip");
      c2.addLine(new AsmScopeBegin("flag_skip"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__b2", null, null));
      c2.addLine(new AsmLabel("__b1"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "5", null, null));
      c2.addLine(new AsmLabel("__b2"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("bmi", CpuAddressingMode.REL, false), "__b3", null, null));
      c2.addLine(new AsmLabel("__b3"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      assertFalse(new Pass5MultiEntryByteSkipping(p2).optimize(),
            "CF-04: Must NOT emit .byte $2c when N/V/Z flags are live after merge label! ASM:\n" + asm2);

      // Case 3: Safe immediate $05 and flags dead after merge -> SHOULD emit .byte $2c!
      Program p3 = new Program();
      AsmProgram asm3 = new AsmProgram(TargetCpu.MOS6502X);
      p3.setAsm(asm3);
      AsmChunk c3 = asm3.startChunk(ScopeRef.ROOT, null, "safe_skip");
      c3.addLine(new AsmScopeBegin("safe_skip"));
      c3.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      c3.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__b2", null, null));
      c3.addLine(new AsmLabel("__b1"));
      c3.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "5", null, null));
      c3.addLine(new AsmLabel("__b2"));
      c3.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      c3.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      c3.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c3.addLine(new AsmScopeEnd());

      assertTrue(new Pass5MultiEntryByteSkipping(p3).optimize(),
            "CF-04: Safe immediate with dead flags should apply .byte $2c byte skipping");
      assertTrue(asm3.toString().contains("$2c"), "Expected .byte $2c in ASM:\n" + asm3);

      // Case 4: Symbolic constants (.const RED = 2, .const PINK = 10) -> safe non-I/O values -> SHOULD emit .byte $2c!
      Program p4 = new Program();
      AsmProgram asm4 = new AsmProgram(TargetCpu.MOS6502X);
      p4.setAsm(asm4);
      AsmChunk c4Global = asm4.startChunk(ScopeRef.ROOT, null, "consts");
      c4Global.addLine(new AsmConstant("RED", "2"));
      c4Global.addLine(new AsmConstant("PINK", "$0a"));
      AsmChunk c4 = asm4.startChunk(ScopeRef.ROOT, null, "const_skip");
      c4.addLine(new AsmScopeBegin("const_skip"));
      c4.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "RED", null, null));
      c4.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__b5", null, null));
      c4.addLine(new AsmLabel("__b4"));
      c4.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "PINK", null, null));
      c4.addLine(new AsmLabel("__b5"));
      c4.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "$0400", null, null));
      c4.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      c4.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c4.addLine(new AsmScopeEnd());

      assertTrue(new Pass5MultiEntryByteSkipping(p4).optimize(),
            "Symbolic .const RED/PINK with safe values should apply .byte $2c byte skipping! ASM:\n" + asm4);
      assertTrue(asm4.toString().contains("$2c"), "Expected .byte $2c for symbolic .const RED/PINK in ASM:\n" + asm4);

      // Case 5: Symbolic constant resolving to hardware I/O high byte (.const IO_HI = $de) -> MUST NOT emit .byte $2c!
      Program p5 = new Program();
      AsmProgram asm5 = new AsmProgram(TargetCpu.MOS6502X);
      p5.setAsm(asm5);
      AsmChunk c5Global = asm5.startChunk(ScopeRef.ROOT, null, "consts_io");
      c5Global.addLine(new AsmConstant("RED", "2"));
      c5Global.addLine(new AsmConstant("IO_HI", "$de"));
      AsmChunk c5 = asm5.startChunk(ScopeRef.ROOT, null, "const_io_skip");
      c5.addLine(new AsmScopeBegin("const_io_skip"));
      c5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "RED", null, null));
      c5.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__b5", null, null));
      c5.addLine(new AsmLabel("__b4"));
      c5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "IO_HI", null, null));
      c5.addLine(new AsmLabel("__b5"));
      c5.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "$0400", null, null));
      c5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      c5.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c5.addLine(new AsmScopeEnd());

      assertFalse(new Pass5MultiEntryByteSkipping(p5).optimize(),
            "Symbolic .const IO_HI = $de must STILL block .byte $2c to avoid I/O dummy read! ASM:\n" + asm5);
   }

   @Test
   void testCf07_FixLongBranchesDoesNotRelaxReturnTrampolineAcrossSegments() {
      Program program = new Program();
      program.getOutputFileManager().setPrimaryCFile(java.nio.file.Paths.get("test_cf07.c"));
      program.getOutputFileManager().setCurrentDir(java.nio.file.Paths.get("."));
      program.getOutputFileManager().setBinaryExtension("prg");
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk cDef = asm.startChunk(ScopeRef.ROOT, null, "defs");
      cDef.addLine(new AsmSegmentDef("Code1", java.util.Collections.singletonMap("start", "$080d")));
      cDef.addLine(new AsmSegmentDef("Code2", java.util.Collections.singletonMap("start", "$1000")));

      AsmChunk c1 = asm.startChunk(ScopeRef.ROOT, null, "seg1");
      c1.addLine(new AsmSegment("Code1"));
      c1.addLine(new AsmScopeBegin("f1"));
      // Simulate long-branch trampoline pattern: bne !+; jmp __breturn; !:
      c1.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "!+", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__breturn", null, null));
      c1.addLine(new AsmLabel("!"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("nop", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      AsmChunk c2 = asm.startChunk(ScopeRef.ROOT, null, "seg2");
      c2.addLine(new AsmSegment("Code2"));
      c2.addLine(new AsmLabel("__breturn"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));

      new Pass5FixLongBranches(program).optimize();
      assertTrue(asm.toString().contains("jmp __breturn"),
            "HOCH-07: Return trampoline must NOT be relaxed across different segments! ASM:\n" + asm);
   }

   @Test
   void testCf08_StaticRegisterValuesInvalidatesAtBackwardMultiLabelTarget() {
      Program program = new Program();
      program.getOutputFileManager().setPrimaryCFile(java.nio.file.Paths.get("test_cf07.c"));
      program.getOutputFileManager().setCurrentDir(java.nio.file.Paths.get("."));
      program.getOutputFileManager().setBinaryExtension("prg");
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "bwd_multilabel");
      chunk.addLine(new AsmScopeBegin("bwd_multilabel"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmLabel("!"));
      // Inside loop: A is loaded with 0, then modified by adc #1, then bne !- jumps back to !
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "5", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("dex", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "!-", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();
      String out = asm.toString();
      // The second `lda #0` right after `!: ` MUST be kept because `bne !-` loops back with A == 5!
      int firstLda0 = out.indexOf("lda #0");
      int secondLda0 = out.indexOf("lda #0", firstLda0 + 1);
      assertTrue(secondLda0 > firstLda0,
            "CF-08: lda #0 at backward multi-label target !: must NOT be eliminated! ASM:\n" + out);
   }

   @Test
   void testCf13_DuplicateBlockEliminationRejectsBlocksContainingDataLines() {
      Program program = new Program();
      program.getOutputFileManager().setPrimaryCFile(java.nio.file.Paths.get("test_cf07.c"));
      program.getOutputFileManager().setCurrentDir(java.nio.file.Paths.get("."));
      program.getOutputFileManager().setBinaryExtension("prg");
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "dup_data");
      chunk.addLine(new AsmScopeBegin("dup_data"));
      chunk.addLine(new AsmLabel("__b1"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmDataNumeric(null, AsmDataNumeric.Type.BYTE, List.of("$aa")));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));

      chunk.addLine(new AsmLabel("__b2"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmDataNumeric(null, AsmDataNumeric.Type.BYTE, List.of("$bb")));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertFalse(new Pass5DuplicateBlockElimination(program).optimize(),
            "CF-13: Blocks containing embedded AsmDataNumeric lines must NOT be merged! ASM:\n" + asm);
      assertTrue(asm.toString().contains("$aa") && asm.toString().contains("$bb"),
            "Both data bytes must remain intact! ASM:\n" + asm);
   }
}
