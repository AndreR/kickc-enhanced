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
 * Tests for Compile-Time Function Evaluation (CTFE) of {@code constexpr} functions
 * via {@link dk.camelot64.kickc.passes.Pass2ConstexprEvaluator}.
 */
public class TestConstexprExecution {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_constexpr_", ".c");
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
   void testConstexprArithmeticFoldedToImmediateZeroCodeSize() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const OUT1 = (uint8_t *)0x0400;
            volatile uint8_t * const OUT2 = (uint8_t *)0x0401;

            constexpr uint8_t sq_plus_one(uint8_t x) {
                return x * x + 1;
            }

            void main(void) {
                *OUT1 = sq_plus_one(4);
                *OUT2 = sq_plus_one(5);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("#$11") || asm.contains("#17"),
            "Expected immediate 17 (#$11) in ASM:\n" + asm);
      assertTrue(asm.contains("#$1a") || asm.contains("#26"),
            "Expected immediate 26 (#$1a) in ASM:\n" + asm);
      assertFalse(asm.contains("sq_plus_one:"),
            "Compile-time-only constexpr procedure must emit 0 bytes in ASM:\n" + asm);
      assertFalse(asm.contains("jsr "),
            "Did not expect runtime jsr for constexpr calls:\n" + asm);
   }

   @Test
   void testConstexprLoopEvaluatedAtCompileTime() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const OUT1 = (uint8_t *)0x0400;
            volatile uint8_t * const OUT2 = (uint8_t *)0x0401;

            constexpr uint8_t sum_to(uint8_t n) {
                uint8_t acc = 0;
                for (uint8_t i = 0; i < n; i++) {
                    acc += i;
                }
                return acc;
            }

            void main(void) {
                *OUT1 = sum_to(5);
                *OUT2 = sum_to(6);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("#$a") || asm.contains("#10"),
            "Expected immediate 10 (#$a) for sum_to(5) in ASM:\n" + asm);
      assertTrue(asm.contains("#$f") || asm.contains("#15"),
            "Expected immediate 15 (#$f) for sum_to(6) in ASM:\n" + asm);
      assertFalse(asm.contains("sum_to:"),
            "Compile-time loop constexpr procedure must emit 0 bytes in ASM:\n" + asm);
      assertFalse(asm.contains("jsr "),
            "Did not expect runtime jsr for constexpr loop calls:\n" + asm);
   }

   @Test
   void testConstexprRecursionAndIfElseFoldedAtCompileTime() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const OUT = (uint8_t *)0x0400;

            constexpr uint8_t fib(uint8_t n) {
                if (n < 2) return n;
                return fib(n - 1) + fib(n - 2);
            }

            void main(void) {
                *OUT = fib(7);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("#$d") || asm.contains("#13"),
            "Expected immediate 13 (#$d) for fib(7) in ASM:\n" + asm);
      assertFalse(asm.contains("fib:"),
            "Compile-time-only recursive constexpr fib must emit 0 bytes in ASM:\n" + asm);
      assertFalse(asm.contains("jsr "),
            "Did not expect runtime recursive jsr in ASM:\n" + asm);
   }

   @Test
   void testConstexprDynamicFallbackAndSideEffectSafety() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const IN = (uint8_t *)0x0400;
            volatile uint8_t * const OUT1 = (uint8_t *)0x0401;
            volatile uint8_t * const OUT2 = (uint8_t *)0x0402;
            const uint8_t TABLE[4] = { 10, 20, 30, 40 };

            constexpr uint8_t scaled_lut(uint8_t idx) {
                return TABLE[idx] + 1;
            }

            void main(void) {
                *OUT1 = scaled_lut(2);
                *OUT2 = scaled_lut(*IN & 3);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("#$1f") || asm.contains("#31"),
            "Expected immediate 31 (#$1f) for constant call scaled_lut(2) in ASM:\n" + asm);
      assertTrue(asm.contains("TABLE"),
            "Dynamic fallback call scaled_lut(*IN & 3) should still compile cleanly:\n" + asm);
   }
}
