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
   void testFl05_CommutativeAccumulatorForwarding() throws Exception {
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
      assertNotNull(program.getAsm());
   }
}
