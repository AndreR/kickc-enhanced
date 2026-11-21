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

/**
 * Regression tests for Package 4E:
 * SSA-11, SSA-12, SSA-13, SSA-14.
 */
public class TestPackage4E {

   @BeforeAll
   static void initTmp() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String sourceCode) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4e_", ".c");
      Files.writeString(tempFile, sourceCode);
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
   void testSsa11_ShortCircuitLogicalAndSkipsRhs() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const IO = (uint8_t *)0xD020;
            volatile uint8_t flag;
            volatile uint8_t out;
            void main(void) {
                if (flag && *IO == 1) {
                    out = 1;
                }
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      int mainIdx = asm.indexOf("main:");
      assertTrue(mainIdx >= 0, "Expected main: in ASM:\n" + asm);
      String mainAsm = asm.substring(mainIdx);
      int idxFlag = mainAsm.indexOf("flag");
      int idxIo = mainAsm.indexOf("IO");
      int idxBranch = -1;
      for (String br : new String[]{"beq ", "bne "}) {
         int idx = mainAsm.indexOf(br, idxFlag);
         if (idx > 0 && (idxBranch < 0 || idx < idxBranch)) {
            idxBranch = idx;
         }
      }
      assertTrue(idxFlag > 0 && idxIo > 0, "Expected both flag and IO in main:\n" + mainAsm);
      assertTrue(idxBranch > idxFlag && idxBranch < idxIo,
            "Short-circuit && must branch on flag before reading *IO! ASM:\n" + mainAsm);
   }

   @Test
   void testSsa11_ConditionalJumpSimplificationNoSinkPastStore() throws Exception {
      String code = """
            #include <stdint.h>
            #include <stdbool.h>
            volatile uint8_t result;
            uint8_t check_and_clear(uint8_t *buf) {
                bool was_five = (buf[0] == 5);
                buf[0] = 0;
                if (was_five) return 1;
                return 0;
            }
            void main(void) {
                uint8_t b[2] = { 5, 0 };
                result = check_and_clear(b);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      int procIdx = asm.indexOf("check_and_clear:");
      assertTrue(procIdx >= 0, "Expected check_and_clear in ASM:\n" + asm);
      String procAsm = asm.substring(procIdx);
      int cmpFive = procAsm.indexOf("#5");
      int storeZero = procAsm.indexOf("#0");
      assertTrue(cmpFive > 0 && storeZero > 0, "Expected #5 comparison and #0 store in check_and_clear:\n" + procAsm);
      assertTrue(cmpFive < storeZero,
            "buf[0] == 5 must be evaluated BEFORE buf[0] = 0! LOG:\n" + program.getLog().toString() + "\nASM:\n" + procAsm);
   }

   @Test
   void testSsa12_IfInitPostIncrementNotDropped() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t x = 1;
            volatile uint8_t res;
            void main(void) {
                if (x++; x < 10) {
                    res = x;
                }
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("inc.z x") || asm.contains("inc x") || asm.contains("adc #1"),
            "if (x++; ...) must emit increment for x! ASM:\n" + asm);
   }

   @Test
   void testSsa13_UnrollConditionVolatileDoesNotCrash() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t limit = 4;
            volatile uint8_t sink;
            void main(void) {
                limit = 4;
                inline for (uint8_t i = 0; i < limit; i++) {
                    sink = i;
                }
            }
            """;
      // Must fail cleanly with CompileError (cannot unroll non-constant volatile loop), NOT InternalError
      assertThrows(dk.camelot64.kickc.model.CompileError.class, () -> compileC(code));
   }

   @Test
   void testSsa14_InlineFunctionWithTernary() throws Exception {
      String code = """
            #include <stdint.h>
            inline uint8_t max_u8(uint8_t a, uint8_t b) {
                return (a > b) ? a : b;
            }
            volatile uint8_t r;
            void main(void) {
                r = max_u8(3, 7);
            }
            """;
      assertDoesNotThrow(() -> compileC(code));
   }
}
