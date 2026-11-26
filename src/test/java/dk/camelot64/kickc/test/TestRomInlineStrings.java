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
 * Tests that inline string literals inside procedures declared under a ROM data segment
 * are emitted into the declared ROM data segment, while mutable local variables remain
 * in the default writable RAM data segment.
 */
public class TestRomInlineStrings {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private static final String ROM_LINKER_SCRIPT = ""
         + ".file [name=\"%O\", type=\"prg\", segments=\"Program\"]\n"
         + ".segmentdef Program [segments=\"Basic, Code, Data, RomCode, RomData\"]\n"
         + ".segmentdef Basic [start=$0801]\n"
         + ".segmentdef Code [start=%P]\n"
         + ".segmentdef Data [startAfter=\"Code\"]\n"
         + ".segmentdef RomCode [start=$8000]\n"
         + ".segmentdef RomData [startAfter=\"RomCode\"]\n"
         + ".segment Basic\n"
         + ":BasicUpstart(%E)\n";

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_rom_inline_str_", ".c");
      Path tempLd = Files.createTempFile(tempFile.getParent(), "kickc_rom_inline_str_", ".ld");
      Files.writeString(tempLd, ROM_LINKER_SCRIPT);
      Files.writeString(tempFile, "#pragma link(\"" + tempLd.getFileName().toString() + "\")\n" + source);
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
         Files.deleteIfExists(tempLd);
      }
   }

   /**
    * Finds the active .segment name immediately preceding the first occurrence of a needle in the ASM output.
    */
   private String findSegmentForSubstring(String asm, String needle) {
      int pos = asm.indexOf(needle);
      assertTrue(pos >= 0, "Expected substring not found in ASM: " + needle + "\nASM:\n" + asm);
      String prefix = asm.substring(0, pos);
      int lastSeg = prefix.lastIndexOf(".segment ");
      assertTrue(lastSeg >= 0, "No .segment directive found before: " + needle);
      int lineEnd = prefix.indexOf('\n', lastSeg);
      String segLine = (lineEnd >= 0 ? prefix.substring(lastSeg, lineEnd) : prefix.substring(lastSeg)).trim();
      return segLine.substring(".segment ".length()).trim();
   }

   @Test
   void testInlineStringInRomDataSegmentAndMutableLocalInRam() throws Exception {
      String src = ""
            + "#pragma segment_type(RomCode, ROM_BANK, 1)\n"
            + "#pragma segment_type(RomData, ROM_BANK, 1)\n"
            + "char* const SCREEN = (char*)0x0400;\n"
            + "void print_str(const char* s) {\n"
            + "    char i = 0;\n"
            + "    while (s[i]) { SCREEN[i] = s[i]; i++; }\n"
            + "}\n"
            + "#pragma code_seg(RomCode)\n"
            + "#pragma data_seg(RomData)\n"
            + "void rom_func(char flag) {\n"
            + "    __mem volatile char local_var = 1;\n"
            + "    local_var += flag;\n"
            + "    SCREEN[40] = local_var;\n"
            + "    print_str(\"ROM_HELLO\");\n"
            + "    if (flag) {\n"
            + "        print_str(\"ROM_NESTED\");\n"
            + "    }\n"
            + "}\n"
            + "#pragma code_seg(Code)\n"
            + "#pragma data_seg(Data)\n"
            + "void main() {\n"
            + "    rom_func(1);\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();

      assertEquals("RomData", findSegmentForSubstring(asm, "\"ROM_HELLO\""),
            "Inline string in ROM procedure should be placed in RomData segment");
      assertEquals("RomData", findSegmentForSubstring(asm, "\"ROM_NESTED\""),
            "Inline string in nested block of ROM procedure should be placed in RomData segment");
      assertEquals("Data", findSegmentForSubstring(asm, "local_var:"),
            "Mutable local variable in ROM procedure must remain in writable Data segment");
   }

   @Test
   void testForwardDeclaredProcedureUsesDefinitionRomDataSegment() throws Exception {
      String src = ""
            + "#pragma segment_type(RomCode, ROM_BANK, 1)\n"
            + "#pragma segment_type(RomData, ROM_BANK, 1)\n"
            + "char* const SCREEN = (char*)0x0400;\n"
            + "void print_str(const char* s) {\n"
            + "    char i = 0;\n"
            + "    while (s[i]) { SCREEN[i] = s[i]; i++; }\n"
            + "}\n"
            + "void rom_fwd(void);\n"
            + "#pragma code_seg(RomCode)\n"
            + "#pragma data_seg(RomData)\n"
            + "void rom_fwd(void) {\n"
            + "    print_str(\"FWD_ROM_STR\");\n"
            + "}\n"
            + "#pragma code_seg(Code)\n"
            + "#pragma data_seg(Data)\n"
            + "void main() {\n"
            + "    rom_fwd();\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();

      assertEquals("RomData", findSegmentForSubstring(asm, "\"FWD_ROM_STR\""),
            "Inline string in forward-declared ROM procedure should use definition's RomData segment");
   }

   @Test
   void testIdenticalInlineStringsInRamAndRomAreNotMergedAcrossSegments() throws Exception {
      String src = ""
            + "#pragma segment_type(RomCode, ROM_BANK, 1)\n"
            + "#pragma segment_type(RomData, ROM_BANK, 1)\n"
            + "char* const SCREEN = (char*)0x0400;\n"
            + "void print_str(const char* s) {\n"
            + "    char i = 0;\n"
            + "    while (s[i]) { SCREEN[i] = s[i]; i++; }\n"
            + "}\n"
            + "#pragma code_seg(RomCode)\n"
            + "#pragma data_seg(RomData)\n"
            + "void rom_fn(void) {\n"
            + "    print_str(\"SHARED_TEXT\");\n"
            + "}\n"
            + "#pragma code_seg(Code)\n"
            + "#pragma data_seg(Data)\n"
            + "void main() {\n"
            + "    print_str(\"SHARED_TEXT\");\n"
            + "    rom_fn();\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();

      int firstIdx = asm.indexOf("\"SHARED_TEXT\"");
      int secondIdx = asm.indexOf("\"SHARED_TEXT\"", firstIdx + 1);
      assertTrue(firstIdx >= 0 && secondIdx > firstIdx,
            "Identical inline strings in Data and RomData must remain separate instances");

      String seg1 = findSegmentForSubstring(asm.substring(0, firstIdx + "\"SHARED_TEXT\"".length()), "\"SHARED_TEXT\"");
      String seg2 = findSegmentForSubstring(asm.substring(firstIdx + 1), "\"SHARED_TEXT\"");
      assertTrue(("Data".equals(seg1) && "RomData".equals(seg2)) || ("RomData".equals(seg1) && "Data".equals(seg2)),
            "Expected one copy in Data and one copy in RomData, got: " + seg1 + " and " + seg2);
   }
}
