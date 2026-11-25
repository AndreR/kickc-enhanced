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
 * Tests for Package 5E: GEN-01, GEN-02, GEN-03.
 */
public class TestPackage5E {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_5e_", ".c");
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
   void testGen01_SignedTribyteOperationsSynthesize() throws Exception {
      String code = """
            #include <stdint.h>
            volatile __int24 a = 1000;
            volatile __int24 b = 2000;
            volatile __int24 c;
            void main(void) {
                c = a + b;
            }
            """;
      Program program = compileC(code);
      assertNotNull(program.getAsm());
   }

   @Test
   void testGen02_ByteAlignedAndTopByteShiftCanonicalized() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint16_t w = 0x1234;
            volatile uint8_t hi;
            volatile uint8_t hi_shifted;
            volatile int8_t hi_signed;
            void main(void) {
                uint16_t v = w;
                hi = (uint8_t)(v >> 8);
                hi_shifted = (uint8_t)(v >> 10);
                hi_signed = (int8_t)(v >> 8);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertNotNull(asm);
      // w >> 8 should read w+1 directly; w >> 10 should shift w+1 right by 2 (only 2 lsr, no 16-bit ror loop!)
      assertFalse(asm.contains("ror"),
            "(uint8_t)(w >> 8) and (uint8_t)(w >> 10) should canonicalize to single-byte operations without 16-bit ror! ASM:\n" + asm);
   }

   @Test
   void testGen03_MainMemPointerIndexedReadAndWrite() throws Exception {
      String code = """
            #include <stdint.h>
            #pragma zp_reserve(0x02..0xFD)
            volatile uint16_t addr = 0x0400;
            volatile uint8_t out;
            void main(void) {
                uint8_t * __mem mem_ptr = (uint8_t *)addr;
                mem_ptr[3] = 0x42;
                out = mem_ptr[3];
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertNotNull(asm);
      assertTrue(asm.contains("($fe),y"), "Main-memory pointer read/write should synthesize via ($fe),y! ASM:\n" + asm);
   }
}
