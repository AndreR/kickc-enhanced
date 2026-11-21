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

public class TestPackage4A {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4a_", ".c");
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
   void testTs01_ZeroMinusXNotPlusX() throws Exception {
      String code = """
            #include <stdint.h>
            volatile int8_t x = 5;
            volatile int8_t y;
            void main(void) {
                y = 0 - x;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("eor #$ff") || asm.contains("sec") || asm.contains("sbc"),
            "0 - x must negate x, not copy +x");
   }

   @Test
   void testTs02_BitwiseNotLiteral() throws Exception {
      String code = """
            static_assert(((~0x0F) & 0xFF) == 0xF0, "Bitwise NOT failed");
            void main(void) {}
            """;
      assertNotNull(compileC(code).getAsm());
   }

   @Test
   void testTs03_SignedCastSignExtension() throws Exception {
      String code = """
            #include <stdint.h>
            static_assert(((int8_t)0xFF) < 0, "int8_t(0xFF) must be < 0");
            static_assert((int16_t)(int8_t)0xFF == -1, "sign extension failed");
            void main(void) {}
            """;
      assertNotNull(compileC(code).getAsm());
   }

   @Test
   void testTs08_UnsignedRightShiftWrap() throws Exception {
      String code = """
            #include <stdint.h>
            static_assert(((0UL - 1UL) >> 1) == 0x7FFFFFFFUL, "DWORD right shift failed");
            void main(void) {}
            """;
      assertNotNull(compileC(code).getAsm());
   }

   @Test
   void testTs13_DwordMaxValue() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint32_t x = 0xFFFFFFFFUL;
            volatile uint8_t ok;
            void main(void) {
                ok = (x <= 0xFFFFFFFFUL) ? 1 : 0;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertFalse(asm.contains("$100000000"), "x <= 0xFFFFFFFFUL must not rewrite to x < $100000000");
   }

   @Test
   void testTs14_SignedToUnsignedCastMaskInAsmFormat() throws Exception {
      String code = """
            #include <stdint.h>
            const int8_t neg = -1;
            volatile uint32_t wide = (uint32_t)(uint8_t)neg;
            void main(void) {}
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("$ff&") || asm.contains("$ff &") || asm.contains(".dword $ff"),
            "Nested (uint32_t)(uint8_t)neg must mask with $ff");
   }
}
