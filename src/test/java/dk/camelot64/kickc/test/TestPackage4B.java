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

public class TestPackage4B {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source, boolean convenience) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4b_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         compiler.setEnableConvenience(convenience);
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
   void testTs05_BitwiseAndWithNarrowSignedPreservesHighByte() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint16_t w = 0x1235;
            volatile int8_t mask = -2;
            volatile uint16_t out;
            void main(void) {
                out = w & mask;
            }
            """;
      Program program = compileC(code, false);
      String asm = program.getAsm().toString();
      // High byte of w (w+1) must be referenced (sign-extended mask & w+1), not discarded to 8-bit SBYTE
      assertTrue(asm.contains("w+1"), "w & (int8_t)mask must preserve high byte of w! ASM:\n" + asm);
   }

   @Test
   void testTs06_MultiplyByNegativeConstantAndZero() throws Exception {
      String code = """
            #include <stdint.h>
            volatile int16_t x = 10;
            volatile int16_t y;
            volatile int16_t z;
            void main(void) {
                y = x * -3;
                z = x * (1 - 1);
            }
            """;
      Program program = compileC(code, false);
      String asm = program.getAsm().toString();
      // Must NOT compile x * -3 to plain y = x; must contain negation/subtraction
      assertTrue(asm.contains("eor #$ff") || asm.contains("sbc"), "x * -3 must negate result! ASM:\n" + asm);
      // z = x * 0 must store 0, not x
      assertTrue(asm.contains("lda #0"), "x * 0 must fold to 0! ASM:\n" + asm);
   }

   @Test
   void testTs09_ConstantShiftLeftPreservesFixedWidthType() throws Exception {
      String code = """
            #include <stdint.h>
            static_assert(((-100000L) << 1) == -200000L, "(-100000L) << 1 must stay SDWORD -200000L");
            volatile int32_t out;
            void main(void) {
                out = (-100000L) << 1;
            }
            """;
      assertDoesNotThrow(() -> compileC(code, false));
   }

   @Test
   void testTs10_ShiftByZeroAndBounds() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t x = 7;
            volatile uint8_t y;
            void main(void) {
                y = x >> 0;
                x = x << 0;
            }
            """;
      assertDoesNotThrow(() -> compileC(code, false));
   }

   @Test
   void testTs11_ModuloPowerOf2WordMaskAndSignedGuard() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint16_t w = 1000;
            volatile uint16_t r;
            void main(void) {
                r = w % 512;
            }
            """;
      Program program = compileC(code, false);
      String asm = program.getAsm().toString();
      // High byte must be masked with #$01 (511 = $01ff), not truncated to 8-bit BYTE!
      assertTrue(asm.contains("and #1") || asm.contains("and #$1") || asm.contains("and #>$1ff"),
            "w % 512 must mask high byte with $01 ($01ff)! ASM:\n" + asm);
   }

   @Test
   void testTs12_ConvenienceMixedSignAnd32BitSignedDiv() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint16_t a = 50000;
            volatile int8_t b = 2;
            volatile uint16_t q;
            volatile int32_t d = -100000L;
            volatile int32_t q32;
            void main(void) {
                q = a / b;
                q32 = d / 4;
            }
            """;
      Program program = compileC(code, true);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("div16u"), "uint16 / int8 must use unsigned div16u, not div16s! ASM:\n" + asm);
      assertTrue(asm.contains("div32s"), "int32 / 4 must use div32s, not div16s! ASM:\n" + asm);
   }
}
