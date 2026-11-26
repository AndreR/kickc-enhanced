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

/**
 * Tests nested switch statements (including inside/outside enclosing loops)
 * to ensure no orphaned intermediate labels remain in the symbol table.
 */
public class TestNestedSwitch {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_nested_switch_", ".c");
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
   void testNestedSwitchInCases() throws Exception {
      String src = ""
            + "char* const SCREEN = (char*)0x0400;\n"
            + "volatile char tab = 0;\n"
            + "volatile char key = 1;\n"
            + "void dispatch() {\n"
            + "    switch (tab) {\n"
            + "        case 0:\n"
            + "            switch (key) {\n"
            + "                case 1:\n"
            + "                    SCREEN[0] = 1;\n"
            + "                    break;\n"
            + "                case 2:\n"
            + "                    SCREEN[0] = 2;\n"
            + "                    break;\n"
            + "                default:\n"
            + "                    SCREEN[0] = 3;\n"
            + "                    break;\n"
            + "            }\n"
            + "            break;\n"
            + "        case 1:\n"
            + "            switch (key) {\n"
            + "                case 1:\n"
            + "                    SCREEN[1] = 4;\n"
            + "                    break;\n"
            + "                default:\n"
            + "                    SCREEN[1] = 5;\n"
            + "                    break;\n"
            + "            }\n"
            + "            break;\n"
            + "        default:\n"
            + "            SCREEN[2] = 6;\n"
            + "            break;\n"
            + "    }\n"
            + "}\n"
            + "void main() {\n"
            + "    dispatch();\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testTripleNestedSwitch() throws Exception {
      String src = ""
            + "char* const SCREEN = (char*)0x0400;\n"
            + "volatile char a = 0;\n"
            + "volatile char b = 1;\n"
            + "volatile char c = 2;\n"
            + "void main() {\n"
            + "    switch (a) {\n"
            + "        case 0:\n"
            + "            switch (b) {\n"
            + "                case 1:\n"
            + "                    switch (c) {\n"
            + "                        case 2:\n"
            + "                            SCREEN[0] = 42;\n"
            + "                            break;\n"
            + "                        default:\n"
            + "                            SCREEN[0] = 99;\n"
            + "                            break;\n"
            + "                    }\n"
            + "                    break;\n"
            + "                default:\n"
            + "                    SCREEN[1] = 10;\n"
            + "                    break;\n"
            + "            }\n"
            + "            break;\n"
            + "        default:\n"
            + "            SCREEN[2] = 20;\n"
            + "            break;\n"
            + "    }\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testNestedSwitchInsideLoopWithContinue() throws Exception {
      String src = ""
            + "char* const SCREEN = (char*)0x0400;\n"
            + "volatile char mode = 0;\n"
            + "volatile char sub = 1;\n"
            + "void main() {\n"
            + "    for (char i = 0; i < 8; i++) {\n"
            + "        switch (mode) {\n"
            + "            case 0:\n"
            + "                switch (sub) {\n"
            + "                    case 1:\n"
            + "                        SCREEN[i] = 1;\n"
            + "                        continue;\n"
            + "                    default:\n"
            + "                        SCREEN[i] = 2;\n"
            + "                        break;\n"
            + "                }\n"
            + "                break;\n"
            + "            default:\n"
            + "                SCREEN[i] = 3;\n"
            + "                break;\n"
            + "        }\n"
            + "        SCREEN[i + 8] = 9;\n"
            + "    }\n"
            + "}\n";
      Program program = compileC(src);
      assertNotNull(program.getAsm());
   }

   @Test
   void testContinueInNestedSwitchOutsideLoopThrows() {
      String src = ""
            + "char* const SCREEN = (char*)0x0400;\n"
            + "volatile char a = 0;\n"
            + "volatile char b = 1;\n"
            + "void main() {\n"
            + "    switch (a) {\n"
            + "        case 0:\n"
            + "            switch (b) {\n"
            + "                case 1:\n"
            + "                    continue;\n"
            + "            }\n"
            + "            break;\n"
            + "    }\n"
            + "}\n";
      CompileError err = assertThrows(CompileError.class, () -> compileC(src));
      assertTrue(err.getMessage().contains("Continue not inside a loop"),
            "Expected 'Continue not inside a loop' error, got: " + err.getMessage());
   }
}
