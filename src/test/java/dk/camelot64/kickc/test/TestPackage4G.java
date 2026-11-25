package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass5FixLongBranches;
import dk.camelot64.kickc.passes.Pass5NextJumpElimination;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Package 4G:
 * CF-01, CF-02, CF-05, CF-06, CF-12.
 */
public class TestPackage4G {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source, int loopAlignThreshold) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4g_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         Program program = compiler.getProgram();
         if (loopAlignThreshold > 0) {
            program.setLoopAlignThreshold(loopAlignThreshold);
         }
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
   void testCf02_ZeroWasteLoopAlignNotPlacedAfterProcLabel() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const RASTER = (uint8_t *)0xD012;
            void wait_line(void) {
                do {} while (*RASTER != 250);
            }
            void main(void) {
                wait_line();
                wait_line();
            }
            """;
      Program program = compileC(code, 16);
      String asm = program.getAsm().toString();
      int procIdx = asm.indexOf("wait_line:");
      assertTrue(procIdx >= 0, "Expected wait_line: in ASM:\n" + asm);
      int firstInstrIdx = asm.indexOf("lda ", procIdx);
      assertTrue(firstInstrIdx > procIdx, "Expected instruction in wait_line:\n" + asm);
      String betweenProcAndBody = asm.substring(procIdx, firstInstrIdx);
      assertFalse(betweenProcAndBody.contains(".align"),
            "Loop alignment must NOT be emitted between wait_line: and its first instruction (would pad with $00 BRK)! ASM:\n" + asm);
   }

   @Test
   void testCf05_NextJumpEliminationDoesNotDeleteJumpAcrossAlignOrSegment() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(dk.camelot64.kickc.model.values.ScopeRef.ROOT, null, "test");
      CpuOpcode jmpAbs = cpu.getOpcode("jmp", CpuAddressingMode.ABS, false);
      chunk.addLine(new AsmInstruction(jmpAbs, "target", null, null));
      chunk.addLine(new AsmLoopAlign(16));
      chunk.addLine(new AsmLabel("target"));

      AsmChunk chunk2 = asm.startChunk(dk.camelot64.kickc.model.values.ScopeRef.ROOT, null, "test2");
      chunk2.addLine(new AsmInstruction(jmpAbs, "target2", null, null));
      chunk2.addLine(new AsmSegment("Data"));
      chunk2.addLine(new AsmLabel("target2"));

      boolean changed = new Pass5NextJumpElimination(program).optimize();
      assertFalse(changed, "Pass5NextJumpElimination must NOT remove jumps across AsmLoopAlign or AsmSegment!");
      assertTrue(asm.toString().contains("jmp target"), "jmp target must be preserved");
      assertTrue(asm.toString().contains("jmp target2"), "jmp target2 must be preserved");
   }

   @Test
   @Timeout(value = 10, unit = TimeUnit.SECONDS)
   void testCf06_ConditionalJumpSequenceImprovementNoInfiniteLoop() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const OUT = (uint8_t *)0x0400;
            void hang_compiler(uint8_t c) {
                if (c == 0) goto b_lbl;
            c_lbl:
                OUT[0] = 1;
            b_lbl:
                OUT[1] = 2;
                goto c_lbl;
            }
            void main(void) {
                hang_compiler(1);
            }
            """;
      Program program = compileC(code, 0);
      assertNotNull(program.getAsm());
   }

   @Test
   void testCf12_RelaxShortBranchesDoesNotFoldIndirectJmp() throws Exception {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(dk.camelot64.kickc.model.values.ScopeRef.ROOT, null, "proc");
      chunk.addLine(new AsmScopeBegin("proc"));
      CpuOpcode bneRel = cpu.getOpcode("bne", CpuAddressingMode.REL, false);
      CpuOpcode jmpInd = cpu.getOpcode("jmp", CpuAddressingMode.IND, false);
      CpuOpcode rtsImp = cpu.getOpcode("rts", CpuAddressingMode.NON, false);

      chunk.addLine(new AsmLabel("vec"));
      chunk.addLine(new AsmInstruction(bneRel, "!skip+", null, null));
      chunk.addLine(new AsmInstruction(jmpInd, "vec", null, null));
      chunk.addLine(new AsmLabel("!skip"));
      chunk.addLine(new AsmInstruction(rtsImp, null, null, null));
      chunk.addLine(new AsmScopeEnd());

      Pass5FixLongBranches pass = new Pass5FixLongBranches(program);
      java.lang.reflect.Method m = Pass5FixLongBranches.class.getDeclaredMethod("relaxShortBranches");
      m.setAccessible(true);
      m.invoke(pass);
      String out = asm.toString();
      assertTrue(out.contains("jmp (vec)"),
            "Indirect jmp (vec) must NEVER be folded into a relative branch! ASM:\n" + out);
   }
}
