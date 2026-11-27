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
 * Tests for packed sub-byte bitfields in __soa structs and __paged_soa 2-page SoA arrays.
 */
public class TestSoaBitfieldAndPaged {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_soa_paged_", ".c");
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
   void testSoaPackedSubByteBitfields() throws Exception {
      String code = """
            #include <stdint.h>
            struct PackedBits {
                uint8_t low : 5;
                uint8_t high : 3;
                uint8_t extra;
            };

            __soa struct PackedBits items[32];
            __soa struct PackedBits init_items[2] = {
                { 17, 5, 0x42 },
                { .high = 2, .low = 3, .extra = 0x99 }
            };

            volatile uint8_t idx = 3;
            volatile uint8_t val = 6;
            volatile uint16_t sz1, sz2;
            volatile uint8_t out_lo, out_hi, out_ex;

            void main(void) {
                sz1 = sizeof(items);
                sz2 = sizeof(init_items);
                items[idx].low = val;
                items[idx].high = 5;
                items[idx].low += 2;
                items[idx].high++;
                items[idx].extra = 0x55;
                out_lo = items[idx].low + init_items[0].low;
                out_hi = items[idx].high + init_items[1].high;
                out_ex = init_items[1].extra;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // sizeof(items) = 32 * 2 = 64 ($40), sizeof(init_items) = 2 * 2 = 4
      assertTrue(asm.contains("#<$40") && asm.contains("#>$40"), "Expected sizeof(items)==64 in ASM:\n" + asm);
      // init_items packed __bf_0 bytes: (5<<5)|17 = $b1, (2<<5)|3 = $43, followed by extra: $42, $99
      assertTrue(asm.contains("$b1") && asm.contains("$43") && asm.contains("$42") && asm.contains("$99"),
            "Expected packed bitfield SoA initializer planes in ASM:\n" + asm);
      // Sub-byte RMW masks: low is bits 0..4 (invMask $e0, valMask $1f), high is bits 5..7 (invMask $1f, valMask $07)
      assertTrue(asm.contains("and #$e0") && asm.contains("and #$1f"),
            "Expected bitfield RMW masks in ASM:\n" + asm);
      assertFalse(asm.contains("),y"), "Expected direct abs,X indexing without ZP pointer indirection:\n" + asm);
   }

   @Test
   void testSoaSignedSubByteBitfield() throws Exception {
      String code = """
            #include <stdint.h>
            struct SignedBits {
                int8_t delta : 4;
                uint8_t flags : 4;
            };
            __soa struct SignedBits items[16];
            volatile uint8_t idx = 4;
            volatile int8_t res;

            void main(void) {
                items[idx].delta = -3;
                items[idx].flags = 9;
                res = (items[idx].delta < 0) ? items[idx].delta : 42;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertFalse(asm.contains("lda #$2a\n    sta res"), "Signed SoA bitfield < 0 must not fold to false");
   }

   @Test
   void testPagedSoa512ElementsAndBitfields() throws Exception {
      String code = """
            #include <stdint.h>
            struct Node {
                uint8_t a : 5;
                uint8_t b : 3;
                uint8_t tag;
            };
            __paged_soa struct Node nodes[512];

            volatile uint8_t idx8 = 12;
            volatile uint16_t idx16 = 300;
            volatile uint8_t val = 7;
            volatile uint16_t sz, cnt;
            volatile uint8_t r_a, r_b, r_t;

            void main(void) {
                sz = sizeof(nodes);
                cnt = countof(nodes);

                // uint8_t index -> page 0 directly
                nodes[idx8].a = val;
                nodes[idx8].tag = 0x11;

                // constant index >= 256 -> page 1 directly
                nodes[300].b = 6;
                nodes[300].tag = 0x22;

                // uint16_t index -> 2-page hi-byte dispatch with abs,X
                nodes[idx16].a = 19;
                nodes[idx16].b += 1;
                nodes[idx16].tag++;

                r_a = nodes[idx16].a;
                r_b = nodes[idx16].b;
                r_t = nodes[idx16].tag + nodes[idx8].tag;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // Must use fast abs,X indexing on page 0 and page 1, never ZP pointer indirection
      assertTrue(asm.contains(",x"), "Expected abs,X indexed addressing in ASM:\n" + asm);
      assertFalse(asm.contains("),y"), "Expected no ZP (zp),Y pointer indirection for __paged_soa:\n" + asm);
      // 2-page offsets: tag plane starts at nodes+512 ($200), tag page 1 at nodes+768 ($300), bf page 1 at nodes+256 ($100)
      assertTrue(asm.contains("nodes+$100") || asm.contains("nodes+$200") || asm.contains("nodes+$300"),
            "Expected 2-page offsets for __paged_soa planes in ASM:\n" + asm);
   }

   @Test
   void testPagedSoaPartialInitAndValidation() throws Exception {
      String valid300 = """
            #include <stdint.h>
            struct Node {
                uint8_t a : 5;
                uint8_t b : 3;
                uint8_t tag;
            };
            __paged_soa struct Node init_nodes[300] = {
                { 5, 3, 0xaa }
            };
            volatile uint16_t idx = 260;
            volatile uint8_t out;
            void main(void) {
                init_nodes[idx].b = 4;
                out = init_nodes[0].a + init_nodes[idx].b + init_nodes[0].tag;
            }
            """;
      Program program = compileC(valid300);
      String asm = program.getAsm().toString();
      // Packed byte 0 = (3<<5)|5 = $65, tag page 0 starts at +300 ($12c), page 1 at +556 ($22c)
      assertTrue(asm.contains("$65") && asm.contains("$aa"), "Expected packed init data in ASM:\n" + asm);

      // > 256 on __soa must fail
      assertThrows(CompileError.class, () -> compileC("""
            struct S { char x; };
            __soa struct S arr[257];
            void main(void) { arr[0].x = 1; }
            """));

      // > 512 on __paged_soa must fail
      assertThrows(CompileError.class, () -> compileC("""
            struct S { char x; };
            __paged_soa struct S arr[513];
            void main(void) { arr[0].x = 1; }
            """));

      // Combining __soa and __paged_soa must fail
      assertThrows(CompileError.class, () -> compileC("""
            struct S { char x; };
            __soa __paged_soa struct S arr[16];
            void main(void) { arr[0].x = 1; }
            """));

      // Taking address of __paged_soa member or bitfield must fail
      assertThrows(CompileError.class, () -> compileC("""
            #include <stdint.h>
            struct S { uint8_t a : 4; uint8_t x; };
            __paged_soa struct S arr[300];
            volatile uint16_t i = 0;
            uint8_t * volatile p;
            void main(void) { p = &arr[i].x; }
            """));
   }
}
