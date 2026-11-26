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
 * Unit tests for selective loop unrolling pragmas:
 * #pragma unroll(N), #pragma unroll(full), #pragma unroll, and #pragma nounroll.
 */
public class TestLoopUnrollPragmas {

   @BeforeAll
   static void initTmp() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String sourceCode) throws Exception {
      Path tempFile = Files.createTempFile("kickc_unroll_pragma_", ".c");
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

   private static String extractMainAsm(Program program) {
      String asm = program.getAsm().toString();
      int mainIdx = asm.indexOf("main:");
      assertTrue(mainIdx >= 0, "Expected main: in ASM:\n" + asm);
      return asm.substring(mainIdx);
   }

   @Test
   void testPragmaUnrollCountFullMatch() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t buf[8];
            void main(void) {
                #pragma unroll(4)
                for (uint8_t i = 0; i < 4; i++) {
                    buf[i] = i + 10;
                }
            }
            """;
      Program program = compileC(code);
      String mainAsm = extractMainAsm(program);
      assertTrue(mainAsm.contains("buf+3"), "Expected unrolled store to buf+3 in ASM:\n" + mainAsm);
      assertFalse(mainAsm.contains("jmp "), "Fully unrolled loop must not contain loop jumps:\n" + mainAsm);
      assertFalse(mainAsm.contains("bne ") || mainAsm.contains("bcc "), "Fully unrolled loop must not contain loop branches:\n" + mainAsm);
   }

   @Test
   void testPragmaUnrollFullAndNoArg() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t outa[4];
            volatile uint8_t outb[4];
            void main(void) {
                #pragma unroll(full)
                for (uint8_t i = 0; i < 3; i++) {
                    outa[i] = i + 1;
                }
                #pragma unroll
                for (uint8_t j = 0; j < 3; j++) {
                    outb[j] = j + 5;
                }
            }
            """;
      Program program = compileC(code);
      String mainAsm = extractMainAsm(program);
      assertTrue(mainAsm.contains("outa+2"), "Expected #pragma unroll(full) to unroll outa+2:\n" + mainAsm);
      assertTrue(mainAsm.contains("outb+2"), "Expected #pragma unroll to unroll outb+2:\n" + mainAsm);
      assertFalse(mainAsm.contains("jmp ") || mainAsm.contains("bne ") || mainAsm.contains("bcc "),
            "Fully unrolled loops must not contain loop jumps/branches:\n" + mainAsm);
   }

   @Test
   void testPragmaNoUnrollOverridesInline() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t buf[8];
            void main(void) {
                #pragma nounroll
                inline for (uint8_t i = 0; i < 4; i++) {
                    buf[i] = i;
                }
            }
            """;
      Program program = compileC(code);
      String mainAsm = extractMainAsm(program);
      assertFalse(mainAsm.contains("buf+3"), "#pragma nounroll must prevent loop unrolling:\n" + mainAsm);
      assertTrue(mainAsm.contains("buf,x") || mainAsm.contains("buf,y") || mainAsm.contains("bcc ") || mainAsm.contains("bne "),
            "#pragma nounroll should preserve loop form:\n" + mainAsm);
   }

   @Test
   void testPragmaUnrollPartialCount() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t buf[8];
            void main(void) {
                #pragma unroll(2)
                for (uint8_t i = 0; i < 6; i++) {
                    buf[i] = i + 20;
                }
            }
            """;
      Program program = compileC(code);
      String mainAsm = extractMainAsm(program);
      assertTrue(mainAsm.contains("buf+1"), "Expected partial unroll of first 2 iterations (buf+1) in ASM:\n" + mainAsm);
   }
}
