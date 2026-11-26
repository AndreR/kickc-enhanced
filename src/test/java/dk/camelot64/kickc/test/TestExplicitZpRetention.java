package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.CompileError;
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
 * Tests Pass 4 zero-page retention for explicit __zp variables and variables referenced in inline ASM:
 * 1. Explicit __zp pointers and inline ASM pointers receive top retention priority (tier 4),
 *    staying in zero page while C-level dereferenced pointers and scalars spill to main memory.
 * 2. Exhausting zero page for an explicit __zp or inline-ASM indirect pointer emits a CompileError
 *    instead of silently spilling to main memory.
 * 3. Non-explicit variables still spill cleanly to main memory on zero-page overflow.
 */
public class TestExplicitZpRetention {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_explicit_zp_", ".c");
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
   void testExplicitZpInlineAsmPointerRetainedInZpUnderPressure() throws Exception {
      // Reserve $04-$FF (only $02-$03 plus $FE-$FF scratch remain if needed):
      // asm_ptr (__zp + used in lda (asm_ptr),y) must keep ZP $02-$03 while scalars and C pointer spill.
      String src = ""
            + "#pragma reserve_zp_range(0x04, 0xfd)\n"
            + "__zp char* volatile asm_ptr = (char*)0x0400;\n"
            + "char* volatile c_ptr = (char*)0x0500;\n"
            + "volatile unsigned int s1 = 1;\n"
            + "volatile unsigned int s2 = 2;\n"
            + "volatile unsigned int s3 = 3;\n"
            + "void use_c_ptr(char* p) {\n"
            + "    for (char i = 0; i < 8; i++) {\n"
            + "        p[i] = i;\n"
            + "    }\n"
            + "}\n"
            + "void main() {\n"
            + "    for (char k = 0; k < 5; k++) {\n"
            + "        s1 += k;\n"
            + "        s2 += k + 1;\n"
            + "        s3 += k + 2;\n"
            + "    }\n"
            + "    use_c_ptr(c_ptr);\n"
            + "    asm {\n"
            + "        ldy #0\n"
            + "        lda (asm_ptr),y\n"
            + "        sta $0428\n"
            + "    }\n"
            + "    *((char*)0x0429) = (char)(s2 + s3);\n"
            + "}\n";

      Program program = compileC(src);
      assertNotNull(program.getAsm(), "Expected ASM to be generated");

      Variable asmPtr = program.getScope().getLocalVariable("asm_ptr");
      assertNotNull(asmPtr, "Expected asm_ptr variable");
      assertTrue(asmPtr.isExplicitZp(), "Expected asm_ptr to be marked explicitZp");
      assertTrue(asmPtr.getAllocation() instanceof Registers.RegisterZpMem,
            "Expected __zp pointer asm_ptr to stay in zero page, got: " + asmPtr.getAllocation());

      boolean foundMainMemScalar = false;
      for (Variable var : program.getScope().getAllVariables(true)) {
         if (!(var.getType() instanceof SymbolTypePointer)
               && (var.getLocalName().startsWith("s1") || var.getLocalName().startsWith("s2") || var.getLocalName().startsWith("s3"))
               && var.getAllocation() instanceof Registers.RegisterMainMem) {
            foundMainMemScalar = true;
         }
      }
      assertTrue(foundMainMemScalar, "Expected normal scalars to spill to main memory when ZP is full");
   }

   @Test
   void testInlineAsmIndirectPointerWithoutExplicitZpPrioritizedOverTier3() throws Exception {
      // Only $02-$03 available for non-scratch ZP; inline-ASM indirect pointer (tier 4) must outrank C PointerDereference (tier 3).
      String src = ""
            + "#pragma reserve_zp_range(0x04, 0xfd)\n"
            + "char* volatile asm_ptr = (char*)0x0400;\n"
            + "char* volatile c_ptr = (char*)0x0500;\n"
            + "void touch(char* p) {\n"
            + "    p[0] = 42;\n"
            + "}\n"
            + "void main() {\n"
            + "    touch(c_ptr);\n"
            + "    asm {\n"
            + "        ldy #0\n"
            + "        lda (asm_ptr),y\n"
            + "        sta $0428\n"
            + "    }\n"
            + "}\n";

      Program program = compileC(src);
      Variable asmPtr = program.getScope().getLocalVariable("asm_ptr");
      assertNotNull(asmPtr);
      assertTrue(asmPtr.getAllocation() instanceof Registers.RegisterZpMem,
            "Expected inline-ASM indirect pointer asm_ptr to retain ZP over C dereference, got: " + asmPtr.getAllocation());
   }

   @Test
   void testExplicitZpOverflowThrowsCompileError() {
      // Reserve all of zero page ($02-$FF) so an explicit __zp variable cannot fit.
      String src = ""
            + "#pragma reserve_zp_range(0x02, 0xff)\n"
            + "__zp char* volatile asm_ptr = (char*)0x0400;\n"
            + "void main() {\n"
            + "    asm {\n"
            + "        ldy #0\n"
            + "        lda (asm_ptr),y\n"
            + "        sta $0428\n"
            + "    }\n"
            + "}\n";

      CompileError error = assertThrows(CompileError.class, () -> compileC(src),
            "Expected CompileError when explicit __zp variable cannot fit in zero page");
      assertTrue(error.getMessage().contains("__zp") || error.getMessage().toLowerCase().contains("zero-page"),
            "Expected clear error message about __zp / zero-page exhaustion, got: " + error.getMessage());
   }

   @Test
   void testInlineAsmIndirectPointerOverflowThrowsCompileError() {
      // Reserve all of zero page ($02-$FF) so even a non-__zp pointer used in lda (ptr),y cannot fit.
      String src = ""
            + "#pragma reserve_zp_range(0x02, 0xff)\n"
            + "char* volatile asm_ptr = (char*)0x0400;\n"
            + "void main() {\n"
            + "    asm {\n"
            + "        ldy #0\n"
            + "        lda (asm_ptr),y\n"
            + "        sta $0428\n"
            + "    }\n"
            + "}\n";

      CompileError error = assertThrows(CompileError.class, () -> compileC(src),
            "Expected CompileError when inline-ASM indirect pointer cannot fit in zero page");
      assertTrue(error.getMessage().toLowerCase().contains("zero-page"),
            "Expected clear error message about zero-page exhaustion, got: " + error.getMessage());
   }

   @Test
   void testNormalScalarsStillSpillWithoutError() throws Exception {
      // Reserve $06-$FF (4 ZP bytes free); multiple volatile scalars must spill cleanly without error.
      String src = ""
            + "#pragma reserve_zp_range(0x06, 0xff)\n"
            + "volatile unsigned int a = 1;\n"
            + "volatile unsigned int b = 2;\n"
            + "volatile unsigned int c = 3;\n"
            + "volatile unsigned int d = 4;\n"
            + "void main() {\n"
            + "    for (char i = 0; i < 4; i++) {\n"
            + "        a += i;\n"
            + "        b += i;\n"
            + "        c += i;\n"
            + "        d += i;\n"
            + "    }\n"
            + "    *((unsigned int*)0x0400) = a + b + c + d;\n"
            + "}\n";

      Program program = compileC(src);
      assertNotNull(program.getAsm(), "Expected ASM to be generated when non-explicit ZP scalars spill");
      boolean foundSpilled = false;
      for (Variable var : program.getScope().getAllVariables(true)) {
         if (var.getAllocation() instanceof Registers.RegisterMainMem) {
            foundSpilled = true;
            break;
         }
      }
      assertTrue(foundSpilled, "Expected at least one scalar variable to spill to main memory");
   }
}
