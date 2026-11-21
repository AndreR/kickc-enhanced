package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TestPackage5A {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_5a_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         Program program = compiler.getProgram();
         program.getIncludePaths().add("src/main/kc/include");
         program.getLibraryPaths().add("src/main/kc/lib");
         program.getTargetPlatformPaths().add("src/main/kc/target");
         program.setAsmFragmentBaseFolder(Paths.get("src/main/fragment"));
         program.initAsmFragmentMasterSynthesizer(null);

         File platformFile = new File("src/main/kc/target/c64.tgt");
         TargetPlatform targetPlatform = CTargetPlatformParser.parseTargetPlatformFile(
               TargetPlatform.DEFAULT_NAME, platformFile, tempFile, program.getTargetPlatformPaths());
         program.setTargetPlatform(targetPlatform);
         program.addReservedZps(targetPlatform.getReservedZps());
         program.getOutputFileManager().setBinaryExtension(targetPlatform.getOutFileExtension());
         program.getOutputFileManager().setCurrentDir(Paths.get("."));
         program.getOutputFileManager().setPrimaryCFile(tempFile);

         Map<String, String> defines = new HashMap<>();
         defines.put("__KICKC__", "1");
         defines.putAll(targetPlatform.getDefines());

         compiler.compile(Collections.singletonList(tempFile), defines);
         return program;
      } finally {
         Files.deleteIfExists(tempFile);
      }
   }

   @Test
   void testFl02_CarryInferenceEliminatesRedundantClcSec() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t x = 10;
            volatile uint8_t limit = 20;
            volatile uint8_t delta = 3;
            volatile uint8_t y;
            void main(void) {
                if (x < limit) {
                    y = x + delta;
                }
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertFalse(asm.contains("clc"), "Redundant clc after bcs fallthrough should be eliminated");
   }

   @Test
   void testFl03_RedundantCpxCpyZeroEliminated() {
      Program program = new Program();
      dk.camelot64.cpufamily6502.Cpu65xx cpu = dk.camelot64.kickc.model.TargetCpu.MOS6502X.getCpu65xx();
      dk.camelot64.kickc.asm.AsmProgram asm = new dk.camelot64.kickc.asm.AsmProgram(dk.camelot64.kickc.model.TargetCpu.MOS6502X);
      program.setAsm(asm);

      dk.camelot64.kickc.asm.AsmChunk chunk = asm.startChunk(dk.camelot64.kickc.model.values.ScopeRef.ROOT, null, "fl03");
      chunk.addLine(new dk.camelot64.kickc.asm.AsmScopeBegin("fl03"));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("ldx", dk.camelot64.cpufamily6502.CpuAddressingMode.ZP, false), "$10", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("cpx", dk.camelot64.cpufamily6502.CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("beq", dk.camelot64.cpufamily6502.CpuAddressingMode.REL, false), "__b1", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmLabel("__b1"));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("rts", dk.camelot64.cpufamily6502.CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmScopeEnd());

      assertTrue(new dk.camelot64.kickc.passes.Pass5StandardPeepholeOptimization(program).optimize());
      assertFalse(asm.toString().contains("cpx #0"), "FL-03: Redundant cpx #0 after ldx should be eliminated! ASM:\n" + asm);
   }

   @Test
   void testFl04_DirectTransferStoreEliminatesTxaTya() {
      Program program = new Program();
      dk.camelot64.cpufamily6502.Cpu65xx cpu = dk.camelot64.kickc.model.TargetCpu.MOS6502X.getCpu65xx();
      dk.camelot64.kickc.asm.AsmProgram asm = new dk.camelot64.kickc.asm.AsmProgram(dk.camelot64.kickc.model.TargetCpu.MOS6502X);
      program.setAsm(asm);

      dk.camelot64.kickc.asm.AsmChunk chunk = asm.startChunk(dk.camelot64.kickc.model.values.ScopeRef.ROOT, null, "fl04");
      chunk.addLine(new dk.camelot64.kickc.asm.AsmScopeBegin("fl04"));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("ldx", dk.camelot64.cpufamily6502.CpuAddressingMode.IMM, false), "7", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("txa", dk.camelot64.cpufamily6502.CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("sta", dk.camelot64.cpufamily6502.CpuAddressingMode.ZP, false), "$20", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("lda", dk.camelot64.cpufamily6502.CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("rts", dk.camelot64.cpufamily6502.CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmScopeEnd());

      assertTrue(new dk.camelot64.kickc.passes.Pass5StandardPeepholeOptimization(program).optimize());
      String out = asm.toString();
      assertFalse(out.contains("txa"), "FL-04: txa; sta.z $20 with dead A should fold to stx.z $20! ASM:\n" + out);
      assertTrue(out.contains("stx.z $20") || out.contains("stx $20"), "Expected stx.z $20 in ASM:\n" + out);
   }

   @Test
   void testFl05_CommutativeAccumulatorForwarding() throws Exception {
      // Direct Pass5 ASM verification that HOCH-01 made FL-05 reachable for ZP operands:
      // sta.z hi; lda.z lo; ora.z hi -> sta.z hi; ora.z lo (eliminating lda.z lo!)
      Program pAsm = new Program();
      TargetPlatform tp = new TargetPlatform("c64");
      tp.setCpu(dk.camelot64.kickc.model.TargetCpu.MOS6502X);
      pAsm.setTargetPlatform(tp);
      dk.camelot64.cpufamily6502.Cpu65xx cpu = dk.camelot64.kickc.model.TargetCpu.MOS6502X.getCpu65xx();
      dk.camelot64.kickc.asm.AsmProgram asmProg = new dk.camelot64.kickc.asm.AsmProgram(dk.camelot64.kickc.model.TargetCpu.MOS6502X);
      pAsm.setAsm(asmProg);

      dk.camelot64.kickc.asm.AsmChunk chunk = asmProg.startChunk(dk.camelot64.kickc.model.values.ScopeRef.ROOT, null, "fl05");
      chunk.addLine(new dk.camelot64.kickc.asm.AsmScopeBegin("fl05"));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("sta", dk.camelot64.cpufamily6502.CpuAddressingMode.ZP, false), "hi", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("lda", dk.camelot64.cpufamily6502.CpuAddressingMode.ZP, false), "lo", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("ora", dk.camelot64.cpufamily6502.CpuAddressingMode.ZP, false), "hi", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("bne", dk.camelot64.cpufamily6502.CpuAddressingMode.REL, false), "__b1", null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmLabel("__b1"));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmInstruction(cpu.getOpcode("rts", dk.camelot64.cpufamily6502.CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new dk.camelot64.kickc.asm.AsmScopeEnd());

      assertTrue(new dk.camelot64.kickc.passes.Pass5RedundantStoreAndReloadElimination(pAsm).optimize(),
            "FL-05 / HOCH-01: Commutative accumulator forwarding must fire for ZP operands!");
      String optOut = asmProg.toString();
      assertFalse(optOut.contains("lda.z lo"), "FL-05: Redundant lda.z lo must be eliminated! ASM:\n" + optOut);
      assertTrue(optOut.contains("ora.z lo"), "FL-05: ora.z hi must be rewritten to ora.z lo! ASM:\n" + optOut);

      // End-to-end C compilation check
      String code = """
            #include <stdint.h>
            volatile uint16_t a = 0x1234;
            volatile uint16_t b = 0x5678;
            volatile uint8_t is_zero;
            void main(void) {
                uint16_t w = a + b;
                is_zero = (w == 0) ? 1 : 0;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("ora.z w") || asm.contains("ora w"), "Expected FL-05 16-bit zero test ora.z w in ASM:\n" + asm);
      assertFalse(asm.contains("lda.z w\n    ora"), "FL-05 should eliminate redundant lda.z w before ora! ASM:\n" + asm);
   }
}
