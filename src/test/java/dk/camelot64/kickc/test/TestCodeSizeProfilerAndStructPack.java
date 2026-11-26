package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.CodeSizeProfiler;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.StructDefinition;
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

public class TestCodeSizeProfilerAndStructPack {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_prof_pack_", ".c");
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
   void testCodeSizeProfilerAnalyzeAndReports() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t out_a;
            volatile uint16_t out_b;
            const uint8_t lut[4] = { 10, 20, 30, 40 };

            __export uint16_t compute_sum(uint8_t n) {
                uint16_t acc = 0;
                for(uint8_t i = 0; i < n; i++) {
                    acc += lut[i & 3];
                }
                return acc;
            }

            void main(void) {
                out_a = lut[1];
                out_b = compute_sum(4);
                out_b += compute_sum(3);
            }
            """;
      Program program = compileC(code);
      CodeSizeProfiler.Report report = CodeSizeProfiler.analyze(program);

      assertTrue(report.getTotalBytes() > 0, "Total bytes must be positive");
      assertTrue(report.getTotalInstructions() > 0, "Total instructions must be positive");
      assertFalse(report.getSegments().isEmpty(), "Segment summary must not be empty");
      assertFalse(report.getProcedures().isEmpty(), "Procedure list must not be empty");
      assertFalse(report.getSourceLines().isEmpty(), "Source-line stats must not be empty");
      assertTrue(report.getTopProcedures().size() <= CodeSizeProfiler.TOP_LIMIT);
      assertTrue(report.getTopSourceLines().size() <= CodeSizeProfiler.TOP_LIMIT);

      boolean foundComputeSum = report.getProcedures().stream()
            .anyMatch(p -> p.getName().contains("compute_sum") && p.getBytes() > 0 && p.getInstructions() > 0);
      assertTrue(foundComputeSum, "Report must contain compute_sum procedure stats");

      int sumProcBytes = report.getProcedures().stream().mapToInt(CodeSizeProfiler.ProcedureStats::getBytes).sum();
      int sumSegBytes = report.getSegments().stream().mapToInt(CodeSizeProfiler.SegmentStats::getBytes).sum();
      assertEquals(report.getTotalBytes(), sumProcBytes, "Procedure bytes sum must equal totalBytes");
      assertEquals(report.getTotalBytes(), sumSegBytes, "Segment bytes sum must equal totalBytes");

      // Verify descending sort order of procedures and topSourceLines
      for(int i = 1; i < report.getProcedures().size(); i++) {
         assertTrue(report.getProcedures().get(i - 1).getBytes() >= report.getProcedures().get(i).getBytes());
      }
      for(int i = 1; i < report.getTopSourceLines().size(); i++) {
         assertTrue(report.getTopSourceLines().get(i - 1).getBytes() >= report.getTopSourceLines().get(i).getBytes());
      }

      Path tempDir = Files.createTempDirectory("kickc_csz_test_");
      Path cszPath = tempDir.resolve("out.csz");
      Path jsonPath = tempDir.resolve("out.json");
      try {
         CodeSizeProfiler.generateReports(program, cszPath, jsonPath);
         String cszText = Files.readString(cszPath);
         String jsonText = Files.readString(jsonPath);

         assertTrue(cszText.contains("Code Size Profile (.csz)"));
         assertTrue(cszText.contains("Segment Summary"));
         assertTrue(cszText.contains("Top 20 Largest Functions"));
         assertTrue(cszText.contains("Top 20 Costliest C Source Lines"));
         assertTrue(cszText.contains("compute_sum"));

         assertTrue(jsonText.startsWith("{"));
         assertTrue(jsonText.trim().endsWith("}"));
         assertTrue(jsonText.contains("\"totalBytes\": " + report.getTotalBytes()));
         assertTrue(jsonText.contains("\"segments\":"));
         assertTrue(jsonText.contains("\"topProcedures\":"));
         assertTrue(jsonText.contains("\"topSourceLines\":"));
         assertTrue(jsonText.contains("\"sourceLines\":"));
         assertTrue(jsonText.contains("compute_sum"));
      } finally {
         Files.deleteIfExists(cszPath);
         Files.deleteIfExists(jsonPath);
         Files.deleteIfExists(tempDir);
      }
   }

   @Test
   void testStructPackReorderOffsetsAndDefaultReset() throws Exception {
      String code = """
            #include <stdint.h>

