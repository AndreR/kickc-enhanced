package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass2ConstantArithmeticSynthesizer;
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
 * Unit and end-to-end compiler tests for {@link Pass2ConstantArithmeticSynthesizer}:
 * - NAF/Binary & factored shift-add-sub constant multiplication
 * - Compile-time verified reciprocal unsigned 8-bit division & modulo without helper libraries.
 */
public class TestConstantArithmeticSynthesis {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileWithoutConvenience(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_arith_synth_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         Program program = compiler.getProgram();
         program.setEnableConvenience(false);
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
   void testMultiplyPlansBitExactForAllByteInputs() {
      long[] constants = { 3, 5, 6, 7, 9, 10, 12, 15, 24, 40 };
      for(long c : constants) {
         Pass2ConstantArithmeticSynthesizer.MulPlan plan8 = Pass2ConstantArithmeticSynthesizer.findMultiplyPlan(c, 8);
         Pass2ConstantArithmeticSynthesizer.MulPlan plan16 = Pass2ConstantArithmeticSynthesizer.findMultiplyPlan(c, 16);
         assertNotNull(plan8, "Expected 8-bit multiply plan for C=" + c);
         assertNotNull(plan16, "Expected 16-bit multiply plan for C=" + c);
         assertTrue(plan8.addSubCount() <= 4, "Too many steps for C=" + c);
         assertTrue(plan16.addSubCount() <= 4, "Too many steps for C=" + c);
         for(int x = 0; x < 256; x++) {
            assertEquals((x * c) & 0xFFL, plan8.eval(x) & 0xFFL, "8-bit mismatch for x=" + x + ", C=" + c);
            assertEquals(x * c, plan16.eval(x), "16-bit mismatch for x=" + x + ", C=" + c);
         }
      }
   }

   @Test
   void testReciprocalDivAndModBitExactForAllByteInputs() {
      long[] divisors = { 3, 5, 6, 9, 10, 12, 15, 24, 40 };
      for(long d : divisors) {
         Pass2ConstantArithmeticSynthesizer.Reciprocal recip = Pass2ConstantArithmeticSynthesizer.findReciprocal(d);
         assertNotNull(recip, "Expected exact 16-bit reciprocal (M, S) for divisor C=" + d);
         assertTrue(recip.s >= 8 && recip.s <= 15, "Shift S out of [8, 15] for C=" + d);

         Pass2ConstantArithmeticSynthesizer.MulPlan mPlan = Pass2ConstantArithmeticSynthesizer.isPowerOfTwo(recip.m)
               ? null
               : Pass2ConstantArithmeticSynthesizer.findMultiplyPlan(recip.m, 16);
         if(!Pass2ConstantArithmeticSynthesizer.isPowerOfTwo(recip.m)) {
            assertNotNull(mPlan, "Expected 16-bit shift-add plan for M=" + recip.m + " (divisor C=" + d + ")");
         }

         for(int x = 0; x < 256; x++) {
            long prod = (mPlan != null) ? mPlan.eval(x) : ((long) x << Long.numberOfTrailingZeros(recip.m));
            assertEquals((long) x * recip.m, prod, "Multiplier mismatch for x=" + x + ", M=" + recip.m);
            long q = (prod >>> recip.s) & 0xFFL;
            assertEquals(x / d, q, "Division mismatch for x=" + x + ", d=" + d);
            long rem = x - q * d;
            assertEquals(x % d, rem, "Modulo mismatch for x=" + x + ", d=" + d);
         }
      }

      // Divisor 7: if a reciprocal is returned it must be exact; otherwise null safely triggers fallback
      Pass2ConstantArithmeticSynthesizer.Reciprocal r7 = Pass2ConstantArithmeticSynthesizer.findReciprocal(7);
      if(r7 != null) {
         assertTrue(Pass2ConstantArithmeticSynthesizer.isExactReciprocal(7, r7.m, r7.s));
      }
   }

   @Test
   void testCompileConstantMultiplicationsWithoutConvenienceOrMultiplyHeader() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const IN = (uint8_t *)0x0400;
            volatile uint8_t * const OUT8_A = (uint8_t *)0x0401;
            volatile uint8_t * const OUT8_B = (uint8_t *)0x0402;
            volatile uint16_t * const OUT16 = (uint16_t *)0x0410;

            void main(void) {
                uint8_t x = *IN;
                *OUT8_A = x * 7;
                *OUT8_B = x * 10;
                *OUT16 = (uint16_t)x * 40;
            }
            """;
      Program program = compileWithoutConvenience(code);
      String asm = program.getAsm().toString();
      assertFalse(asm.contains("mul8u") || asm.contains("mul16u"),
            "Did not expect runtime multiply helpers in ASM:\n" + asm);
      assertTrue(asm.contains("sbc"), "Expected subtraction from NAF (x<<3)-x for x*7 in ASM:\n" + asm);
      assertTrue(asm.contains("adc"), "Expected addition for x*10 / x*40 in ASM:\n" + asm);
   }

   @Test
   void testCompileUnsignedByteDivAndModWithoutConvenienceOrDivisionHeader() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const IN = (uint8_t *)0x0400;
            volatile uint8_t * const OUT_D3 = (uint8_t *)0x0401;
            volatile uint8_t * const OUT_D5 = (uint8_t *)0x0402;
            volatile uint8_t * const OUT_D10 = (uint8_t *)0x0403;
            volatile uint8_t * const OUT_M10 = (uint8_t *)0x0404;

            void main(void) {
                uint8_t x = *IN;
                *OUT_D3 = x / 3;
                *OUT_D5 = x / 5;
                *OUT_D10 = x / 10;
                *OUT_M10 = x % 10;
            }
            """;
      Program program = compileWithoutConvenience(code);
      String asm = program.getAsm().toString();
      assertFalse(asm.contains("div8u") || asm.contains("rem8u") || asm.contains("mul8u") || asm.contains("mul16u"),
            "Did not expect runtime div/rem/mul helpers in ASM:\n" + asm);
      assertTrue(asm.contains("lsr"), "Expected shift-right scaling in ASM for reciprocal div/mod:\n" + asm);
   }
}
