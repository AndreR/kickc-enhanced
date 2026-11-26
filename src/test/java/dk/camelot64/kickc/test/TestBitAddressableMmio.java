package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.Variable;
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
 * Tests for bit-addressable MMIO boolean variables declared with __bit(addr, n)
 * and lowered via {@link dk.camelot64.kickc.passes.Pass1BitAddressLowering}.
 */
public class TestBitAddressableMmio {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_bit_mmio_", ".c");
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
   void testBit7ReadFoldsToBitAndBplOrBmi() throws Exception {
      String code = """
            #include <stdint.h>
            #include <stdbool.h>
            __bit(0xD011, 7) volatile bool VIC_RASTER_MSB;
            volatile uint8_t * const OUT = (uint8_t *)0x0401;

            void main(void) {
                if (VIC_RASTER_MSB) *OUT = 1;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("bit $d011"), "Expected BIT $d011 instruction in ASM:\n" + asm);
      assertTrue(asm.contains("bpl ") || asm.contains("bmi "),
            "Expected bpl/bmi branch after BIT $d011:\n" + asm);
      assertFalse(asm.contains("and #$80"), "Did not expect unoptimized and #$80 in ASM:\n" + asm);
      for(Variable v : program.getScope().getAllVars(true)) {
         assertFalse(v.isBitAddressable(), "Lowered __bit variable should consume 0 RAM/ZP: " + v.getFullName());
      }
   }

   @Test
   void testBit6ReadFoldsToBitAndBvcOrBvs() throws Exception {
      String code = """
            #include <stdint.h>
            #include <stdbool.h>
            __bit(0xD011, 6) volatile bool VIC_ECM;
            volatile uint8_t * const OUT = (uint8_t *)0x0401;

            void main(void) {
                if (VIC_ECM) *OUT = 2;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("bit $d011"), "Expected BIT $d011 instruction in ASM:\n" + asm);
      assertTrue(asm.contains("bvc ") || asm.contains("bvs "),
            "Expected bvc/bvs branch after BIT $d011:\n" + asm);
      assertFalse(asm.contains("and #$40"), "Did not expect unoptimized and #$40 in ASM:\n" + asm);
   }

   @Test
   void testConstantBitWritesEmitOraAndAndRmw() throws Exception {
      String code = """
            #include <stdint.h>
            #include <stdbool.h>
            __bit(0xD011, 5) volatile bool VIC_BMM;

            void main(void) {
                VIC_BMM = true;
                VIC_BMM = false;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.matches("(?s).*lda\\s+\\$d011\\s+ora\\s+#\\$20\\s+sta\\s+\\$d011.*"),
            "Expected lda $d011; ora #$20; sta $d011 sequence in ASM:\n" + asm);
      assertTrue(asm.matches("(?s).*lda\\s+\\$d011\\s+and\\s+#\\$df\\s+sta\\s+\\$d011.*"),
            "Expected lda $d011; and #$df; sta $d011 sequence in ASM:\n" + asm);
   }

   @Test
   void testDynamicBitWriteCompilesCleanly() throws Exception {
      String code = """
            #include <stdint.h>
            #include <stdbool.h>
            __bit(0xD011, 5) volatile bool VIC_BMM;
            volatile uint8_t * const IN = (uint8_t *)0x0400;

            void main(void) {
                bool flag = (*IN & 1) != 0;
                VIC_BMM = flag;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("ora #$20"), "Expected ora #$20 on set path in ASM:\n" + asm);
      assertTrue(asm.contains("and #$df"), "Expected and #$df on clear path in ASM:\n" + asm);
      assertTrue(asm.contains("sta $d011"), "Expected store to $d011 in ASM:\n" + asm);
   }
}