            #pragma struct_pack(reorder)
            struct Packed {
                uint16_t w;
                uint8_t a;
                uint16_t w2;
                uint8_t b;
            };
            #pragma struct_pack(default)

            struct Normal {
                uint16_t w;
                uint8_t a;
                uint16_t w2;
                uint8_t b;
            };

            volatile uint8_t sink_u8;
            volatile uint16_t sink_u16;

            #pragma struct_model(classic)
            const struct Packed PACKED_CONST = { 0x1234, 0xAA, 0x5678, 0xBB };
            const struct Packed * volatile ptr_sink;
            #pragma struct_model(unwind)

            void main(void) {
                ptr_sink = &PACKED_CONST;
                const struct Packed *p = ptr_sink;
                uint8_t a = p->a;
                uint8_t b = p->b;
                uint16_t w = p->w;
                uint16_t w2 = p->w2;
                sink_u8 = a + b;
                sink_u16 = w + w2;
                struct Normal n = { 1, 2, 3, 4 };
                sink_u8 += n.a + n.b;
                sink_u16 += n.w + n.w2;
            }
            """;
      Program program = compileC(code);
      ProgramScope scope = program.getScope();

      StructDefinition packed = scope.getLocalStructDefinition("Packed");
      assertNotNull(packed);
      assertTrue(packed.isPackReorder());
      assertEquals(0L, packed.getMemberByteOffset(packed.getMember("a"), scope));
      assertEquals(1L, packed.getMemberByteOffset(packed.getMember("b"), scope));
      assertEquals(2L, packed.getMemberByteOffset(packed.getMember("w"), scope));
      assertEquals(4L, packed.getMemberByteOffset(packed.getMember("w2"), scope));

      StructDefinition normal = scope.getLocalStructDefinition("Normal");
      assertNotNull(normal);
      assertFalse(normal.isPackReorder());
      assertEquals(0L, normal.getMemberByteOffset(normal.getMember("w"), scope));
      assertEquals(2L, normal.getMemberByteOffset(normal.getMember("a"), scope));
      assertEquals(3L, normal.getMemberByteOffset(normal.getMember("w2"), scope));
      assertEquals(5L, normal.getMemberByteOffset(normal.getMember("b"), scope));

      // Verify PACKED_CONST bytes are emitted in packed layout order: a ($aa), b ($bb), w ($1234), w2 ($5678)
      String asm = program.getAsm().toString();
      int constIdx = asm.indexOf("PACKED_CONST:");
      assertTrue(constIdx >= 0, "PACKED_CONST label must exist in ASM");
      String tail = asm.substring(constIdx, Math.min(asm.length(), constIdx + 200));
      int idxA = tail.indexOf("$aa");
      int idxB = tail.indexOf("$bb");
      int idxW = tail.indexOf("$1234");
      int idxW2 = tail.indexOf("$5678");
      assertTrue(idxA >= 0 && idxB > idxA && idxW > idxB && idxW2 > idxW,
            "PACKED_CONST must emit fields in packed order (a, b, w, w2), got:\n" + tail);

      // Verify #pragma struct_model(unwind) properly switched struct model back to unwind
      assertFalse(program.getTargetPlatform().getVariableBuilderConfig().isStructModelClassic(),
            "#pragma struct_model(unwind) must set structModelClassic=false");
   }

   @Test
   void testStructPackReorderNonConstInitializerSemantics() throws Exception {
      String code = """
            #include <stdint.h>

            #pragma struct_pack(reorder)
            struct Packed {
                uint16_t w;
                uint8_t a;
                uint16_t w2;
                uint8_t b;
            };
            #pragma struct_pack(default)

            volatile uint16_t rw1 = 0x1122;
            volatile uint16_t rw2 = 0x3344;
            volatile uint8_t out_a;
            volatile uint8_t out_b;
            volatile uint16_t out_w;
            volatile uint16_t out_w2;

            #pragma struct_model(classic)
            void test_classic(void) {
                struct Packed p = { rw1, 0x11, rw2, 0x22 };
                struct Packed *pp = &p;
                out_a = pp->a;
                out_b = pp->b;
                out_w = pp->w;
                out_w2 = pp->w2;
            }
            #pragma struct_model(unwind)

            void main(void) {
                test_classic();
                struct Packed u = { rw1, 0x33, rw2, 0x44 };
                out_a = u.a;
                out_b = u.b;
                out_w = u.w;
                out_w2 = u.w2;
            }
            """;
      Program program = compileC(code);
      assertNotNull(program.getAsm());
   }
}
