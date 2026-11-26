package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.Procedure;
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
 * Unit tests for scoped convenience control:
 * #pragma convenience(push, on|off), #pragma convenience(pop),
 * __attribute__((convenience)), and __convenience.
 */
public class TestConvenienceControl {

   @BeforeAll
   static void initTmp() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String sourceCode) throws Exception {
      Path tempFile = Files.createTempFile("kickc_conv_ctrl_", ".c");
      Files.writeString(tempFile, sourceCode);
      try {
         Compiler compiler = new Compiler();
         assertFalse(compiler.isEnableConvenience(), "Global -fconvenience must be disabled by default");
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
   void testScopedPragmaPushPopWithoutGlobalFlag() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t out[5];

            #pragma convenience(push, on)
            __export uint8_t div10_scoped(uint8_t x) {
                return x / 10;
            }
            __export void multi_div10_scoped(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
                out[0] = a / 10;
                out[1] = b / 10;
                out[2] = c / 10;
                out[3] = d / 10;
            }
            #pragma convenience(pop)

            uint8_t outside_fn(uint8_t x) {
                uint8_t acc = x;
                for (uint8_t i = 0; i < 3; i++) acc += i;
                return acc;
            }

            void main(void) {
                multi_div10_scoped(20, 30, 40, 50);
                out[4] = div10_scoped(out[0]) + outside_fn(out[1]) + outside_fn(out[2]);
            }
            """;
      Program program = compileC(code);
      assertFalse(program.isEnableConvenience(), "Scoped push/pop must not enable global convenience flag");

      Procedure scoped = program.getScope().getLocalProcedure("div10_scoped");
      assertNotNull(scoped);
      assertTrue(scoped.isDeclaredConvenience(), "Procedure inside push(on) must have declaredConvenience=true");

      Procedure multi = program.getScope().getLocalProcedure("multi_div10_scoped");
      assertNotNull(multi);
      assertTrue(multi.isDeclaredConvenience(), "Procedure inside push(on) must have declaredConvenience=true");

      Procedure outside = program.getScope().getLocalProcedure("outside_fn");
      assertNotNull(outside);
      assertFalse(outside.isDeclaredConvenience(), "Procedure after pop must have declaredConvenience=false");

      Procedure helper = program.getScope().getLocalProcedure("__div8u_const_10");
      assertNotNull(helper, "Expected Pass1ConvenienceMathRewrite to synthesize __div8u_const_10 for scoped procedure");
   }

   @Test
   void testAttributeConvenienceAndKeywordWithoutGlobalFlag() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t in_a, in_b;
            volatile uint8_t out[4];

            __attribute__((convenience)) uint8_t div10(uint8_t x) {
                uint8_t q = x / 10;
                uint8_t r = q + 1;
                return r;
            }

            __convenience uint8_t div10_kw(uint8_t x) {
                uint8_t q = x / 10;
                uint8_t r = q + 2;
                return r;
            }

            void main(void) {
                out[0] = div10(in_a);
                out[1] = div10(in_b);
                out[2] = div10_kw(in_a);
                out[3] = div10_kw(in_b);
            }
            """;
      Program program = compileC(code);
      assertFalse(program.isEnableConvenience());

      Procedure div10 = program.getScope().getLocalProcedure("div10");
      assertNotNull(div10);
      assertTrue(div10.isDeclaredConvenience(), "__attribute__((convenience)) must set declaredConvenience=true");

      Procedure div10Kw = program.getScope().getLocalProcedure("div10_kw");
      assertNotNull(div10Kw);
      assertTrue(div10Kw.isDeclaredConvenience(), "__convenience must set declaredConvenience=true");

      Procedure mainProc = program.getScope().getLocalProcedure("main");
      assertNotNull(mainProc);
      assertFalse(mainProc.isDeclaredConvenience(), "main() without attribute must remain declaredConvenience=false");
   }

   @Test
   void testScopedDynamicDivisionOnlyActiveInsidePushPop() throws Exception {
      String scopedOk = """
            #include <stdint.h>
            #include <division.h>
            volatile uint8_t out;

            #pragma convenience(push, on)
            __export uint8_t div_dyn_ok(uint8_t a, uint8_t b) {
                return a / b;
            }
            #pragma convenience(pop)

            uint8_t regular_fn(uint8_t a) {
                uint8_t t = a;
                for (uint8_t i = 0; i < 3; i++) t += i;
                return t;
            }

            void main(void) {
                out = div_dyn_ok(42, 6) + regular_fn(out) + regular_fn(out + 1);
            }
            """;
      Program okProgram = compileC(scopedOk);
      assertTrue(okProgram.getScope().getLocalProcedure("div_dyn_ok").isDeclaredConvenience());
      assertFalse(okProgram.getScope().getLocalProcedure("regular_fn").isDeclaredConvenience());

      String outsideRejected = """
            #include <stdint.h>
            #include <division.h>
            volatile uint8_t in_a, in_b, out;

            #pragma convenience(push, on)
            #pragma convenience(pop)

            uint8_t div_dyn_outside(uint8_t a, uint8_t b) {
                return a / b;
            }

            void main(void) {
                out = div_dyn_outside(in_a, in_b);
            }
            """;
      assertThrows(CompileError.class, () -> compileC(outsideRejected),
            "Runtime division outside convenience scope must still be rejected when -fconvenience is off");
   }

   @Test
   void testPopWithoutPushThrowsCompileError() {
      String badCode = """
            #pragma convenience(pop)
            void main(void) { }
            """;
      assertThrows(CompileError.class, () -> compileC(badCode));
   }
}
