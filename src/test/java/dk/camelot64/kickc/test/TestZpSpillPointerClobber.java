package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
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
 * Tests Pass 4 zero-page spilling and post-spill CPU register clobber handling:
 * 1. Indirectly dereferenced pointers are prioritized for zero-page retention over non-pointer scalars.
 * 2. When zero page is exhausted and a pointer must spill to main memory after register uplift,
 *    any live CPU register clobbered by the resulting main-memory pointer fragment is demoted to memory
 *    instead of failing Pass4AssertNoCpuClobber.
 */
public class TestZpSpillPointerClobber {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_zp_spill_ptr_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         compiler.setUpliftCombinations(20);
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
   void testDereferencedPointerPrioritizedInZpOverScalars() throws Exception {
      // Leave only 4 ZP bytes ($02-$05) available so scalars must spill while the dereferenced pointer stays in ZP.
      String src = ""
            + "#pragma reserve_zp_range(0x06, 0xff)\n"
            + "volatile unsigned int acc1 = 0;\n"
            + "volatile unsigned int acc2 = 0;\n"
            + "volatile unsigned int acc3 = 0;\n"
            + "char* volatile dst_ptr = (char*)0x0400;\n"
            + "void fill_line(char* p, char len, char ch) {\n"
            + "    for (char i = 0; i < len; i++) {\n"
            + "        p[i] = ch;\n"
            + "    }\n"
            + "}\n"
            + "void main() {\n"
            + "    for (char k = 0; k < 10; k++) {\n"
            + "        acc1 += k;\n"
            + "        acc2 += k + 1;\n"
            + "        acc3 += k + 2;\n"
            + "    }\n"
            + "    fill_line(dst_ptr, 40, (char)acc1);\n"
            + "    *((char*)0x0428) = (char)(acc2 + acc3);\n"
            + "}\n";

      Program program = compileC(src);
      assertNotNull(program.getAsm(), "Expected ASM to be generated");

      boolean foundZpPointer = false;
      for (Variable var : program.getScope().getAllVariables(true)) {
         if (var.getType() instanceof SymbolTypePointer
               && var.getLocalName().startsWith("p")
               && var.getAllocation() instanceof Registers.RegisterZpMem) {
            foundZpPointer = true;
         }
      }
      assertTrue(foundZpPointer, "Expected dereferenced pointer 'p' to retain zero-page allocation over scalars");
   }

   @Test
   void testForcedPointerSpillDemotesClobberedCpuRegister() throws Exception {
      // Reserve $02-$FD so zero page is completely full (only $FE/$FF scratch remain),
      // forcing indirect pointers to spill to main memory while A, X, Y are all live across p[i] = ch.
      String src = ""
            + "#pragma reserve_zp_range(0x02, 0xfd)\n"
            + "char* volatile base_ptr = (char*)0x0400;\n"
            + "volatile char sink = 0;\n"
            + "void write_pattern(char* p, char count, char start_ch, char step) {\n"
            + "    char ch = start_ch;\n"
            + "    char idx = 0;\n"
            + "    char rem = count;\n"
            + "    while (rem > 0) {\n"
            + "        p[idx] = ch;\n"
            + "        ch += step;\n"
            + "        idx++;\n"
            + "        rem--;\n"
            + "    }\n"
            + "    sink = ch + idx;\n"
            + "}\n"
            + "void main() {\n"
            + "    write_pattern(base_ptr, 20, 1, 3);\n"
            + "    write_pattern(base_ptr + 40, 15, 2, 5);\n"
            + "}\n";

      Program program = compileC(src);
      assertNotNull(program.getAsm(), "Expected ASM to be generated without register clobber errors");

      boolean foundMainMemPointer = false;
      for (Variable var : program.getScope().getAllVariables(true)) {
         if (var.getType() instanceof SymbolTypePointer
               && var.getLocalName().startsWith("p")
               && var.getAllocation() instanceof Registers.RegisterMainMem) {
            foundMainMemPointer = true;
         }
      }
      assertTrue(foundMainMemPointer, "Expected pointer 'p' to spill to main memory when zero page is exhausted");
   }
}
