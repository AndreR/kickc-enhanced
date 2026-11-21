package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.CompileError;
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

public class TestPackage4C {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4c_", ".c");
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
   void testTs04_ThreeByteStructNotInt24() throws Exception {
      String code = """
            #include <stdint.h>
            struct Color { uint8_t r; uint8_t g; uint8_t b; };
            struct Color palette[4];
            volatile uint8_t out;
            uint8_t get_red(void) {
                return palette[0].r;
            }
            void main(void) { out = get_red(); }
            """;
      Program program = compileC(code);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTs07_SignedBitfieldAndMixedWidth() throws Exception {
      String code = """
            #include <stdint.h>
            struct Flags { int8_t delta : 4; };
            volatile int16_t res;
            void main(void) {
                struct Flags f;
                f.delta = -3;
                res = (f.delta < 0) ? f.delta : 99;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // If f.delta < 0 was folded to false, 99 ($63) would be stored unconditionally without -3 ($fd)
      assertFalse(asm.contains("lda #$63\n    sta res"), "Signed bitfield < 0 must not fold to false");
   }

   @Test
   void testTs15_FixedPointAndTribyteSuffixes() throws Exception {
      String code = """
            #include <stdint.h>
            typedef union { struct { uint8_t frac; int8_t integer; }; int16_t raw; } fixed16_8;
            volatile uint32_t t;
            volatile uint16_t b;
            void main(void) {
                fixed16_8 a = { .frac = 128, .integer = 3 };
                t = 123u24 + a.raw;
                b = 0b1.1f8;
            }
            """;
      Program program = compileC(code);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTs16_C23TypedEnumNullptrCountof() throws Exception {
      String code = """
            #include <stdint.h>
            enum Mode : uint16_t { MODE_EXT = 0x1234 };
            volatile enum Mode m = MODE_EXT;
            uint8_t *ptr = nullptr;
            uint8_t a[4] = { 1, 2, 3, 4 };
            uint8_t b[countof(a)];
            void main(void) {
                b[0] = a[0];
                if(ptr == nullptr) { b[1] = 1; }
            }
            """;
      Program program = compileC(code);
      assertNotNull(program.getAsm());
   }
}
