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

public class TestPackage4D {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4d_", ".c");
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
   void testSsa02_PointerWriteInvalidatesKnownZp() throws Exception {
      String code = """
            #include <stdint.h>
            void write_via_ptr(uint8_t *p) {
                *p = 42;
            }
            volatile uint8_t out;
            volatile uint8_t dummy;
            void main(void) {
                __zp uint8_t x = 0;
                write_via_ptr(&dummy);
                write_via_ptr(&x);
                x = 0;
                out = x;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // `x = 0` after `write_via_ptr(&x)` must NOT be eliminated, so `out` must not just read `x` without storing 0
      assertTrue(asm.contains("lda #0\n    sta.z x") || asm.contains("lda #0\n    sta out"),
            "Store x = 0 after pointer write must be preserved");
   }

   @Test
   void testSsa03_LoopBackEdgeNotReplacedByPreheader() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t out;
            void main(void) {
                __ma uint8_t x = 1;
                uint8_t init = x * 3;
                out = init;
                while ((x * 3) < 12) {
                    x++;
                }
                out = x;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // Loop must not be an unconditional infinite loop
      assertTrue(asm.contains("cmp #$c") || asm.contains("cmp #12"),
            "Loop condition (x * 3) < 12 must not be replaced by preheader init");
   }

   @Test
   void testSsa04_VoidVoidCallbackForcesStackCall() throws Exception {
      String code = """
            #include <stdint.h>
            uint8_t state = 0;
            void step_handler(void) { state = 99; }
            void (* volatile cb)(void) = &step_handler;
            volatile uint8_t out;
            void main(void) {
                state = 0;
                (*cb)();
                out = state;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // `out = state` must NOT be constant-propagated to `lda #0; sta out`
      assertFalse(asm.contains("lda #0\n    sta out"),
            "Global state modified by void(void) callback must not be constant-propagated to 0");
   }

   @Test
   void testSsa07_VolatileReadNotEliminated() throws Exception {
      String code = """
            #include <stdint.h>
            void clear_irq_latch(void) {
                volatile uint8_t *CIA1_ICR = (uint8_t *)0xDC0D;
                *CIA1_ICR;
            }
            void main(void) { clear_irq_latch(); }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("$dc0d"), "Volatile read *CIA1_ICR must not be eliminated. ASM:\n" + asm);
   }
}
