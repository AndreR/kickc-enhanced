package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.passes.Pass5IllegalOpcodeOptimization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Package 5C: ILL-04, ILL-05, ILL-06, ILL-07, ILL-08.
 */
public class TestPackage5C {

   @Test
   void testIll04_RmwAluAcrossInterveningLda() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("asl", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$03", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("ora", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5IllegalOpcodeOptimization(program).optimize());
      String out = asm.toString();
      assertTrue(out.contains("slo.z $02"), "Expected asl $02; lda $03; ora $02 -> lda $03; slo $02! ASM:\n" + out);
      assertFalse(out.contains("asl.z $02"), "asl.z $02 should be folded into slo.z $02! ASM:\n" + out);
   }

   @Test
   void testIll05_AncFoldsAndClcAndSignedShift() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("and", CpuAddressingMode.IMM, false), "$0f", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("adc", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("and", CpuAddressingMode.IMM, false), "$f0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "$80", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("ror", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5IllegalOpcodeOptimization(program).optimize());
      String out = asm.toString();
      assertTrue(out.contains("anc #$0f"), "Expected and #$0f; clc -> anc #$0f! ASM:\n" + out);
      assertFalse(out.contains("clc"), "clc should be eliminated by anc #$0f! ASM:\n" + out);
      assertTrue(out.contains("anc #$f0"), "Expected and #$f0; cmp #$80; ror -> anc #$f0; ror! ASM:\n" + out);
      assertFalse(out.contains("cmp #$80"), "cmp #$80 should be eliminated by anc #$f0! ASM:\n" + out);
   }

   @Test
   void testIll06_SaxFoldsTxaAndSta() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("txa", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("and", CpuAddressingMode.IMM, false), "$0f", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5IllegalOpcodeOptimization(program).optimize());
      String out = asm.toString();
      assertTrue(out.contains("sax.z $02"), "Expected txa; and #$0f; sta.z $02 -> lda #$0f; sax.z $02! ASM:\n" + out);
      assertFalse(out.contains("txa"), "txa should be eliminated! ASM:\n" + out);
   }

   @Test
   void testIll07_AxsFoldsConstantXStepping() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("txa", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("adc", CpuAddressingMode.IMM, false), "4", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("tax", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5IllegalOpcodeOptimization(program).optimize());
      String out = asm.toString();
      assertTrue(out.contains("axs #$fc"), "Expected txa; clc; adc #4; tax -> txa; axs #$fc! ASM:\n" + out);
      assertFalse(out.contains("clc"), "clc should be eliminated! ASM:\n" + out);
      assertFalse(out.contains("tax"), "tax should be eliminated! ASM:\n" + out);
   }

   @Test
   void testIll08_DcpFoldsDecLdaCmpBne() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmLabel("loop"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$04", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("dec", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("cmp", CpuAddressingMode.IMM, false), "$ff", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "loop", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5IllegalOpcodeOptimization(program).optimize());
      String out = asm.toString();
      assertTrue(out.contains("dcp.z $02"), "Expected dec $02; lda $02; cmp #$ff; bne -> lda #$ff; dcp $02; bne! ASM:\n" + out);
      assertFalse(out.contains("cmp #$ff"), "cmp #$ff should be eliminated! ASM:\n" + out);
   }
}
