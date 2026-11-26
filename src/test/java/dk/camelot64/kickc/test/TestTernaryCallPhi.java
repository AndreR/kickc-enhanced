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

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests ternary expressions whose branches invoke functions that modify shared global variables.
 */
public class TestTernaryCallPhi {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_ternary_call_phi_", ".c");
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
   void testTernaryBothBranchesCallModifyingGlobal() throws Exception {
      String src = ""
            + "unsigned int g = 0;\n"
            + "unsigned int* const OUT = (unsigned int*)0x0400;\n"
            + "volatile unsigned int in_x = 100;\n"
            + "volatile unsigned int in_a = 10;\n"
            + "volatile unsigned int in_b = 20;\n"
            + "unsigned int helper(unsigned int v) {\n"
            + "    g = v;\n"
            + "    return v + 1;\n"
            + "}\n"
            + "unsigned int compute(unsigned int x) {\n"
            + "    unsigned int res = (x <= 50) ? (x + helper(in_a)) : (x + helper(in_b));\n"
            + "    return res + g;\n"
            + "}\n"
            + "void main() {\n"
            + "    OUT[0] = compute(in_x);\n"
            + "    OUT[1] = compute(in_a);\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTernaryBothBranchesDivMul32() throws Exception {
      String src = ""
            + "#include <multiply.h>\n"
            + "#include <division.h>\n"
            + "unsigned long* const OUT = (unsigned long*)0x0400;\n"
            + "volatile unsigned long in_x = 70000UL;\n"
            + "unsigned long scale(unsigned long x) {\n"
            + "    unsigned long res = (x <= 65535UL)\n"
            + "        ? (x + div32u16u(mul16u((unsigned int)x, 15), 100))\n"
            + "        : (x + mul16u((unsigned int)div32u16u(x, 100), 15));\n"
            + "    return res;\n"
            + "}\n"
            + "void main() {\n"
            + "    OUT[0] = scale(in_x);\n"
            + "    OUT[1] = scale(1000UL);\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTernaryOneBranchCall() throws Exception {
      String src = ""
            + "unsigned int g = 0;\n"
            + "unsigned int* const OUT = (unsigned int*)0x0400;\n"
            + "volatile unsigned int in_x = 100;\n"
            + "unsigned int helper(unsigned int v) {\n"
            + "    g = v;\n"
            + "    return v + 2;\n"
            + "}\n"
            + "unsigned int compute(unsigned int x) {\n"
            + "    unsigned int res = (x <= 50) ? (x + helper(x)) : (x + 5);\n"
            + "    return res + g;\n"
            + "}\n"
            + "void main() {\n"
            + "    OUT[0] = compute(in_x);\n"
            + "    OUT[1] = compute(10);\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testNestedTernaryWithCalls() throws Exception {
      String src = ""
            + "unsigned int g = 0;\n"
            + "unsigned int* const OUT = (unsigned int*)0x0400;\n"
            + "volatile unsigned int in_x = 100;\n"
            + "unsigned int helper(unsigned int v) {\n"
            + "    g = v;\n"
            + "    return v + 3;\n"
            + "}\n"
            + "unsigned int compute(unsigned int x) {\n"
            + "    unsigned int res = (x < 10) ? helper(1) : ((x < 50) ? helper(2) : helper(3));\n"
            + "    return res + g;\n"
            + "}\n"
            + "void main() {\n"
            + "    OUT[0] = compute(in_x);\n"
            + "    OUT[1] = compute(5);\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTernaryWithExplicitInlineCall() throws Exception {
      String src = ""
            + "unsigned int g = 0;\n"
            + "unsigned int* const OUT = (unsigned int*)0x0400;\n"
            + "volatile unsigned int in_x = 100;\n"
            + "inline unsigned int inc_g(unsigned int v) {\n"
            + "    g = v;\n"
            + "    return v + 7;\n"
            + "}\n"
            + "unsigned int compute(unsigned int x) {\n"
            + "    unsigned int res = (x <= 50) ? (x + inc_g(x)) : (x + inc_g(x + 1));\n"
            + "    return res + g;\n"
            + "}\n"
            + "void main() {\n"
            + "    OUT[0] = compute(in_x);\n"
            + "    OUT[1] = compute(10);\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTernaryChainedNonInlinedCalls() throws Exception {
      String src = ""
            + "unsigned int g = 0;\n"
            + "unsigned int* const OUT = (unsigned int*)0x0400;\n"
            + "volatile unsigned int in_x = 100;\n"
            + "unsigned int work(unsigned int v) {\n"
            + "    unsigned int acc = v;\n"
            + "    for (unsigned char i = 0; i < 4; i++) {\n"
            + "        acc += i;\n"
            + "        g += acc;\n"
            + "    }\n"
            + "    return acc;\n"
            + "}\n"
            + "unsigned int compute(unsigned int x) {\n"
            + "    unsigned int res = (x <= 50)\n"
            + "        ? work(work(work(x)))\n"
            + "        : (work(x) + work(x + 1));\n"
            + "    return res + g;\n"
            + "}\n"
            + "void main() {\n"
            + "    OUT[0] = compute(in_x);\n"
            + "    OUT[1] = compute(10);\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }
}
