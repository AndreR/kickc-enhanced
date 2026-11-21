package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass4RegisterUpliftCombinations;
import dk.camelot64.kickc.passes.Pass5DeadStoreElimination;
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

/**
 * Tests for Package 5D: GEN-04, GEN-05, GEN-06.
 */
public class TestPackage5D {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_5d_", ".c");
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
   void testGen04_ByteGranularDeadStoreEliminationRemovesUnreadHighByte() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmLabelDecl("__1", "$10"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "__1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "__1+1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "__1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5DeadStoreElimination(program).optimize());
      String out = asm.toString();
      assertFalse(out.contains("sta.z __1+1"),
            "Unread high byte sta.z __1+1 should be eliminated even though __1+0 is read! ASM:\n" + out);
      assertTrue(out.contains("sta.z __1"), "Live low byte sta.z __1 must be preserved! ASM:\n" + out);
   }

   @Test
   void testGen05_UpliftTrialResultTracksBytesAndCpuRegs() {
      var r1 = new Pass4RegisterUpliftCombinations.UpliftTrialResult(null, 100, 12, 1, true);
      var r2 = new Pass4RegisterUpliftCombinations.UpliftTrialResult(null, 100, 10, 2, true);
      assertEquals(12, r1.bytes);
      assertEquals(10, r2.bytes);
      assertEquals(2, r2.upliftedRegCount);
   }

   @Test
   void testGen06_AutoARegisterReturnCompilesCleanly() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t g_x = 5;
            volatile uint8_t g_y = 7;
            volatile uint8_t g_out1, g_out2;

            uint8_t add_vals(uint8_t a, uint8_t b) {
                return a + b + 1;
            }

            void main(void) {
                g_out1 = add_vals(g_x, g_y);
                g_out2 = add_vals(g_y, g_x);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertNotNull(asm);
      assertTrue(asm.contains("jsr add_vals"), "Expected calls to add_vals:\n" + asm);
      assertFalse(asm.contains("lda.z add_vals.return"),
            "8-bit return value should be returned directly in A, not reloaded from add_vals.return! ASM:\n" + asm);
   }
}
