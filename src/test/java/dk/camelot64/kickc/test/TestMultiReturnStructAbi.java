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

public class TestMultiReturnStructAbi {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_multiret_", ".c");
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

   private static String extractProcBody(String asm, String procName) {
      String marker = procName + ":";
      int start = asm.indexOf(marker);
      assertTrue(start >= 0, "Procedure " + procName + " must exist in ASM");
      int end = asm.indexOf("\n}", start);
      if(end < 0) end = asm.length();
      return asm.substring(start, end);
   }

   @Test
   void testTwoByteStructReturnStructuredBindingRegistersAX() throws Exception {
      String code = """
            #include <stdint.h>

            struct Point {
                uint8_t x;
                uint8_t y;
            };

            volatile uint8_t in_v1 = 10;
            volatile uint8_t in_v2 = 20;
            volatile uint8_t out_x;
            volatile uint8_t out_y;

            __export struct Point get_point(uint8_t v) {
                struct Point p;
                p.x = v + 1;
                p.y = v + 2;
                return p;
            }

            void main(void) {
                auto [px, py] = get_point(in_v1);
                out_x = px;
                out_y = py;
                auto [px2, py2] = get_point(in_v2);
                out_x = px2;
                out_y = py2;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      String procBody = extractProcBody(asm, "get_point");

      assertFalse(procBody.contains("sta.z return_"), "2-byte struct return must not spill return_x/y via sta.z:\n" + procBody);
      assertFalse(procBody.contains("stx.z return_"), "2-byte struct return must not spill return_x/y via stx.z:\n" + procBody);
      assertFalse(procBody.contains("sty.z return_"), "2-byte struct return must not spill return_x/y via sty.z:\n" + procBody);

      String mainBody = extractProcBody(asm, "main");
      assertTrue(mainBody.contains("jsr get_point"), "main must call get_point:\n" + mainBody);
      assertTrue(mainBody.matches("(?s).*\\bst[axy](\\.z)?\\s+out_x\\b.*"),
            "main must unpack px into out_x:\n" + mainBody);
      assertTrue(mainBody.matches("(?s).*\\bst[axy](\\.z)?\\s+out_y\\b.*"),
            "main must unpack py into out_y:\n" + mainBody);
   }

   @Test
   void testThreeByteStructReturnStructuredBindingRegistersAXY() throws Exception {
      String code = """
            #include <stdint.h>

            struct Vec3 {
                uint8_t x;
                uint8_t y;
                uint8_t z;
            };

            volatile uint8_t in_v1 = 5;
            volatile uint8_t in_v2 = 9;
            volatile uint8_t out_x;
            volatile uint8_t out_y;
            volatile uint8_t out_z;

            __export struct Vec3 get_vec3(uint8_t v) {
                struct Vec3 r;
                r.x = v + 1;
                r.y = v + 2;
                r.z = v + 3;
                return r;
            }

            void main(void) {
                auto [x, y, z] = get_vec3(in_v1);
                out_x = x;
                out_y = y;
                out_z = z;
                auto [x2, y2, z2] = get_vec3(in_v2);
                out_x = x2;
                out_y = y2;
                out_z = z2;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      String procBody = extractProcBody(asm, "get_vec3");

      assertFalse(procBody.contains("sta.z return_"), "3-byte struct return must not spill return_x/y/z via sta.z:\n" + procBody);
      assertFalse(procBody.contains("stx.z return_"), "3-byte struct return must not spill return_x/y/z via stx.z:\n" + procBody);
      assertFalse(procBody.contains("sty.z return_"), "3-byte struct return must not spill return_x/y/z via sty.z:\n" + procBody);

      String mainBody = extractProcBody(asm, "main");
      assertTrue(mainBody.contains("jsr get_vec3"), "main must call get_vec3");
      assertTrue(mainBody.contains("out_x") && mainBody.contains("out_y") && mainBody.contains("out_z"),
            "main must unpack all 3 fields x, y, z");
   }
}
